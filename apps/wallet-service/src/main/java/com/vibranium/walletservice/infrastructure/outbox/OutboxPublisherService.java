package com.vibranium.walletservice.infrastructure.outbox;

import com.vibranium.utils.outbox.AbstractOutboxPublisher;
import com.vibranium.walletservice.config.OutboxProperties;
import com.vibranium.walletservice.domain.model.OutboxMessage;
import com.vibranium.walletservice.domain.repository.OutboxMessageRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessagePropertiesBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Serviço responsável por publicar eventos do Outbox no RabbitMQ via polling.
 *
 * <p>Estende {@link AbstractOutboxPublisher} do módulo {@code common-utils},
 * fornecendo implementações específicas do wallet-service:
 * claim atômico ({@code UPDATE ... WHERE processed=false}),
 * roteamento via {@link EventRoute} e construção de mensagem com headers.</p>
 *
 * <h2>Fluxo de publicação</h2>
 * <ol>
 *   <li>{@code @Scheduled} dispara periodicamente (configurável via
 *       {@code app.outbox.polling.interval-ms}, default 2000ms).</li>
 *   <li>Executa {@code SELECT ... FOR UPDATE SKIP LOCKED LIMIT :batchSize}
 *       para obter mensagens pendentes com lock exclusivo — instâncias
 *       concorrentes processam lotes diferentes.</li>
 *   <li>Para cada mensagem, executa claim atômico ({@code UPDATE ... WHERE processed=false}),
 *       publica no RabbitMQ e marca como processada.</li>
 * </ol>
 *
 * <h2>Tratamento de falhas</h2>
 * <ul>
 *   <li>{@code @Transactional}: se {@code rabbitTemplate.send()} lançar exceção,
 *       o {@code UPDATE processed=true} faz rollback → mensagem permanece
 *       {@code processed=false} → reprocessada no próximo ciclo.</li>
 *   <li>{@code @Retryable}: backoff exponencial de 500ms → até 10s, máx. 5 tentativas.</li>
 *   <li>{@code @Recover}: após esgotar tentativas, loga sem propagar exceção.</li>
 * </ul>
 *
 * <h2>Escalabilidade horizontal</h2>
 * <p>{@code SKIP LOCKED} garante que N instâncias do serviço possam executar
 * este polling simultaneamente sem duplicatas nem deadlocks. Cada instância
 * processa apenas as linhas que conseguiu bloquear.</p>
 */
@Service
public class OutboxPublisherService extends AbstractOutboxPublisher<OutboxMessage> {

    private final OutboxMessageRepository outboxRepository;

    /** AT-15.2: Timer para latência de publicação de mensagens do outbox. */
    private final Timer outboxPublishTimer;

    /**
     * @param rabbitTemplate   Template para publicação no RabbitMQ.
     * @param outboxRepository Repositório do Outbox com suporte a SKIP LOCKED.
     * @param outboxProperties Configurações do módulo outbox (batch-size, polling interval).
     * @param meterRegistry    Registry Micrometer para métricas de negócio.
     */
    public OutboxPublisherService(
            RabbitTemplate          rabbitTemplate,
            OutboxMessageRepository outboxRepository,
            OutboxProperties        outboxProperties,
            MeterRegistry           meterRegistry) {
        super(rabbitTemplate, outboxProperties.batchSize());
        this.outboxRepository = outboxRepository;
        this.outboxPublishTimer = Timer.builder("vibranium.outbox.publish.latency")
                .description("Latency of outbox message publish to RabbitMQ")
                .register(meterRegistry);
    }

    // -------------------------------------------------------------------------
    // Polling scheduler
    // -------------------------------------------------------------------------

    /**
     * Executa periodicamente para publicar mensagens pendentes do Outbox.
     *
     * <p>O {@code fixedDelayString} referencia a propriedade YAML
     * {@code app.outbox.polling.interval-ms} com default de 2000ms.
     * {@code fixedDelay} garante que a próxima execução só começa após
     * o término da anterior — prevenindo overlap.</p>
     */
    @Scheduled(fixedDelayString = "${app.outbox.polling.interval-ms:2000}")
    @Transactional
    public void publishPendingMessages() {
        pollAndPublish();
    }

