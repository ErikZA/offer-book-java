package com.vibranium.orderservice.domain.model;

import com.vibranium.contracts.enums.OrderStatus;
import com.vibranium.contracts.enums.OrderType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testes unitários puros da máquina de estados do agregado {@link Order}.
 *
 * <p><strong>Escopo:</strong> sem Spring, sem banco, sem Docker — roda em &lt; 1 segundo.</p>
 *
 * <p><strong>Invariantes verificadas:</strong></p>
 * <ul>
 *   <li>OPEN      → remainingAmount == originalAmount</li>
 *   <li>PARTIAL   → 0 &lt; remainingAmount &lt; originalAmount</li>
 *   <li>FILLED    → remainingAmount == 0</li>
 *   <li>CANCELLED → pode ocorrer em PENDING, OPEN ou PARTIAL; nunca em FILLED</li>
 * </ul>
 */
@DisplayName("Order — Máquina de Estados (Testes Unitários)")
class OrderDomainTest {

    // -------------------------------------------------------------------------
    // Helpers de fixture
    // -------------------------------------------------------------------------

    private static final BigDecimal PRICE  = new BigDecimal("500.00");
    private static final BigDecimal AMOUNT = new BigDecimal("10.00");

    /**
     * Cria uma Order nova no estado PENDING — ponto de partida de todos os testes.
     */
    private Order pendingOrder() {
        return Order.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "user-keycloak-id",
                UUID.randomUUID(),
                OrderType.BUY,
                PRICE,
                AMOUNT
        );
    }

    // =========================================================================
    // Subtask 8.1 — markAsOpen()
    // =========================================================================

    @Nested
    @DisplayName("markAsOpen()")
    class MarkAsOpenTests {

        @Test
        @DisplayName("PENDING → OPEN: deve transicionar corretamente")
        void whenPending_shouldTransitionToOpen() {
            Order order = pendingOrder();

            order.markAsOpen(); // [RED] método ainda não existe em Order.java

            assertThat(order.getStatus()).isEqualTo(OrderStatus.OPEN);
            assertThat(order.getUpdatedAt()).isNotNull();
        }

        @Test
        @DisplayName("OPEN → OPEN: deve lançar IllegalStateException")
        void whenOpen_shouldThrowIllegalStateException() {
            Order order = pendingOrder();
            order.markAsOpen();

            assertThatThrownBy(order::markAsOpen)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("markAsOpen")
                    .hasMessageContaining("PENDING");
        }

        @Test
        @DisplayName("CANCELLED → OPEN: deve lançar IllegalStateException")
        void whenCancelled_shouldThrow() {
            Order order = pendingOrder();
            order.cancel("TEST");

            assertThatThrownBy(order::markAsOpen)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("markAsOpen");
        }

        @Test
        @DisplayName("Invariante OPEN: remainingAmount deve ser igual ao originalAmount")
        void openInvariant_remainingAmountEqualsOriginalAmount() {
            Order order = pendingOrder();
            order.markAsOpen();

            assertThat(order.getRemainingAmount().compareTo(order.getAmount()))
                    .as("remainingAmount deve ser igual a amount no estado OPEN")
                    .isZero();
        }
    }

    // =========================================================================
    // Subtask 8.2 — applyMatch()
    // =========================================================================

    @Nested
    @DisplayName("applyMatch()")
    class ApplyMatchTests {

        private Order openOrder;

        @BeforeEach
        void setup() {
            openOrder = pendingOrder();
            openOrder.markAsOpen(); // transita para OPEN usando o novo método semântico
        }

        @Test
        @DisplayName("OPEN → PARTIAL: match parcial deve decrementar remainingAmount")
        void partialMatch_shouldDecrementAndTransitionToPartial() {
            BigDecimal executedQty = new BigDecimal("3.00");

            openOrder.applyMatch(executedQty);

            assertThat(openOrder.getStatus()).isEqualTo(OrderStatus.PARTIAL);
            assertThat(openOrder.getRemainingAmount().compareTo(new BigDecimal("7.00"))).isZero();
        }

        @Test
        @DisplayName("OPEN → FILLED: match total deve zerar remainingAmount")
        void fullMatch_shouldTransitionToFilled() {
            openOrder.applyMatch(AMOUNT); // executa 100%

            assertThat(openOrder.getStatus()).isEqualTo(OrderStatus.FILLED);
            assertThat(openOrder.getRemainingAmount().compareTo(BigDecimal.ZERO)).isZero();
        }

        @Test
        @DisplayName("PARTIAL → FILLED: segundo match completo deve finalizar a ordem")
        void partialThenFull_shouldTransitionFromPartialToFilled() {
            openOrder.applyMatch(new BigDecimal("6.00")); // PARTIAL: remaining = 4
            openOrder.applyMatch(new BigDecimal("4.00")); // FILLED:  remaining = 0

            assertThat(openOrder.getStatus()).isEqualTo(OrderStatus.FILLED);
            assertThat(openOrder.getRemainingAmount().compareTo(BigDecimal.ZERO)).isZero();
        }

        @Test
        @DisplayName("PARTIAL → PARTIAL: match intermediário mantém status PARTIAL")
        void twoPartialMatches_shouldRemainPartial() {
            openOrder.applyMatch(new BigDecimal("3.00")); // remaining = 7
            openOrder.applyMatch(new BigDecimal("3.00")); // remaining = 4

            assertThat(openOrder.getStatus()).isEqualTo(OrderStatus.PARTIAL);
            assertThat(openOrder.getRemainingAmount().compareTo(new BigDecimal("4.00"))).isZero();
        }

        @Test
        @DisplayName("executedQty negativa deve lançar IllegalArgumentException")
        void negativeQty_shouldThrowIllegalArgumentException() {
            assertThatThrownBy(() -> openOrder.applyMatch(new BigDecimal("-1.00")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("executedQty");
        }

        @Test
        @DisplayName("executedQty zero deve lançar IllegalArgumentException")
        void zeroQty_shouldThrowIllegalArgumentException() {
            assertThatThrownBy(() -> openOrder.applyMatch(BigDecimal.ZERO))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("executedQty");
        }

        @Test
        @DisplayName("executedQty nula deve lançar IllegalArgumentException")
        void nullQty_shouldThrowIllegalArgumentException() {
            assertThatThrownBy(() -> openOrder.applyMatch(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("executedQty");
        }

        @Test
        @DisplayName("executedQty maior que remainingAmount deve lançar IllegalArgumentException")
        void qtyExceedsRemaining_shouldThrowIllegalArgumentException() {
            BigDecimal oversized = AMOUNT.add(new BigDecimal("0.01")); // 10.01 > 10.00

            assertThatThrownBy(() -> openOrder.applyMatch(oversized))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("remainingAmount");
        }

        @Test
        @DisplayName("[DLQ Policy] CANCELLED: applyMatch deve lançar IllegalStateException → vai para DLQ")
        void whenCancelled_applyMatch_shouldThrowIllegalStateException() {
            Order order = pendingOrder();
            order.cancel("INSUFFICIENT_FUNDS");

            // Simula race condition: evento de match chegando após cancelamento
            // A exceção impede corrupção de dados e o container RabbitMQ encaminha para DLQ
            assertThatThrownBy(() -> order.applyMatch(new BigDecimal("5.00")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("applyMatch");
        }

        @Test
        @DisplayName("[DLQ Policy] FILLED: applyMatch deve lançar IllegalStateException → vai para DLQ")
        void whenFilled_applyMatch_shouldThrowIllegalStateException() {
            openOrder.applyMatch(AMOUNT); // FILLED

            assertThatThrownBy(() -> openOrder.applyMatch(new BigDecimal("1.00")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("applyMatch");
        }

        @Test
        @DisplayName("[DLQ Policy] PENDING: applyMatch deve lançar IllegalStateException → vai para DLQ")
        void whenPending_applyMatch_shouldThrowIllegalStateException() {
            Order order = pendingOrder(); // PENDING — fundos ainda não reservados

            assertThatThrownBy(() -> order.applyMatch(new BigDecimal("5.00")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("applyMatch");
        }

        @Test
        @DisplayName("Invariante PARTIAL: 0 < remainingAmount < originalAmount")
        void partialInvariant_remainingMustBeBetweenZeroAndOriginal() {
            openOrder.applyMatch(new BigDecimal("4.00")); // remaining = 6

            assertThat(openOrder.getRemainingAmount().compareTo(BigDecimal.ZERO))
                    .as("remainingAmount deve ser > 0 em PARTIAL")
                    .isGreaterThan(0);
            assertThat(openOrder.getRemainingAmount().compareTo(openOrder.getAmount()))
                    .as("remainingAmount deve ser < originalAmount em PARTIAL")
                    .isLessThan(0);
        }
    }

    // =========================================================================
    // Subtask 8.3 — cancel()
    // =========================================================================

    @Nested
    @DisplayName("cancel()")
    class CancelTests {

        @Test
        @DisplayName("PENDING → CANCELLED: deve cancelar com motivo")
        void whenPending_shouldCancelWithReason() {
            Order order = pendingOrder();

            order.cancel("INSUFFICIENT_FUNDS");

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(order.getCancellationReason()).isEqualTo("INSUFFICIENT_FUNDS");
            assertThat(order.getUpdatedAt()).isNotNull();
        }

        @Test
        @DisplayName("OPEN → CANCELLED: deve cancelar ordem aberta")
        void whenOpen_shouldCancel() {
            Order order = pendingOrder();
            order.markAsOpen();

            order.cancel("INTERNAL_ERROR");

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(order.getCancellationReason()).isEqualTo("INTERNAL_ERROR");
        }

        @Test
        @DisplayName("PARTIAL → CANCELLED: deve cancelar ordem parcialmente executada")
        void whenPartial_shouldCancel() {
            Order order = pendingOrder();
            order.markAsOpen();
            order.applyMatch(new BigDecimal("3.00")); // PARTIAL

            order.cancel("MARKET_CLOSED");

            assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        }

        @Test
        @DisplayName("FILLED → CANCELLED: deve lançar IllegalStateException (liquidação já ocorreu)")
        void whenFilled_shouldThrowIllegalStateException() {
            Order order = pendingOrder();
            order.markAsOpen();
            order.applyMatch(AMOUNT); // FILLED

            // Uma ordem totalmente executada não pode ser revertida
            assertThatThrownBy(() -> order.cancel("ATTEMPT_TO_REVERSE"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("FILLED")
                    .hasMessageContaining(order.getId().toString());
        }
    }
}
