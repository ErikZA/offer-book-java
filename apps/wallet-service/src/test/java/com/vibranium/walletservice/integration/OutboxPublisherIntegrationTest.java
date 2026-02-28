package com.vibranium.walletservice.integration;

import com.vibranium.walletservice.AbstractIntegrationTest;
import com.vibranium.walletservice.domain.model.OutboxMessage;
import com.vibranium.walletservice.domain.repository.OutboxMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * [RED → GREEN] Testa o relay end-to-end do Outbox Publisher via Debezium CDC.
 *
 * <p>Ativa o engine Debezium via {@code app.outbox.debezium.enabled=true} para
 * este contexto de teste isolado. O PostgreSQL container já está configurado
 * com {@code wal_level=logical} em {@link AbstractIntegrationTest}.</p>
 *
 * <p><b>Fluxo testado:</b> INSERT em {@code outbox_message} →
 * Debezium captura via WAL → {@link com.vibranium.walletservice.infrastructure.outbox.OutboxPublisherService}
 * publica na exchange {@code vibranium.events} → fila de teste recebe a mensagem →
 * {@code processed = true} no banco.</p>
 */
@TestPropertySource(properties = "app.outbox.debezium.enabled=true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("[Integration] OutboxPublisher — relay Debezium → RabbitMQ")
class OutboxPublisherIntegrationTest extends AbstractIntegrationTest {

    // -------------------------------------------------------------------------
    // Constantes de filas de teste (auto-delete)
    // -------------------------------------------------------------------------

    /** Fila temporária de teste para os eventos de FundsReserved. */
    private static final String QUEUE_FUNDS_RESERVED = "test.outbox.funds-reserved";

    /** Fila temporária de teste para os eventos de FundsReservationFailed. */
    private static final String QUEUE_FUNDS_FAILED   = "test.outbox.funds-failed";

    /** Fila temporária de teste para os eventos de FundsSettled. */
    private static final String QUEUE_FUNDS_SETTLED  = "test.outbox.funds-settled";

    /** Exchange de domínio onde todos os eventos do wallet são publicados. */
    private static final String EVENTS_EXCHANGE = "vibranium.events";

    // -------------------------------------------------------------------------
    // Dependências
    // -------------------------------------------------------------------------

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OutboxMessageRepository outboxRepository;

    // -------------------------------------------------------------------------
    // Setup: declaração e limpeza de filas de teste
    // -------------------------------------------------------------------------

    /**
     * Declara filas temporárias (auto-delete) e as vincula à exchange de eventos
     * com as routing-keys correspondentes a cada tipo de evento.
     * Purga as filas para garantir isolamento entre cenários.
     */
    @BeforeEach
    void setupTestQueues() {
        declareAndBindQueue(QUEUE_FUNDS_RESERVED, "wallet.events.funds-reserved");
        declareAndBindQueue(QUEUE_FUNDS_FAILED,   "wallet.events.funds-reservation-failed");
        declareAndBindQueue(QUEUE_FUNDS_SETTLED,  "wallet.events.funds-settled");
    }

    // -------------------------------------------------------------------------
    // Subtask 1.4 — Testes de integração do relay
    // -------------------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("Deve publicar FundsReservedEvent pendente na exchange vibranium.events em até 10s")
    void shouldPublishFundsReservedEventToRabbitMQ() {
        // Arrange: insert direto no outbox simulando o WalletService
        UUID eventId   = UUID.randomUUID();
        UUID walletId  = UUID.randomUUID();
        String payload = buildPayload(walletId, "100.00");

        insertOutboxMessage(eventId, "FundsReservedEvent", walletId, payload);

        // Act + Assert: aguarda até 10s que o Debezium entregue a mensagem na fila
        await().atMost(10, TimeUnit.SECONDS)
               .pollInterval(200, TimeUnit.MILLISECONDS)
               .untilAsserted(() -> {
                   Message msg = rabbitTemplate.receive(QUEUE_FUNDS_RESERVED, 100);
                   assertThat(msg)
                       .as("Mensagem deve ter chegado na fila %s", QUEUE_FUNDS_RESERVED)
                       .isNotNull();
                   assertThat(msg.getMessageProperties().getMessageId())
                       .as("messageId deve ser o UUID do registro outbox")
                       .isEqualTo(eventId.toString());
               });

        // Assert: registro marcado como processed=true no banco
        OutboxMessage stored = outboxRepository.findById(eventId).orElseThrow();
        assertThat(stored.isProcessed())
            .as("processed deve ser true após publicação confirmada")
            .isTrue();
    }

    @Test
    @Order(2)
    @DisplayName("Deve publicar FundsReservationFailedEvent na routing-key correta")
    void shouldPublishFundsReservationFailedEventToCorrectQueue() {
        UUID eventId  = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();

        insertOutboxMessage(eventId, "FundsReservationFailedEvent", walletId,
                buildPayload(walletId, "50.00"));

        await().atMost(10, TimeUnit.SECONDS)
               .pollInterval(200, TimeUnit.MILLISECONDS)
               .untilAsserted(() ->
                   assertThat(rabbitTemplate.receive(QUEUE_FUNDS_FAILED, 100))
                       .isNotNull()
               );
    }

    @Test
    @Order(3)
    @DisplayName("Deve publicar FundsSettledEvent na routing-key correta")
    void shouldPublishFundsSettledEventToCorrectQueue() {
        UUID eventId  = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();

        insertOutboxMessage(eventId, "FundsSettledEvent", walletId,
                buildPayload(walletId, "200.00"));

        await().atMost(10, TimeUnit.SECONDS)
               .pollInterval(200, TimeUnit.MILLISECONDS)
               .untilAsserted(() ->
                   assertThat(rabbitTemplate.receive(QUEUE_FUNDS_SETTLED, 100))
                       .isNotNull()
               );
    }

    @Test
    @Order(4)
    @DisplayName("A mesma mensagem não deve ser publicada duas vezes (idempotência do publisher)")
    void shouldNotPublishSameMessageTwice() {
        UUID eventId  = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();

        insertOutboxMessage(eventId, "FundsReservedEvent", walletId,
                buildPayload(walletId, "75.00"));

        // Aguarda a primeira publicação
        await().atMost(10, TimeUnit.SECONDS)
               .pollInterval(200, TimeUnit.MILLISECONDS)
               .untilAsserted(() ->
                   assertThat(rabbitTemplate.receive(QUEUE_FUNDS_RESERVED, 100)).isNotNull()
               );

        // Verifica que não há segunda mensagem na fila após additional 1s
        await().during(1, TimeUnit.SECONDS)
               .atMost(1500, TimeUnit.MILLISECONDS)
               .untilAsserted(() ->
                   assertThat(rabbitTemplate.receive(QUEUE_FUNDS_RESERVED, 50))
                       .as("Não deve haver segunda mensagem na fila (at-most-once com claim)")
                       .isNull()
               );
    }

    @Test
    @Order(5)  // Teste de carga sempre por último para não interferir nos demais
    @DisplayName("Deve suportar 500 publicações simultâneas sem atraso > 15s por ciclo")
    void shouldHandle500ConcurrentOutboxMessages() throws InterruptedException {
        // Insere 500 mensagens no outbox em lote via JDBC
        int count = 500;
        for (int i = 0; i < count; i++) {
            insertOutboxMessage(UUID.randomUUID(), "FundsReservedEvent",
                    UUID.randomUUID(), "{\"amount\":" + i + "}");
        }

        // Aguarda que TODAS as 500 mensagens sejam publicadas em até 15s
        await().atMost(15, TimeUnit.SECONDS)
               .untilAsserted(() -> {
                   long unpublished = outboxRepository.countByProcessedFalseAndEventType("FundsReservedEvent");
                   assertThat(unpublished)
                       .as("Todas as 500 mensagens devem estar processadas em 15s")
                       .isZero();
               });
    }

    // -------------------------------------------------------------------------
    // Helpers privados
    // -------------------------------------------------------------------------

    /**
     * Insere uma linha diretamente na tabela {@code outbox_message} via JDBC,
     * simulando o que o {@link com.vibranium.walletservice.application.service.WalletService}
     * faria dentro de uma transação de negócio.
     */
    private void insertOutboxMessage(UUID id, String eventType, UUID aggregateId, String payload) {
        jdbcTemplate.update(
            """
            INSERT INTO outbox_message (id, event_type, aggregate_id, payload, created_at, processed)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            id, eventType, aggregateId.toString(), payload,
            Timestamp.from(Instant.now()), false);
    }

    /**
     * Cria um payload JSON mínimo com walletId e amount para os eventos de fundos.
     */
    private String buildPayload(UUID walletId, String amount) {
        return """
               {"walletId":"%s","amount":%s}
               """.formatted(walletId, amount).strip();
    }

    /**
     * Declara a exchange de eventos, uma fila durável e vincula-os com a routing-key fornecida.
     * Purga a fila para garantir isolamento entre cenários.
     *
     * <p>A fila é declarada como <b>durable + não auto-delete</b> para garantir que não seja
     * removida entre chamadas de {@code rabbitTemplate.receive()} dentro do Awaitility.</p>
     *
     * @param queueName  Nome da fila de teste.
     * @param routingKey Routing-key para binding na exchange {@code vibranium.events}.
     */
    private void declareAndBindQueue(String queueName, String routingKey) {
        // Garante que a exchange existe (pode ter sido criada pelo RabbitMQConfig,
        // mas declarar novamente é idempotente no RabbitMQ)
        TopicExchange exchange = new TopicExchange(EVENTS_EXCHANGE, true, false);
        rabbitAdmin.declareExchange(exchange);

        // Fila durável e não-auto-delete: persiste entre chamadas de receive() dentro do Awaitility
        Queue queue = new Queue(queueName, true, false, false);
        rabbitAdmin.declareQueue(queue);
        rabbitAdmin.declareBinding(
            BindingBuilder.bind(queue)
                          .to(exchange)
                          .with(routingKey));
        rabbitAdmin.purgeQueue(queueName, false);
    }
}
