package com.vibranium.orderservice.integration;

import com.vibranium.contracts.enums.OrderStatus;
import com.vibranium.contracts.enums.OrderType;
import com.vibranium.orderservice.application.service.SagaTimeoutCleanupJob;
import com.vibranium.orderservice.domain.model.Order;
import com.vibranium.orderservice.domain.repository.OrderOutboxRepository;
import com.vibranium.orderservice.domain.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [FASE RED → GREEN] — Testes de integração do {@link SagaTimeoutCleanupJob}.
 *
 * <p>Valida que ordens {@code PENDING} criadas antes do threshold configurável
 * são automaticamente canceladas com o motivo {@code SAGA_TIMEOUT} e que o
 * evento de cancelamento é persistido na tabela de outbox.</p>
 *
 * <h3>Estratégia do relógio fixo (AT-09.2)</h3>
 * <p>O bean {@link Clock} é substituído por um {@code Clock.fixed} apontando
 * para {@code Instant.now() + 1 hora}. Isso garante que o {@code cutoff}
 * calculado pelo job ({@code clock.instant() - 5min = now + 55min}) seja
 * sempre posterior ao {@code createdAt} da ordem ({@code ≈ now}), sem
 * necessidade de manipular timestamps no banco ou de {@code Thread.sleep}.</p>
 *
 * <h3>Fase RED</h3>
 * <p>Este teste falha antes da implementação pois:</p>
 * <ul>
 *   <li>{@link SagaTimeoutCleanupJob} não existe ainda.</li>
 *   <li>{@code OrderRepository.findByStatusAndCreatedAtBefore} não existe ainda.</li>
 * </ul>
 */
@DisplayName("AT-09.1 — SagaTimeoutCleanupJob: cancelamento automático de ordens PENDING expiradas")
@Import(SagaTimeoutCleanupJobTest.FixedClockConfig.class)
class SagaTimeoutCleanupJobTest extends AbstractIntegrationTest {

    // =========================================================================
    // Clock fixo para testes determinísticos (AT-09.2)
    // =========================================================================

    /**
     * Substitui o bean {@link Clock} de produção ({@code Clock.systemUTC()}) por um
     * relógio fixo 1 hora à frente do instante corrente.
     *
     * <p>Consequência: {@code cutoff = fixedClock.instant() - 5min = now + 55min}.
     * Toda ordem com {@code createdAt ≈ now} será considerada expirada, sem
     * necessidade de alterar timestamps no banco ou de {@code Thread.sleep}.
     * Testes ficam rápidos e completamente determinísticos (AT-09.2).</p>
     */
    @TestConfiguration
    static class FixedClockConfig {

        /**
         * Clock fixo 1h à frente garante que o job sempre trate ordens recém-criadas
         * como "expiradas" no contexto dos testes. {@code @Primary} sobrepõe o bean
         * {@code Clock.systemUTC()} definido em {@code TimeConfig}.
         */
        @Bean
        @Primary
        Clock testClock() {
            // T = Instant.now() em tempo de execução do teste
            // fixedClock = T + 1h → cutoff = T + 55min > T ≈ order.createdAt
            return Clock.fixed(Instant.now().plusSeconds(3_600), ZoneOffset.UTC);
        }
    }

    // =========================================================================
    // Beans injetados
    // =========================================================================

    @Autowired
    private SagaTimeoutCleanupJob sagaTimeoutCleanupJob;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderOutboxRepository outboxRepository;

    // =========================================================================
    // Fixture
    // =========================================================================

    @BeforeEach
    void setUp() {
        // Limpa estado entre testes para garantir isolamento
        outboxRepository.deleteAll();
        orderRepository.deleteAll();
    }

    // =========================================================================
    // AT-09.1 — Cancelamento de ordens PENDING expiradas
    // =========================================================================

