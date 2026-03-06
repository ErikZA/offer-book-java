package com.vibranium.orderservice.application.service;

import com.vibranium.orderservice.config.OutboxProperties;
import com.vibranium.orderservice.domain.model.OrderOutboxMessage;
import com.vibranium.orderservice.domain.repository.OrderOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

/**
 * Serviço de relay do Outbox Pattern para o order-service.
 *
 * <p>Executa periodicamente para publicar no RabbitMQ os comandos armazenados
 * na tabela {@code tb_order_outbox} pelo {@link OrderCommandService#placeOrder}.
 * Após publicação bem-sucedida, marca {@code published_at} para que o comando
 * não seja reenviado na próxima execução.</p>
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
public class OrderOutboxPublisherService {

    private static final Logger logger = LoggerFactory.getLogger(OrderOutboxPublisherService.class);

    private final OrderOutboxRepository        outboxRepository;
    private final RabbitTemplate               rabbitTemplate;
    private final TransactionTemplate          transactionTemplate;
    private final int                          batchSize;
    // Self-proxy para que chamadas internas passem pelo proxy AOP (@Retryable).
    // @Lazy evita referência circular durante a construção do bean.
    private final OrderOutboxPublisherService  self;

    /**
     * @param outboxRepository    Repositório do Outbox com suporte a SKIP LOCKED.
     * @param rabbitTemplate      Template para publicação no RabbitMQ.
     * @param transactionTemplate Template transacional (evita @Transactional no @Scheduled).
     * @param outboxProperties    Configurações do módulo outbox (batch-size, delay-ms).
     * @param self                Auto-referência via proxy para @Retryable funcionar em chamada interna.
     */
    public OrderOutboxPublisherService(OrderOutboxRepository outboxRepository,
                                       RabbitTemplate rabbitTemplate,
                                       TransactionTemplate transactionTemplate,
                                       OutboxProperties outboxProperties,
                                       @Lazy OrderOutboxPublisherService self) {
        this.outboxRepository    = outboxRepository;
        this.rabbitTemplate      = rabbitTemplate;
        this.transactionTemplate = transactionTemplate;
        this.batchSize           = outboxProperties.batchSize();
        this.self                = self;
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
        transactionTemplate.executeWithoutResult(status -> {
            List<OrderOutboxMessage> pending = outboxRepository.findPendingWithLock(batchSize);

            if (pending.isEmpty()) {
                return;
            }

            logger.debug("Outbox relay: {} mensagem(ns) pendente(s) para publicação", pending.size());

            for (OrderOutboxMessage msg : pending) {
                // Chamada via self-proxy: garante que @Retryable/@Recover sejam interceptados
                // pelo proxy AOP. Chamada direta (this.publishSingle) ignoraria as annotations.
                self.publishSingle(msg);
            }
        });
    }

    // -------------------------------------------------------------------------
    // Publicação com retry
    // -------------------------------------------------------------------------

    /**
     * Publica uma única mensagem do outbox no RabbitMQ com retry automático.
     *
     * <p>Usa {@link RabbitTemplate#send} com bytes raw em vez de
     * {@link RabbitTemplate#convertAndSend}: o payload já é JSON serializado
     * ({@code String}), e passar uma {@code String} ao {@code Jackson2JsonMessageConverter}
     * causaria double-serialization — a mensagem chegaria aos consumers como
     * {@code "\"{ ... }\""} em vez de {@code { ... }}.</p>
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
        Message amqpMessage = MessageBuilder
                .withBody(msg.getPayload().getBytes(StandardCharsets.UTF_8))
                .andProperties(new MessageProperties())
                .build();
        amqpMessage.getMessageProperties().setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbitTemplate.send(msg.getExchange(), msg.getRoutingKey(), amqpMessage);

        msg.markAsPublished();
        outboxRepository.save(msg);

        logger.info("Outbox relay: publicado eventType={} aggregateId={} outboxId={}",
                msg.getEventType(), msg.getAggregateId(), msg.getId());
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
        logger.error("Outbox relay: falha permanente ao publicar outboxId={} eventType={} "
                        + "após múltiplas tentativas. error={}",
                msg.getId(), msg.getEventType(), ex.getMessage(), ex);
        // Não propaga exceção — mensagem permanece com published_at=null para próximo ciclo.
    }
}
