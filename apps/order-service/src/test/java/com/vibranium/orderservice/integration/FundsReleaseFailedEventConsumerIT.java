package com.vibranium.orderservice.integration;

import com.vibranium.contracts.enums.FailureReason;
import com.vibranium.contracts.enums.OrderStatus;
import com.vibranium.contracts.enums.OrderType;
import com.vibranium.contracts.events.wallet.FundsReleaseFailedEvent;
import com.vibranium.orderservice.config.RabbitMQConfig;
import com.vibranium.orderservice.domain.model.Order;
import com.vibranium.orderservice.domain.model.OrderOutboxMessage;
import com.vibranium.orderservice.domain.model.ProcessedEvent;
import com.vibranium.orderservice.domain.repository.OrderOutboxRepository;
import com.vibranium.orderservice.domain.repository.OrderRepository;
import com.vibranium.orderservice.domain.repository.ProcessedEventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * [FASE RED → GREEN] — Testes de integração do {@link com.vibranium.orderservice.infrastructure.messaging.FundsReleaseFailedEventConsumer}.
 *
 * <h2>Objetivo</h2>
 * <p>Publicar um {@link FundsReleaseFailedEvent} na exchange {@code vibranium.events} e verificar
 * via Awaitility que:</p>
 * <ul>
 *   <li>A ordem está {@code CANCELLED} no PostgreSQL.</li>
 *   <li>{@code OrderCancelledEvent} está no outbox.</li>
 *   <li>{@code ProcessedEvent} registrado (idempotência).</li>
 * </ul>
 *
 * <h2>Estado FASE RED</h2>
 * <p>Antes da implementação:</p>
 * <ul>
 *   <li>{@code FundsReleaseFailedEventConsumer} não existe.</li>
 *   <li>A fila {@code order.events.funds-release-failed} não está declarada.</li>
 *   <li>O binding para routing key {@code wallet.events.funds-release-failed} não existe.</li>
 *   <li>O teste falha com {@code ConditionTimeoutException} pois nenhum consumer captura o evento.</li>
 * </ul>
 */
@DisplayName("Atividade 5 (IT) — FundsReleaseFailedEventConsumer: integração end-to-end")
class FundsReleaseFailedEventConsumerIT extends AbstractIntegrationTest {

    private static final String EVENTS_EXCHANGE = "vibranium.events";

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderOutboxRepository outboxRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    private UUID orderId;
    private UUID correlationId;
    private UUID walletId;

    @BeforeEach
    void setUp() {
        orderId       = UUID.randomUUID();
        correlationId = UUID.randomUUID();
        walletId      = UUID.randomUUID();

        // Persiste uma ordem OPEN no banco (simula ordem que já teve fundos reservados)
        Order order = Order.create(
                orderId, correlationId,
                UUID.randomUUID().toString(),
                walletId,
                OrderType.BUY,
                new BigDecimal("500.00"),
                new BigDecimal("10.00000000")
        );
        order.markAsOpen();
        orderRepository.saveAndFlush(order);
    }

    @AfterEach
    void tearDown() {
        outboxRepository.deleteAll();
        processedEventRepository.deleteAll();
        orderRepository.deleteAll();
    }

    // =========================================================================
    // Cenário 1 — Processamento end-to-end: ordem CANCELLED + outbox + idempotência
    // =========================================================================

    @Test
    @DisplayName("[RED→GREEN] FundsReleaseFailedEvent deve cancelar ordem no PostgreSQL e gravar outbox")
    void testReleaseFailed_cancelsOrderAndWritesOutbox() {
        // Publica o evento de falha de release de fundos
        FundsReleaseFailedEvent event = FundsReleaseFailedEvent.of(
                correlationId, orderId, walletId.toString(),
                FailureReason.RELEASE_DB_ERROR,
                "DB write failed during release (teste IT)"
        );
        rabbitTemplate.convertAndSend(
                EVENTS_EXCHANGE,
                RabbitMQConfig.RK_FUNDS_RELEASE_FAILED,
                event
        );

        // Aguarda até que a ordem esteja CANCELLED no PostgreSQL
        await().atMost(15, TimeUnit.SECONDS)
                .pollInterval(300, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    Order updated = orderRepository.findById(orderId).orElseThrow();
                    assertThat(updated.getStatus())
                            .as("Ordem deve estar CANCELLED no PostgreSQL")
                            .isEqualTo(OrderStatus.CANCELLED);
                    assertThat(updated.getCancellationReason())
                            .as("Reason deve conter RELEASE_FAILED")
                            .contains("RELEASE_FAILED");
                });

        // Verifica OrderCancelledEvent no outbox
        await().atMost(5, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    List<OrderOutboxMessage> outboxMessages = outboxRepository.findByPublishedAtIsNull();
                    assertThat(outboxMessages)
                            .as("Deve haver pelo menos 1 OrderCancelledEvent no outbox")
                            .anyMatch(msg -> "OrderCancelledEvent".equals(msg.getEventType())
                                    && msg.getPayload().contains(orderId.toString()));
                });

        // Verifica ProcessedEvent registrado (idempotência)
        Optional<ProcessedEvent> processed = processedEventRepository.findById(event.eventId());
        assertThat(processed)
                .as("ProcessedEvent deve ter sido registrado para idempotência")
                .isPresent();
    }

    // =========================================================================
    // Cenário 2 — Idempotência end-to-end: publicar 2x → apenas 1 cancel
    // =========================================================================

    @Test
    @DisplayName("[RED→GREEN] Publicar mesmo evento 2x deve resultar em apenas 1 cancel no PostgreSQL")
    void testReleaseFailed_idempotency_sameEventTwice() {
        FundsReleaseFailedEvent event = FundsReleaseFailedEvent.of(
                correlationId, orderId, walletId.toString(),
                FailureReason.RELEASE_DB_ERROR,
                "Idempotency test"
        );

        // Publica 2x o mesmo evento
        rabbitTemplate.convertAndSend(EVENTS_EXCHANGE, RabbitMQConfig.RK_FUNDS_RELEASE_FAILED, event);
        rabbitTemplate.convertAndSend(EVENTS_EXCHANGE, RabbitMQConfig.RK_FUNDS_RELEASE_FAILED, event);

        // Aguarda cancelamento
        await().atMost(15, TimeUnit.SECONDS)
                .pollInterval(300, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    Order updated = orderRepository.findById(orderId).orElseThrow();
                    assertThat(updated.getStatus()).isEqualTo(OrderStatus.CANCELLED);
                });

        // Verifica que apenas 1 OrderCancelledEvent está no outbox
        await().atMost(5, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    long cancelledCount = outboxRepository.findByPublishedAtIsNull().stream()
                            .filter(msg -> "OrderCancelledEvent".equals(msg.getEventType()))
                            .filter(msg -> msg.getPayload().contains(orderId.toString()))
                            .count();
                    assertThat(cancelledCount)
                            .as("Mesmo evento publicado 2x deve gerar apenas 1 OrderCancelledEvent")
                            .isEqualTo(1L);
                });
    }
}
