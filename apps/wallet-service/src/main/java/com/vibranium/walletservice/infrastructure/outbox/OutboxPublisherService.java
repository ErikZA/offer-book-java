package com.vibranium.walletservice.infrastructure.outbox;

import com.vibranium.walletservice.config.OutboxProperties;
import com.vibranium.walletservice.domain.model.OutboxMessage;
import com.vibranium.walletservice.domain.repository.OutboxMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
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
 * <p>Implementação do relay do Transactional Outbox Pattern usando
 * {@code SELECT FOR UPDATE SKIP LOCKED} para suporte a múltiplas instâncias.</p>
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
public class OutboxPublisherService {

    private static final Logger logger = LoggerFactory.getLogger(OutboxPublisherService.class);

    private final RabbitTemplate          rabbitTemplate;
    private final OutboxMessageRepository outboxRepository;
    private final int                     batchSize;

    /**
     * @param rabbitTemplate   Template para publicação no RabbitMQ.
     * @param outboxRepository Repositório do Outbox com suporte a SKIP LOCKED.
     * @param outboxProperties Configurações do módulo outbox (batch-size, polling interval).
     */
    public OutboxPublisherService(
            RabbitTemplate          rabbitTemplate,
            OutboxMessageRepository outboxRepository,
            OutboxProperties        outboxProperties) {
        this.rabbitTemplate   = rabbitTemplate;
        this.outboxRepository = outboxRepository;
        this.batchSize        = outboxProperties.batchSize();
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
        List<OutboxMessage> pending = outboxRepository.findPendingWithLock(batchSize);

        if (pending.isEmpty()) {
            return;
        }

        logger.debug("Outbox polling: {} mensagem(ns) pendente(s)", pending.size());

        for (OutboxMessage msg : pending) {
            claimAndPublish(msg.getId(), msg.getEventType(),
                    msg.getAggregateId(), msg.getPayload());
        }
    }

    // -------------------------------------------------------------------------
    // Publicação com claim atômico
    // -------------------------------------------------------------------------

    /**
     * Tenta publicar o evento Outbox no RabbitMQ de forma idempotente.
     *
     * <p>O claim atômico ({@code UPDATE ... WHERE processed=false}) garante
     * que mesmo com SKIP LOCKED, se duas instâncias processarem a mesma
     * mensagem (cenário raro de race condition), apenas uma publica.</p>
     *
     * @param eventId     UUID da linha {@code outbox_message}.
     * @param eventType   Valor da coluna {@code event_type}.
     * @param aggregateId Valor da coluna {@code aggregate_id}.
     * @param payload     Conteúdo JSON do evento de domínio.
     */
    @Retryable(
        retryFor  = { AmqpException.class, Exception.class },
        maxAttempts = 5,
        backoff   = @Backoff(delay = 500, multiplier = 2, maxDelay = 10_000))
    public void claimAndPublish(UUID eventId, String eventType,
                                String aggregateId, String payload) {

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

        rabbitTemplate.send(route.getExchange(), route.getRoutingKey(), message);

        logger.info("Outbox event publicado: id={} type={} exchange={} routingKey={}",
                eventId, eventType, route.getExchange(), route.getRoutingKey());
    }

    // -------------------------------------------------------------------------
    // Recover
    // -------------------------------------------------------------------------

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
