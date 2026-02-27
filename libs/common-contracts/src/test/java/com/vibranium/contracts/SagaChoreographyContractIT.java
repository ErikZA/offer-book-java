package com.vibranium.contracts;

import com.vibranium.contracts.commands.order.CreateOrderCommand;
import com.vibranium.contracts.commands.wallet.ReserveFundsCommand;
import com.vibranium.contracts.commands.wallet.SettleFundsCommand;
import com.vibranium.contracts.enums.AssetType;
import com.vibranium.contracts.enums.FailureReason;
import com.vibranium.contracts.enums.OrderType;
import com.vibranium.contracts.events.order.*;
import com.vibranium.contracts.events.wallet.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Teste de Integração de Contrato: simula o fluxo completo da Saga de Coreografia
 * sem nenhuma dependência externa (sem Spring, sem RabbitMQ, sem banco de dados).
 *
 * Objetivo: garantir que os campos de correlação (correlationId) e identificadores
 * de negócio (orderId, walletId, matchId) se propagam corretamente entre os
 * contratos ao longo de toda a Saga.
 *
 * Saga completa simulada:
 * CreateOrderCommand
 *   → OrderReceivedEvent
 *     → ReserveFundsCommand
 *       → [SUCESSO] FundsReservedEvent
 *           → OrderAddedToBookEvent
 *             → MatchExecutedEvent
 *               → SettleFundsCommand
 *                 → FundsSettledEvent
 *                   → OrderFilledEvent
 *       → [FALHA]  FundsReservationFailedEvent
 *           → OrderCancelledEvent
 */
@DisplayName("Saga Choreography Contract IT — Propagação de Correlação")
class SagaChoreographyContractIT {

    // =========================================================================
    // Cenário 1 — Caminho Feliz Completo (Happy Path)
    // =========================================================================
    @Nested
    @DisplayName("Cenário 1: Caminho feliz — BUY order com match total")
    class HappyPath {

