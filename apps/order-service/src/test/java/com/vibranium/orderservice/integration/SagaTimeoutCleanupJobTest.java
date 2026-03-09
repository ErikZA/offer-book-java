package com.vibranium.orderservice.integration;

import com.vibranium.contracts.enums.OrderStatus;
import com.vibranium.contracts.enums.OrderType;
import com.vibranium.orderservice.application.service.SagaTimeoutCleanupJob;
import com.vibranium.orderservice.domain.model.Order;
import com.vibranium.orderservice.domain.model.OrderOutboxMessage;
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
import java.util.List;
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

        // Assert — outbox deve conter OrderCancelledEvent + ReleaseFundsCommand
        // (emissão incondicional de ReleaseFundsCommand cobre janela de corrida PENDING→OPEN)
        var outboxMessages = outboxRepository.findAll();
        assertThat(outboxMessages)
                .as("Outbox deve ter 2 mensagens: OrderCancelledEvent + ReleaseFundsCommand")
                .hasSize(2);
        assertThat(outboxMessages.stream().map(OrderOutboxMessage::getEventType))
                .containsExactlyInAnyOrder("OrderCancelledEvent", "ReleaseFundsCommand");
        assertThat(outboxMessages.stream().allMatch(m -> orderId.equals(m.getAggregateId())))
                .as("Todos aggregateIds no outbox devem corresponder ao orderId")
                .isTrue();
        assertThat(outboxMessages.stream().allMatch(m -> m.getPublishedAt() == null))
                .as("Mensagens no outbox ainda não devem ter sido publicadas")
                .isTrue();
    }

    @Test
    @DisplayName("[1.1.4] Ordem OPEN expirada deve ser cancelada com OrderCancelledEvent + ReleaseFundsCommand")
    void cancelStalePendingOrders_shouldNotCancelOpenOrder() {
        // Comportamento atualizado em AT-1.1.4: ordens OPEN expiradas também são canceladas
        // e geram ReleaseFundsCommand compensatório, pois os fundos já foram reservados.
        Order openOrder = buildPendingOrder();
        openOrder.markAsOpen(); // PENDING → OPEN
        orderRepository.save(openOrder);

        UUID orderId = openOrder.getId();

        // Pré-condição
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.OPEN);
        assertThat(outboxRepository.findAll()).isEmpty();

        // Act
        sagaTimeoutCleanupJob.cancelStalePendingOrders();

        // Assert — ordem OPEN deve ser CANCELLED (fundos já reservados → precisa de compensação)
        Order afterJob = orderRepository.findById(orderId).orElseThrow();
        assertThat(afterJob.getStatus())
                .as("[AT-1.1.4] Ordem OPEN expirada deve ser CANCELLED")
                .isEqualTo(OrderStatus.CANCELLED);
        assertThat(afterJob.getCancellationReason())
                .as("Motivo deve ser SAGA_TIMEOUT")
                .isEqualTo("SAGA_TIMEOUT");

        // Assert — outbox deve ter 2 mensagens: OrderCancelledEvent + ReleaseFundsCommand
        List<OrderOutboxMessage> outboxMessages = outboxRepository.findAll();
        assertThat(outboxMessages)
                .as("[AT-1.1.4] Outbox deve ter OrderCancelledEvent + ReleaseFundsCommand para ordem OPEN")
                .hasSize(2);
        assertThat(outboxMessages.stream().map(OrderOutboxMessage::getEventType))
                .containsExactlyInAnyOrder("OrderCancelledEvent", "ReleaseFundsCommand");
    }

    // =========================================================================
    // AT-1.1.4 — Emissão do ReleaseFundsCommand para ordens com fundos reservados
    // =========================================================================

    @Test
    @DisplayName("[AT-1.1.4 RED] Timeout de ordem OPEN deve gerar OrderCancelledEvent + ReleaseFundsCommand")
    void testTimeout_orderOpen_emitsReleaseFundsCommand() {
        // Arrange — cria ordem BUY no estado OPEN (fundos já reservados)
        Order openOrder = buildPendingOrder();
        openOrder.markAsOpen(); // PENDING → OPEN (simula FundsReservedEvent já processado)
        orderRepository.save(openOrder);
        UUID orderId = openOrder.getId();

        // Pré-condição: status OPEN, outbox vazio
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.OPEN);
        assertThat(outboxRepository.findAll()).isEmpty();

        // Act — job com clock fixo 1h à frente → ordem OPEN é considerada expirada
        sagaTimeoutCleanupJob.cancelStalePendingOrders();

        // Assert — ordem deve ser CANCELLED
        Order cancelled = orderRepository.findById(orderId).orElseThrow();
        assertThat(cancelled.getStatus())
                .as("[AT-1.1.4] Ordem OPEN expirada deve ser CANCELLED")
                .isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelled.getCancellationReason()).isEqualTo("SAGA_TIMEOUT");

        // Assert — outbox deve ter OrderCancelledEvent E ReleaseFundsCommand
        List<OrderOutboxMessage> messages = outboxRepository.findAll();
        assertThat(messages)
                .as("[AT-1.1.4] Outbox deve ter 2 mensagens: OrderCancelledEvent + ReleaseFundsCommand")
                .hasSize(2);
        assertThat(messages.stream().map(OrderOutboxMessage::getEventType))
                .containsExactlyInAnyOrder("OrderCancelledEvent", "ReleaseFundsCommand");

        // Assert — ReleaseFundsCommand deve referenciar a ordem correta
        OrderOutboxMessage releaseMsg = messages.stream()
                .filter(m -> "ReleaseFundsCommand".equals(m.getEventType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("ReleaseFundsCommand não encontrado no outbox"));
        assertThat(releaseMsg.getAggregateId())
                .as("ReleaseFundsCommand deve ter o orderId como aggregateId")
                .isEqualTo(orderId);
        assertThat(releaseMsg.getPublishedAt())
                .as("Mensagem no outbox ainda não deve ter sido publicada")
                .isNull();
    }

    @Test
    @DisplayName("[AT-1.1.4] Timeout de ordem PENDING DEVE gerar ReleaseFundsCommand (cobertura da janela de corrida)")
    void testTimeout_orderPending_emitsReleaseFundsCommand() {
        // Arrange — ordem PENDING: pode ter fundos reservados no wallet-service
        // devido à janela de corrida entre reserva efetiva e atualização de status.
        Order pendingOrder = buildPendingOrder();
        orderRepository.save(pendingOrder);
        UUID orderId = pendingOrder.getId();

        // Act
        sagaTimeoutCleanupJob.cancelStalePendingOrders();

        // Assert — OrderCancelledEvent + ReleaseFundsCommand (emissão incondicional)
        // O wallet-service trata InsufficientLockedFundsException como no-op idempotente
        // caso os fundos nunca tenham sido reservados.
        List<OrderOutboxMessage> messages = outboxRepository.findAll();
        assertThat(messages)
                .as("[AT-1.1.4] Ordem PENDING deve gerar OrderCancelledEvent + ReleaseFundsCommand")
                .hasSize(2);
        assertThat(messages.stream().map(OrderOutboxMessage::getEventType))
                .containsExactlyInAnyOrder("OrderCancelledEvent", "ReleaseFundsCommand");
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
        // Primeira execução gera 2 mensagens (OrderCancelledEvent + ReleaseFundsCommand);
        // segunda execução não deve adicionar mais nenhuma.
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
        assertThat(outboxRepository.findAll())
                .as("Segunda execução não deve duplicar mensagem no outbox")
                .hasSize(2);
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
