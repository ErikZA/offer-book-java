package com.vibranium.walletservice.infrastructure.outbox;

import com.vibranium.walletservice.domain.repository.OutboxMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessagePropertiesBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Serviço responsável por publicar eventos do Outbox no RabbitMQ.
 *
 * <p>É chamado pelo {@link DebeziumOutboxEngine} para cada INSERT detectado
 * via CDC (Change Data Capture) no WAL do PostgreSQL.</p>
 *
 * <h2>Fluxo de publicação</h2>
 * <ol>
 *   <li>Recebe o evento CDC com os campos da linha {@code outbox_message}.</li>
 *   <li>Executa {@code UPDATE outbox_message SET processed=true WHERE id=? AND processed=false}
 *       (claim atômico): garante que em ambientes multi-instância apenas uma instância
 *       publique cada evento.</li>
 *   <li>Se retornar {@code 0}, outra instância já publicou — descarta silenciosamente.</li>
 *   <li>Usa {@link EventRoute#fromEventType(String)} para resolver exchange + routing-key.</li>
 *   <li>Publica via {@link RabbitTemplate#send(String, String, Message)},
 *       com o {@code message-id} AMQP igual ao UUID do registro outbox
 *       (permite idempotência no consumer).</li>
 * </ol>
 *
 * <h2>Tratamento de falhas</h2>
 * <ul>
 *   <li>{@link @Transactional}: se {@code rabbitTemplate.send()} lançar exceção,
 *       o {@code UPDATE processed=true} faz rollback → mensagem permanece
 *       {@code processed=false} → reprocessada no próximo ciclo Debezium.</li>
 *   <li>{@link @Retryable}: backoff exponencial de 500ms → até 10s, máx. 5 tentativas,
 *       antes do rollback transacional.</li>
 *   <li>{@link @Recover}: após esgotar tentativas, loga estruturadamente sem propagar
 *       exceção para não parar o engine Debezium.</li>
 * </ul>
 *
 * <p>Ativado apenas quando {@code app.outbox.debezium.enabled=true}, garantindo
 * que o bean não seja carregado em contextos sem infraestrutura AMQP
 * (ex: {@code @DataJpaTest}).</p>
 */
@Service
@ConditionalOnProperty(name = "app.outbox.debezium.enabled", havingValue = "true",
                       matchIfMissing = false)
public class OutboxPublisherService {

    private static final Logger logger = LoggerFactory.getLogger(OutboxPublisherService.class);

    // -------------------------------------------------------------------------
    // Dependências (constructor injection — imutável)
    // -------------------------------------------------------------------------

    private final RabbitTemplate             rabbitTemplate;
    private final OutboxMessageRepository    outboxRepository;

    public OutboxPublisherService(
            RabbitTemplate          rabbitTemplate,
            OutboxMessageRepository outboxRepository) {
        this.rabbitTemplate   = rabbitTemplate;
        this.outboxRepository = outboxRepository;
    }

    // -------------------------------------------------------------------------
    // Publicação com claim atômico
    // -------------------------------------------------------------------------

    /**
     * Tenta publicar o evento Outbox no RabbitMQ de forma idempotente.
     *
     * <p>O método roda dentro de uma transação para garantir que o claim atômico
     * ({@code UPDATE ... WHERE processed=false}) seja revertido caso a publicação
     * no broker falhe — mantendo {@code processed=false} para reprocessamento.</p>
     *
     * <p>{@code @Retryable} envolve o método inteiro: se o {@code rabbitTemplate.send()}
     * lançar {@link AmqpException}, a transação faz rollback e o Spring Retry
     * reagenda a execução com backoff exponencial sem precisar de scheduler externo.</p>
     *
     * @param eventId     UUID da linha {@code outbox_message} — usado como {@code message-id} AMQP.
     * @param eventType   Valor da coluna {@code event_type} (ex: {@code "FundsReservedEvent"}).
     * @param aggregateId Valor da coluna {@code aggregate_id} — incluído como header para rastreabilidade.
     * @param payload     Conteúdo da coluna {@code payload} (JSON serializado do evento de domínio).
     */
    @Transactional
    @Retryable(
        retryFor  = { AmqpException.class, Exception.class },
        maxAttempts = 5,
        backoff   = @Backoff(delay = 500, multiplier = 2, maxDelay = 10_000))
    public void claimAndPublish(UUID eventId, String eventType,
                                String aggregateId, String payload) {

        // --- Claim atômico: apenas a instância que atualizar 1 linha publica ---
        int claimed = outboxRepository.claimAndMarkProcessed(eventId);
        if (claimed == 0) {
            logger.debug("Outbox event {} já foi processado por outra instância — descartando.",
                    eventId);
            return;
        }

        // --- Resolve exchange + routing-key via catálogo de rotas ---------------
        EventRoute route = EventRoute.fromEventType(eventType);

        // --- Constrói a mensagem AMQP com o messageId = UUID do outbox ----------
        // O messageId permite que os consumers (ex: OrderCommandRabbitListener)
        // detectem duplicatas via tabela idempotency_key já presente no sistema.
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

        // --- Publica: se lançar exceção, @Transactional faz rollback do claim ---
        rabbitTemplate.send(route.getExchange(), route.getRoutingKey(), message);

        logger.info("Outbox event publicado: id={} type={} exchange={} routingKey={}",
                eventId, eventType, route.getExchange(), route.getRoutingKey());
    }

    // -------------------------------------------------------------------------
    // Recover — chamado após esgotar todas as tentativas de retry
    // -------------------------------------------------------------------------

    /**
     * Chamado pelo Spring Retry após esgotar as {@code maxAttempts} tentativas.
     *
     * <p>Neste ponto a transação foi revertida ({@code processed} permanece {@code false}).
     * O registro será reprocessado pelo Debezium no próximo ciclo ou
     * reconexão do engine. O erro é logado estruturadamente para alertas/monitoramento.</p>
     *
     * @param ex          Exceção da última tentativa.
     * @param eventId     ID do evento que falhou.
     * @param eventType   Tipo do evento.
     * @param aggregateId Aggregate raiz.
     * @param payload     Payload do evento (truncado no log por segurança).
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