    @Test
    @DisplayName("Ordem PENDING criada 'agora' deve ser cancelada pelo job (clock fixo 1h à frente)")
    void cancelStalePendingOrders_shouldCancelPendingOrderWithFixedFutureClock() {
        // Arrange — cria ordem PENDING; @PrePersist seta createdAt = Instant.now()
        // Com o clock fixo (T+1h), cutoff = T+55min > T ≈ createdAt → será cancelada
        Order pendingOrder = buildPendingOrder();
        orderRepository.save(pendingOrder);

        UUID orderId = pendingOrder.getId();

        // Pré-condição: status deve ser PENDING antes do job
        Order savedBefore = orderRepository.findById(orderId).orElseThrow();
        assertThat(savedBefore.getStatus())
                .as("Pré-condição: status deve ser PENDING antes do job")
                .isEqualTo(OrderStatus.PENDING);
        assertThat(outboxRepository.findAll())
                .as("Pré-condição: outbox deve estar vazio")
                .isEmpty();

        // Act — executa o job diretamente (sem Thread.sleep)
        sagaTimeoutCleanupJob.cancelStalePendingOrders();

        // Assert — status deve ser CANCELLED
        Order cancelledOrder = orderRepository.findById(orderId).orElseThrow();
        assertThat(cancelledOrder.getStatus())
                .as("Ordem deve estar CANCELLED após o job")
                .isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelledOrder.getCancellationReason())
                .as("Motivo deve ser SAGA_TIMEOUT")
                .isEqualTo("SAGA_TIMEOUT");

        // Assert — outbox deve conter OrderCancelledEvent
        var outboxMessages = outboxRepository.findAll();
        assertThat(outboxMessages)
                .as("Outbox deve ter exatamente 1 mensagem de cancelamento")
                .hasSize(1);
        assertThat(outboxMessages.get(0).getEventType())
                .as("Tipo do evento no outbox deve ser OrderCancelledEvent")
                .isEqualTo("OrderCancelledEvent");
        assertThat(outboxMessages.get(0).getAggregateId())
                .as("O aggregateId no outbox deve corresponder ao orderId")
                .isEqualTo(orderId);
        assertThat(outboxMessages.get(0).getPublishedAt())
                .as("Mensagem no outbox ainda não deve ter sido publicada")
                .isNull();
    }

    @Test
    @DisplayName("Ordem OPEN não deve ser cancelada pelo job (apenas PENDING é elegível)")
    void cancelStalePendingOrders_shouldNotCancelOpenOrder() {
        // Arrange — cria ordem e transita para OPEN antes de persistir
        Order openOrder = buildPendingOrder();
        openOrder.markAsOpen(); // PENDING → OPEN
        orderRepository.save(openOrder);

        UUID orderId = openOrder.getId();

        // Pré-condição
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.OPEN);

        // Act
        sagaTimeoutCleanupJob.cancelStalePendingOrders();

        // Assert — ordem OPEN deve permanecer OPEN
        Order afterJob = orderRepository.findById(orderId).orElseThrow();
        assertThat(afterJob.getStatus())
                .as("Ordem OPEN não deve ser cancelada pelo job de timeout")
                .isEqualTo(OrderStatus.OPEN);

        // Assert — outbox vazio: nenhum evento gerado para ordens não-PENDING
        assertThat(outboxRepository.findAll())
                .as("Outbox deve permanecer vazio para ordens que não são PENDING")
                .isEmpty();
    }

    @Test
    @DisplayName("Job deve ser idempotente: segunda execução não cancela nem duplica outbox")
    void cancelStalePendingOrders_shouldBeIdempotent() {
        // Arrange
        Order pendingOrder = buildPendingOrder();
        orderRepository.save(pendingOrder);
        UUID orderId = pendingOrder.getId();

        // Act — executa duas vezes
        sagaTimeoutCleanupJob.cancelStalePendingOrders(); // primeira execução → cancela
        sagaTimeoutCleanupJob.cancelStalePendingOrders(); // segunda execução → noop (já CANCELLED)

        // Assert — ainda CANCELLED, sem duplicata no outbox
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
        assertThat(outboxRepository.findAll())
                .as("Segunda execução não deve duplicar mensagem no outbox")
                .hasSize(1);
    }

    @Test
    @DisplayName("Job com lista vazia não deve lançar NullPointerException")
    void cancelStalePendingOrders_withEmptyList_shouldNotThrow() {
        // Arrange — repositório vazio
        assertThat(orderRepository.findAll()).isEmpty();

        // Act & Assert — não deve lançar exceção
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> sagaTimeoutCleanupJob.cancelStalePendingOrders());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Constrói uma {@link Order} em estado {@code PENDING} com dados sintéticos.
     * O {@code @PrePersist} definirá {@code createdAt = Instant.now()} ao salvar.
     */
    private Order buildPendingOrder() {
        return Order.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID().toString(), // userId (keycloakId sintético)
                UUID.randomUUID(),            // walletId
                OrderType.BUY,
                new BigDecimal("100.00"),
                new BigDecimal("1.00")
        );
    }
}