    // -------------------------------------------------------------------------
    // Extension points — AbstractOutboxPublisher
    // -------------------------------------------------------------------------

    @Override
    protected List<OutboxMessage> findPendingMessages(int batchSize) {
        return outboxRepository.findPendingWithLock(batchSize);
    }

    /**
     * AT-15.2: sobrescreve dispatchMessage para medir latência de publicação via Timer.
     */
    @Override
    protected void dispatchMessage(OutboxMessage message) {
        outboxPublishTimer.record(() -> doPublish(message));
    }

    /**
     * Claim atômico: {@code UPDATE ... WHERE processed=false}.
     * Retorna {@code false} se outra instância já processou a mensagem.
     */
    @Override
    protected boolean beforePublish(OutboxMessage message) {
        return outboxRepository.claimAndMarkProcessed(message.getId()) > 0;
    }

    @Override
    protected Message buildAmqpMessage(OutboxMessage msg) {
        EventRoute route = EventRoute.fromEventType(msg.getEventType());
        return MessageBuilder
                .withBody(msg.getPayload().getBytes(StandardCharsets.UTF_8))
                .andProperties(
                    MessagePropertiesBuilder.newInstance()
                        .setMessageId(msg.getId().toString())
                        .setContentType("application/json")
                        .setHeader("aggregate-id", msg.getAggregateId())
                        .setHeader("event-type",   msg.getEventType())
                        .build())
                .build();
    }

    @Override
    protected String resolveExchange(OutboxMessage msg) {
        return EventRoute.fromEventType(msg.getEventType()).getExchange();
    }

    @Override
    protected String resolveRoutingKey(OutboxMessage msg) {
        return EventRoute.fromEventType(msg.getEventType()).getRoutingKey();
    }

    @Override
    protected Object getMessageId(OutboxMessage msg) {
        return msg.getId();
    }

    @Override
    protected String getEventType(OutboxMessage msg) {
        return msg.getEventType();
    }

    // -------------------------------------------------------------------------
    // Retry + Recover (preservados para backward compatibility)
    // -------------------------------------------------------------------------

    /**
     * Publicação com retry — anotação preservada para compatibilidade.
     * Chamada internamente via {@link #dispatchMessage} (default: this.doPublish).
     */
    @Retryable(
        retryFor  = { Exception.class },
        maxAttempts = 5,
        backoff   = @Backoff(delay = 500, multiplier = 2, maxDelay = 10_000))
    public void claimAndPublish(UUID eventId, String eventType,
                                String aggregateId, String payload) {

        // Método preservado para backward compatibility com chamadores externos.
        // Internamente o fluxo agora passa por pollAndPublish → doPublish.
        int claimed = outboxRepository.claimAndMarkProcessed(eventId);
        if (claimed == 0) {
            logger.debug("Outbox event {} já processado por outra instância — descartando.",
                    eventId);
            return;
        }

        EventRoute route = EventRoute.fromEventType(eventType);

        Message message = MessageBuilder
                .withBody(payload.getBytes(StandardCharsets.UTF_8))
                .andProperties(
                    MessagePropertiesBuilder.newInstance()
                        .setMessageId(eventId.toString())
                        .setContentType("application/json")
                        .setHeader("aggregate-id", aggregateId)
                        .setHeader("event-type",   eventType)
                        .build())
                .build();

        getRabbitTemplate().send(route.getExchange(), route.getRoutingKey(), message);

        logger.info("Outbox event publicado: id={} type={} exchange={} routingKey={}",
                eventId, eventType, route.getExchange(), route.getRoutingKey());
    }

    /**
     * Chamado pelo Spring Retry após esgotar as {@code maxAttempts} tentativas.
     *
     * <p>A transação foi revertida ({@code processed} permanece {@code false}).
     * O registro será reprocessado no próximo ciclo de polling.</p>
     */
    @Recover
    public void recoverPublishFailure(Exception ex, UUID eventId, String eventType,
                                      String aggregateId, String payload) {
        logger.error(
            "Falha permanente ao publicar evento Outbox após múltiplas tentativas. "
                + "id={} eventType={} aggregateId={} payloadLen={} error={}",
            eventId, eventType, aggregateId,
            payload != null ? payload.length() : 0,
            ex.getMessage(), ex);
    }
}
