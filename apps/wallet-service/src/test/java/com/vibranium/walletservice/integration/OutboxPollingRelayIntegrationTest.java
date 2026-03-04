package com.vibranium.walletservice.integration;

import com.vibranium.walletservice.AbstractIntegrationTest;
import com.vibranium.walletservice.domain.repository.OutboxMessageRepository;
import org.junit.jupiter.api.*;
import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Testa o relay end-to-end do Outbox Publisher via Polling SKIP LOCKED.
 *
 * <p>Valida o ciclo completo: INSERT → @Scheduled polling → RabbitMQ.</p>
 *
 * <p>O @Scheduled é ativado automaticamente pelo contexto Spring Boot.
 * Não requer wal_level=logical nem replication slots — relay puramente baseado
 * em Polling com {@code SELECT FOR UPDATE SKIP LOCKED}.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("[Integration] OutboxPublisher — relay Polling SKIP LOCKED → RabbitMQ")
class OutboxPollingRelayIntegrationTest extends AbstractIntegrationTest {

    private static final String QUEUE_FUNDS_RESERVED = "test.polling.funds-reserved";
    private static final String QUEUE_FUNDS_FAILED   = "test.polling.funds-failed";
    private static final String QUEUE_FUNDS_SETTLED  = "test.polling.funds-settled";
    private static final String EVENTS_EXCHANGE      = "vibranium.events";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private OutboxMessageRepository outboxRepository;

    @BeforeEach
    void setupTestQueues() {
        declareAndBindQueue(QUEUE_FUNDS_RESERVED, "wallet.events.funds-reserved");
        declareAndBindQueue(QUEUE_FUNDS_FAILED,   "wallet.events.funds-reservation-failed");
        declareAndBindQueue(QUEUE_FUNDS_SETTLED,  "wallet.events.funds-settled");
    }

    // --- Teste 1: Relay básico ---
    @Test @Order(1)
    @DisplayName("Deve publicar FundsReservedEvent pendente via polling em até 10s")
    void shouldPublishPendingEventViaPolling() {
        UUID eventId  = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();
        insertOutboxMessage(eventId, "FundsReservedEvent", walletId,
                "{\"walletId\":\"" + walletId + "\",\"amount\":100.00}");

        await().atMost(10, TimeUnit.SECONDS)
               .pollInterval(500, TimeUnit.MILLISECONDS)
               .untilAsserted(() -> {
                   Message msg = rabbitTemplate.receive(QUEUE_FUNDS_RESERVED, 100);
                   assertThat(msg).as("Mensagem deve chegar via polling").isNotNull();
                   assertThat(msg.getMessageProperties().getMessageId())
                       .isEqualTo(eventId.toString());
               });

        // Assert: processed = true no banco
        assertThat(outboxRepository.findById(eventId).orElseThrow().isProcessed()).isTrue();
    }

    // --- Teste 2: Routing correto para diferentes event types ---
    @Test @Order(2)
    @DisplayName("Deve rotear FundsReservationFailedEvent para fila correta")
    void shouldRouteFailedEventToCorrectQueue() {
        UUID eventId = UUID.randomUUID();
        insertOutboxMessage(eventId, "FundsReservationFailedEvent", UUID.randomUUID(),
                "{\"reason\":\"insufficient_funds\"}");

        await().atMost(10, TimeUnit.SECONDS)
               .untilAsserted(() ->
                   assertThat(rabbitTemplate.receive(QUEUE_FUNDS_FAILED, 100)).isNotNull());
    }

    // --- Teste 3: Routing para FundsSettledEvent ---
    @Test @Order(3)
    @DisplayName("Deve rotear FundsSettledEvent para fila correta")
    void shouldRouteFundsSettledToCorrectQueue() {
        UUID eventId = UUID.randomUUID();
        insertOutboxMessage(eventId, "FundsSettledEvent", UUID.randomUUID(),
                "{\"amount\":200.00}");

        await().atMost(10, TimeUnit.SECONDS)
               .untilAsserted(() ->
                   assertThat(rabbitTemplate.receive(QUEUE_FUNDS_SETTLED, 100)).isNotNull());
    }

    // --- Teste 4: Idempotência — processada=true não é republicada ---
    @Test @Order(4)
    @DisplayName("Mensagem já processada NÃO deve ser republicada (idempotência)")
    void shouldNotRepublishAlreadyProcessedMessage() {
        UUID eventId = UUID.randomUUID();
        insertOutboxMessage(eventId, "FundsReservedEvent", UUID.randomUUID(),
                "{\"amount\":75.00}");

        // Aguarda primeira publicação
        await().atMost(10, TimeUnit.SECONDS)
               .untilAsserted(() ->
                   assertThat(rabbitTemplate.receive(QUEUE_FUNDS_RESERVED, 100)).isNotNull());

        // Não deve haver segunda mensagem
        await().during(2, TimeUnit.SECONDS)
               .atMost(3, TimeUnit.SECONDS)
               .untilAsserted(() ->
                   assertThat(rabbitTemplate.receive(QUEUE_FUNDS_RESERVED, 50)).isNull());
    }

    // --- Teste 5: Carga — 500 mensagens processadas em tempo razoável ---
    @Test @Order(5)
    @DisplayName("Deve processar 500 mensagens pendentes em até 30s via polling")
    void shouldProcess500MessagesViaPolling() {
        int count = 500;
        for (int i = 0; i < count; i++) {
            insertOutboxMessage(UUID.randomUUID(), "FundsReservedEvent",
                    UUID.randomUUID(), "{\"amount\":" + i + "}");
        }

        await().atMost(30, TimeUnit.SECONDS)
               .untilAsserted(() -> {
                   long pending = outboxRepository.countByProcessedFalseAndEventType("FundsReservedEvent");
                   assertThat(pending).as("Todas as 500 mensagens processadas").isZero();
               });
    }

    // --- Helpers ---

    private void insertOutboxMessage(UUID id, String eventType, UUID aggregateId, String payload) {
        jdbcTemplate.update("""
            INSERT INTO outbox_message (id, event_type, aggregate_id, payload, created_at, processed)
            VALUES (?, ?, ?, ?, ?, ?)
            """, id, eventType, aggregateId.toString(), payload, Timestamp.from(Instant.now()), false);
    }

    private void declareAndBindQueue(String queueName, String routingKey) {
        TopicExchange exchange = new TopicExchange(EVENTS_EXCHANGE, true, false);
        rabbitAdmin.declareExchange(exchange);
        Queue queue = new Queue(queueName, true, false, false);
        rabbitAdmin.declareQueue(queue);
        rabbitAdmin.declareBinding(BindingBuilder.bind(queue).to(exchange).with(routingKey));
        rabbitAdmin.purgeQueue(queueName, false);
    }
}
