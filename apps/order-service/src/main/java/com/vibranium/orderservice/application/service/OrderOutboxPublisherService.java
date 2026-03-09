package com.vibranium.orderservice.application.service;

import com.vibranium.orderservice.config.OutboxProperties;
import com.vibranium.orderservice.domain.model.OrderOutboxMessage;
import com.vibranium.orderservice.domain.repository.OrderOutboxRepository;
import com.vibranium.utils.outbox.AbstractOutboxPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Lazy;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Serviço de relay do Outbox Pattern para o order-service.
 *
 * <p>Estende {@link AbstractOutboxPublisher} do módulo {@code common-utils},
 * fornecendo implementações específicas do order-service:
 * roteamento via colunas {@code exchange}/{@code routing_key} da entidade,
 * {@code TransactionTemplate} para controle transacional explícito, e
 * self-proxy para interceptação do {@code @Retryable}.</p>
 *
 * <h2>Fluxo de publicação</h2>
 * <ol>
 *   <li>{@code OrderCommandService} grava a mensagem com {@code published_at = NULL}.</li>
 *   <li>{@code @Scheduled} dispara periodicamente (configurável via
 *       {@code app.outbox.delay-ms}, default 500ms).</li>
 *   <li>Executa {@code SELECT ... FOR UPDATE SKIP LOCKED LIMIT :batchSize}
 *       para obter mensagens pendentes com lock exclusivo — instâncias
 *       concorrentes processam lotes diferentes.</li>
 *   <li>Para cada mensagem, publica no RabbitMQ via {@link RabbitTemplate#send}
 *       (bytes raw para evitar double-serialization).</li>
 *   <li>Atualiza {@code published_at = now()} via {@link OrderOutboxMessage#markAsPublished()}.</li>
 * </ol>
 *
 * <h2>Tratamento de falhas</h2>
 * <ul>
 *   <li>{@code TransactionTemplate}: o {@code @Scheduled} não usa {@code @Transactional}
 *       diretamente — delega para {@link TransactionTemplate} que gerencia a transação.</li>
 *   <li>{@code @Retryable}: backoff exponencial de 500ms → até 5s, máx. 3 tentativas.</li>
 *   <li>{@code @Recover}: após esgotar tentativas, loga sem propagar exceção —
 *       mensagem permanece com {@code published_at = NULL} para reprocessamento.</li>
 * </ul>
 *
 * <h2>Escalabilidade horizontal</h2>
 * <p>{@code SKIP LOCKED} garante que N instâncias do order-service possam executar
 * este polling simultaneamente sem duplicatas nem deadlocks. Cada instância
 * processa apenas as linhas que conseguiu bloquear.</p>
 *
 * @see com.vibranium.orderservice.config.OutboxConfig
 * @see com.vibranium.orderservice.config.OutboxProperties
 */
@Service
public class OrderOutboxPublisherService extends AbstractOutboxPublisher<OrderOutboxMessage> {

    /**
     * Mapa de resolução: nome simples do eventType armazenado no outbox → FQN esperado
     * pelo listener do wallet-service para roteamento via header AMQP {@code type}.
     * Comandos/eventos não mapeados são enviados com o eventType original (nome simples).
     */
    private static final Map<String, String> EVENT_TYPE_FQN_MAP = Map.of(
            "ReserveFundsCommand",  "com.vibranium.contracts.commands.wallet.ReserveFundsCommand",
            "ReleaseFundsCommand",  "com.vibranium.contracts.commands.wallet.ReleaseFundsCommand",
            "SettleFundsCommand",   "com.vibranium.contracts.commands.wallet.SettleFundsCommand"
    );

    private final OrderOutboxRepository        outboxRepository;
    private final TransactionTemplate          transactionTemplate;
    // Self-proxy para que chamadas internas passem pelo proxy AOP (@Retryable).
    // @Lazy evita referência circular durante a construção do bean.
    private final OrderOutboxPublisherService  self;

    /** AT-15.2: Timer para latência de publicação de mensagens do outbox. */
    private final Timer outboxPublishTimer;

    /**
     * @param outboxRepository    Repositório do Outbox com suporte a SKIP LOCKED.
     * @param rabbitTemplate      Template para publicação no RabbitMQ.
     * @param transactionTemplate Template transacional (evita @Transactional no @Scheduled).
     * @param outboxProperties    Configurações do módulo outbox (batch-size, delay-ms).
     * @param self                Auto-referência via proxy para @Retryable funcionar em chamada interna.
     * @param meterRegistry       Registry Micrometer para métricas de negócio.
     */
    public OrderOutboxPublisherService(OrderOutboxRepository outboxRepository,
                                       RabbitTemplate rabbitTemplate,
                                       TransactionTemplate transactionTemplate,
                                       OutboxProperties outboxProperties,
                                       @Lazy OrderOutboxPublisherService self,
                                       MeterRegistry meterRegistry) {
        super(rabbitTemplate, outboxProperties.batchSize());
        this.outboxRepository    = outboxRepository;
        this.transactionTemplate = transactionTemplate;
        this.self                = self;
        this.outboxPublishTimer  = Timer.builder("vibranium.outbox.publish.latency")
                .description("Latency of outbox message publish to RabbitMQ")
                .register(meterRegistry);
    }

    // -------------------------------------------------------------------------
    // Polling scheduler
    // -------------------------------------------------------------------------

    /**
     * Executa periodicamente para publicar mensagens pendentes do Outbox.
     *
     * <p>Configurável via {@code app.outbox.delay-ms} (padrão: 500ms).
     * O {@code fixedDelay} garante que a próxima execução só começa após
     * o término da anterior — prevenindo overlap.</p>
     *
     * <p>Usa {@link TransactionTemplate} em vez de {@code @Transactional}
     * para manter o {@code SELECT FOR UPDATE SKIP LOCKED} dentro de uma
     * transação explícita sem anotar o método {@code @Scheduled}.</p>
     */
    @Scheduled(fixedDelayString = "${app.outbox.delay-ms:500}")
    public void publishPendingMessages() {
        transactionTemplate.executeWithoutResult(status -> pollAndPublish());
    }

    // -------------------------------------------------------------------------
    // Extension points — AbstractOutboxPublisher
    // -------------------------------------------------------------------------

    @Override
    protected List<OrderOutboxMessage> findPendingMessages(int batchSize) {
        return outboxRepository.findPendingWithLock(batchSize);
    }

    /**
     * Usa self-proxy para que {@code @Retryable} em {@link #publishSingle}
     * seja interceptado pelo proxy AOP.
     * AT-15.2: mede latência de publicação via Timer.
     */
    @Override
    protected void dispatchMessage(OrderOutboxMessage message) {
        outboxPublishTimer.record(() -> self.publishSingle(message));
    }

    @Override
    protected Message buildAmqpMessage(OrderOutboxMessage msg) {
        Message amqpMessage = MessageBuilder
                .withBody(msg.getPayload().getBytes(StandardCharsets.UTF_8))
                .andProperties(new MessageProperties())
                .build();
        amqpMessage.getMessageProperties().setContentType(MessageProperties.CONTENT_TYPE_JSON);
        amqpMessage.getMessageProperties().setMessageId(msg.getId().toString());
        // BUG-FIX: seta o header AMQP 'type' com o FQN da classe do comando/evento.
        // O listener do wallet-service usa este header para roteamento tipado.
        // Resolve nome simples (armazenado no outbox) → FQN via mapa estático.
        String resolvedType = EVENT_TYPE_FQN_MAP.getOrDefault(msg.getEventType(), msg.getEventType());
        amqpMessage.getMessageProperties().setType(resolvedType);
        return amqpMessage;
    }

    @Override
    protected String resolveExchange(OrderOutboxMessage msg) {
        return msg.getExchange();
    }

    @Override
    protected String resolveRoutingKey(OrderOutboxMessage msg) {
        return msg.getRoutingKey();
    }

    @Override
    protected void afterPublish(OrderOutboxMessage msg) {
        msg.markAsPublished();
        outboxRepository.save(msg);
    }

    @Override
    protected Object getMessageId(OrderOutboxMessage msg) {
        return msg.getId();
    }

    @Override
    protected String getEventType(OrderOutboxMessage msg) {
        return msg.getEventType();
    }

    // -------------------------------------------------------------------------
    // Publicação com retry
    // -------------------------------------------------------------------------

    /**
     * Publica uma única mensagem do outbox no RabbitMQ com retry automático.
     *
     * <p>Chamado via self-proxy ({@link #dispatchMessage}) para garantir
     * interceptação do {@code @Retryable} pelo proxy AOP.</p>
     *
     * <p>Em caso de {@link AmqpException}, {@code @Retryable} executa backoff
     * exponencial (500ms → 1s → 2s, máx. 3 tentativas). Se todas falharem,
     * {@link #recoverPublishFailure} é invocado.</p>
     *
     * @param msg Mensagem do outbox a ser publicada.
     */
    @Retryable(
            retryFor    = AmqpException.class,
            maxAttempts = 3,
            backoff     = @Backoff(delay = 500, multiplier = 2, maxDelay = 5000))
    public void publishSingle(OrderOutboxMessage msg) {
        doPublish(msg);
    }

    // -------------------------------------------------------------------------
    // Recover
    // -------------------------------------------------------------------------

    /**
     * Chamado pelo Spring Retry após esgotar as {@code maxAttempts} tentativas.
     *
     * <p>A mensagem permanece com {@code published_at = NULL} e será
     * reprocessada no próximo ciclo de polling. Não propaga exceção
     * para não interromper o processamento do lote restante.</p>
     *
     * @param ex  Última exceção lançada pelo {@code publishSingle}.
     * @param msg Mensagem que falhou permanentemente neste ciclo.
     */
    @Recover
    public void recoverPublishFailure(Exception ex, OrderOutboxMessage msg) {
        doRecover(ex, msg);
    }
}