        @Test
        @DisplayName("correlationId deve ser o mesmo do início ao fim da Saga")
        void correlationId_propagatedThroughEntireSaga() {
            // ---- FASE 1: Entrada do comando ----
            UUID correlationId = UUID.randomUUID();
            UUID orderId    = UUID.randomUUID();
            UUID userId     = UUID.randomUUID();
            UUID walletId   = UUID.randomUUID();
            BigDecimal price  = new BigDecimal("155.00");
            BigDecimal amount = new BigDecimal("10.00");

            CreateOrderCommand cmd = new CreateOrderCommand(
                    correlationId, orderId, userId, walletId,
                    OrderType.BUY, price, amount);

            assertThat(cmd.correlationId()).isEqualTo(correlationId);

            // ---- FASE 2: Order Service emite OrderReceivedEvent ----
            OrderReceivedEvent orderReceived = OrderReceivedEvent.of(
                    cmd.correlationId(), cmd.orderId(), cmd.userId(),
                    cmd.walletId(), cmd.orderType(), cmd.price(), cmd.amount());

            assertThat(orderReceived.correlationId()).isEqualTo(correlationId);
            assertThat(orderReceived.aggregateId()).isEqualTo(orderId.toString());

            // ---- FASE 3: Wallet Service consome e emite ReserveFundsCommand ----
            ReserveFundsCommand reserveCmd = new ReserveFundsCommand(
                    orderReceived.correlationId(),
                    orderReceived.orderId(),
                    orderReceived.walletId(),
                    AssetType.BRL, // BUY → bloqueia BRL
                    orderReceived.price().multiply(orderReceived.amount())
            );

            assertThat(reserveCmd.correlationId()).isEqualTo(correlationId);
            assertThat(reserveCmd.orderId()).isEqualTo(orderId);

            // ---- FASE 4: Wallet Service conclui bloqueio → FundsReservedEvent ----
            FundsReservedEvent fundsReserved = FundsReservedEvent.of(
                    reserveCmd.correlationId(),
                    reserveCmd.orderId(),
                    reserveCmd.walletId(),
                    reserveCmd.asset(),
                    reserveCmd.amount());

            assertThat(fundsReserved.correlationId()).isEqualTo(correlationId);
            assertThat(fundsReserved.orderId()).isEqualTo(orderId);

            // ---- FASE 5: Order Service adiciona ao livro ----
            OrderAddedToBookEvent addedToBook = OrderAddedToBookEvent.of(
                    fundsReserved.correlationId(),
                    fundsReserved.orderId(),
                    OrderType.BUY, price, amount);

            assertThat(addedToBook.correlationId()).isEqualTo(correlationId);

            // ---- FASE 6: Motor de match executa cruzamento ----
            UUID sellOrderId    = UUID.randomUUID();
            UUID sellerUserId   = UUID.randomUUID();
            UUID sellerWalletId = UUID.randomUUID();

            MatchExecutedEvent matchEvent = MatchExecutedEvent.of(
                    addedToBook.correlationId(),
                    addedToBook.orderId(), sellOrderId,
                    userId, sellerUserId, walletId, sellerWalletId,
                    price, amount);

            assertThat(matchEvent.correlationId()).isEqualTo(correlationId);
            assertThat(matchEvent.buyOrderId()).isEqualTo(orderId);
            assertThat(matchEvent.matchId()).isNotNull();

            // ---- FASE 7: Wallet Service executa liquidação ----
            SettleFundsCommand settleCmd = new SettleFundsCommand(
                    matchEvent.correlationId(),
                    matchEvent.matchId(),
                    matchEvent.buyOrderId(),
                    matchEvent.sellOrderId(),
                    matchEvent.buyerWalletId(),
                    matchEvent.sellerWalletId(),
                    matchEvent.matchPrice(),
                    matchEvent.matchAmount());

            assertThat(settleCmd.correlationId()).isEqualTo(correlationId);
            assertThat(settleCmd.matchId()).isEqualTo(matchEvent.matchId());

            // ---- FASE 8: Liquidação concluída ----
            FundsSettledEvent settled = FundsSettledEvent.of(
                    settleCmd.correlationId(),
                    settleCmd.matchId(),
                    settleCmd.buyOrderId(),
                    settleCmd.sellOrderId(),
                    settleCmd.buyerWalletId(),
                    settleCmd.sellerWalletId(),
                    settleCmd.matchPrice(),
                    settleCmd.matchAmount());

            assertThat(settled.correlationId()).isEqualTo(correlationId);
            assertThat(settled.matchId()).isEqualTo(matchEvent.matchId());

            // ---- FASE 9: Order Service marca ordem como FILLED ----
            OrderFilledEvent filled = OrderFilledEvent.of(
                    settled.correlationId(),
                    settled.buyOrderId(),
                    settled.matchAmount(),
                    settled.matchPrice());

            assertThat(filled.correlationId()).isEqualTo(correlationId);
            assertThat(filled.orderId()).isEqualTo(orderId);

            // Asserção final: correlationId intacto do início ao fim
            assertThat(filled.correlationId())
                .isEqualTo(cmd.correlationId())
                .isEqualTo(orderReceived.correlationId())
                .isEqualTo(fundsReserved.correlationId())
                .isEqualTo(matchEvent.correlationId())
                .isEqualTo(settled.correlationId());
        }
    }

    // =========================================================================
    // Cenário 2 — Caminho de Falha: Saldo Insuficiente
    // =========================================================================
    @Nested
    @DisplayName("Cenário 2: Saldo insuficiente — Saga compensatória")
    class InsufficientFundsPath {

        @Test
        @DisplayName("correlationId deve ser preservado até o evento de cancelamento")
        void compensatingPath_correlationIdPreserved() {
            UUID correlationId = UUID.randomUUID();
            UUID orderId = UUID.randomUUID();
            UUID walletId = UUID.randomUUID();

            // Comando original
            CreateOrderCommand cmd = new CreateOrderCommand(
                    correlationId, orderId, UUID.randomUUID(), walletId,
                    OrderType.BUY, new BigDecimal("200.00"), new BigDecimal("10.00"));

            // Wallet Service detecta saldo insuficiente
            FundsReservationFailedEvent failedEvent = FundsReservationFailedEvent.of(
                    cmd.correlationId(),
                    cmd.orderId(),
                    cmd.walletId().toString(),
                    FailureReason.INSUFFICIENT_FUNDS,
                    "BRL available: 50.00, required: 2000.00");

            assertThat(failedEvent.correlationId()).isEqualTo(correlationId);
            assertThat(failedEvent.orderId()).isEqualTo(orderId);
            assertThat(failedEvent.reason()).isEqualTo(FailureReason.INSUFFICIENT_FUNDS);

            // Order Service cancela a ordem
            OrderCancelledEvent cancelled = OrderCancelledEvent.of(
                    failedEvent.correlationId(),
                    failedEvent.orderId(),
                    FailureReason.INSUFFICIENT_FUNDS,
                    "Cancelled due to: " + failedEvent.detail());

            assertThat(cancelled.correlationId()).isEqualTo(correlationId);
            assertThat(cancelled.orderId()).isEqualTo(orderId);
            assertThat(cancelled.reason()).isEqualTo(FailureReason.INSUFFICIENT_FUNDS);
        }
    }

    // =========================================================================
    // Cenário 3 — Falha de Liquidação (Settlement Failed)
    // =========================================================================
    @Nested
    @DisplayName("Cenário 3: Falha de liquidação — evento crítico de incidente")
    class SettlementFailedPath {

        @Test
        @DisplayName("FundsSettlementFailedEvent deve preservar matchId e correlationId")
        void settlementFailed_matchIdAndCorrelationIdPreserved() {
            UUID correlationId = UUID.randomUUID();
            UUID matchId = UUID.randomUUID();

            FundsSettlementFailedEvent failedSettlement = FundsSettlementFailedEvent.of(
                    correlationId,
                    matchId,
                    FailureReason.SETTLEMENT_DB_ERROR,
                    "PostgreSQL constraint violation: negative balance");

            assertThat(failedSettlement.correlationId()).isEqualTo(correlationId);
            assertThat(failedSettlement.matchId()).isEqualTo(matchId);
            assertThat(failedSettlement.aggregateId()).isEqualTo(matchId.toString());
            assertThat(failedSettlement.reason()).isEqualTo(FailureReason.SETTLEMENT_DB_ERROR);
        }
    }

    // =========================================================================
    // Cenário 4 — Mensagem Duplicada (Idempotência)
    // =========================================================================
    @Nested
    @DisplayName("Cenário 4: RabbitMQ entrega duplicada — validação de idempotência")
    class DuplicateMessageScenario {

        @Test
        @DisplayName("dois events com mesmo correlationId mas eventIds distintos são identificáveis")
        void duplicateDelivery_identifiableByEventId() {
            UUID correlationId = UUID.randomUUID();
            UUID orderId = UUID.randomUUID();
            UUID walletId = UUID.randomUUID();

            FundsReservedEvent delivery1 = FundsReservedEvent.of(
                    correlationId, orderId, walletId, AssetType.BRL,
                    new BigDecimal("500.00"));
            FundsReservedEvent delivery2 = FundsReservedEvent.of(
                    correlationId, orderId, walletId, AssetType.BRL,
                    new BigDecimal("500.00"));

            // Mesmo correlationId (mesma Saga), mas eventIds distintos
            assertThat(delivery1.correlationId()).isEqualTo(delivery2.correlationId());
            assertThat(delivery1.eventId()).isNotEqualTo(delivery2.eventId());

            // O consumidor usa o eventId para detectar e descartar duplicatas
            assertThat(delivery1.eventId()).isNotNull();
            assertThat(delivery2.eventId()).isNotNull();
        }
    }
}
