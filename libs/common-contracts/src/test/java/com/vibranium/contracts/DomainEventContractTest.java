package com.vibranium.contracts;

import com.vibranium.contracts.events.DomainEvent;
import com.vibranium.contracts.events.order.*;
import com.vibranium.contracts.events.wallet.*;
import com.vibranium.contracts.enums.AssetType;
import com.vibranium.contracts.enums.FailureReason;
import com.vibranium.contracts.enums.OrderType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Teste de contrato da interface {@link DomainEvent}.
 *
 * Garante que TODOS os eventos do domínio atendem ao contrato mínimo
 * de rastreabilidade exigido pela Saga e pelo Event Store.
 */
@DisplayName("DomainEvent Contract — Metadados de Rastreabilidade")
class DomainEventContractTest {

    // -------------------------------------------------------------------------
    // Fixtures reutilizáveis
    // -------------------------------------------------------------------------
    private static final UUID CORRELATION = UUID.randomUUID();
    private static final UUID ORDER_ID    = UUID.randomUUID();
    private static final UUID WALLET_ID   = UUID.randomUUID();
    private static final UUID USER_ID     = UUID.randomUUID();
    private static final UUID MATCH_ID    = UUID.randomUUID();
    private static final BigDecimal PRICE  = new BigDecimal("150.50");
    private static final BigDecimal AMOUNT = new BigDecimal("10.00");

    // -------------------------------------------------------------------------
    // Helper: lista de todos os eventos para testar o contrato de forma genérica
    // -------------------------------------------------------------------------
    private java.util.List<DomainEvent> allEvents() {
        return java.util.List.of(
            WalletCreatedEvent.of(CORRELATION, WALLET_ID, USER_ID),
            FundsReservedEvent.of(CORRELATION, ORDER_ID, WALLET_ID, AssetType.BRL, PRICE),
            FundsReservationFailedEvent.of(CORRELATION, ORDER_ID, WALLET_ID.toString(),
                    FailureReason.INSUFFICIENT_FUNDS, "saldo insuficiente"),
            FundsSettledEvent.of(CORRELATION, MATCH_ID, ORDER_ID, UUID.randomUUID(),
                    WALLET_ID, UUID.randomUUID(), PRICE, AMOUNT),
            FundsSettlementFailedEvent.of(CORRELATION, MATCH_ID,
                    FailureReason.SETTLEMENT_DB_ERROR, "db timeout"),
            OrderReceivedEvent.of(CORRELATION, ORDER_ID, USER_ID, WALLET_ID,
                    OrderType.BUY, PRICE, AMOUNT),
            OrderAddedToBookEvent.of(CORRELATION, ORDER_ID, OrderType.BUY, PRICE, AMOUNT),
            MatchExecutedEvent.of(CORRELATION, ORDER_ID, UUID.randomUUID(),
                    USER_ID, UUID.randomUUID(), WALLET_ID, UUID.randomUUID(), PRICE, AMOUNT),
            OrderPartiallyFilledEvent.of(CORRELATION, ORDER_ID, MATCH_ID,
                    new BigDecimal("4.00"), new BigDecimal("6.00")),
            OrderFilledEvent.of(CORRELATION, ORDER_ID, AMOUNT, PRICE),
            OrderCancelledEvent.of(CORRELATION, ORDER_ID,
                    FailureReason.INSUFFICIENT_FUNDS, "saldo insuficiente")
        );
    }

    @Nested
    @DisplayName("Contrato: campos obrigatórios presentes em todos os eventos")
    class MandatoryFieldsContract {

        @Test
        @DisplayName("eventId deve ser não-nulo e do tipo UUID")
        void eventId_shouldBeNonNull() {
            allEvents().forEach(event ->
                assertThat(event.eventId())
                    .as("eventId de %s não deve ser nulo", event.eventType())
                    .isNotNull()
            );
        }

        @Test
        @DisplayName("correlationId deve ser não-nulo (rastreabilidade Saga)")
        void correlationId_shouldBeNonNull() {
            allEvents().forEach(event ->
                assertThat(event.correlationId())
                    .as("correlationId de %s não deve ser nulo", event.eventType())
                    .isNotNull()
                    .isEqualTo(CORRELATION)
            );
        }

        @Test
        @DisplayName("aggregateId deve ser não-nulo e não-vazio")
        void aggregateId_shouldBeNonBlank() {
            allEvents().forEach(event ->
                assertThat(event.aggregateId())
                    .as("aggregateId de %s não deve ser nulo/vazio", event.eventType())
                    .isNotBlank()
            );
        }

        @Test
        @DisplayName("occurredOn deve ser não-nulo e no passado ou presente")
        void occurredOn_shouldBeRecentTimestamp() {
            Instant before = Instant.now().minusSeconds(5);
            allEvents().forEach(event ->
                assertThat(event.occurredOn())
                    .as("occurredOn de %s deve ser timestamp recente", event.eventType())
                    .isNotNull()
                    .isAfter(before)
            );
        }
    }

    @Nested
    @DisplayName("Contrato: unicidade de eventId")
    class IdempotencyContract {

        @Test
        @DisplayName("Duas instâncias do mesmo tipo de evento devem ter eventIds distintos")
        void twoInstances_shouldHaveDifferentEventIds() {
            FundsReservedEvent e1 = FundsReservedEvent.of(CORRELATION, ORDER_ID,
                    WALLET_ID, AssetType.BRL, PRICE);
            FundsReservedEvent e2 = FundsReservedEvent.of(CORRELATION, ORDER_ID,
                    WALLET_ID, AssetType.BRL, PRICE);

            assertThat(e1.eventId()).isNotEqualTo(e2.eventId());
        }

        @Test
        @DisplayName("Todos os eventos gerados juntos devem ter eventIds únicos")
        void allEvents_shouldHaveUniqueEventIds() {
            var ids = allEvents().stream().map(DomainEvent::eventId).toList();
            assertThat(ids).doesNotHaveDuplicates();
        }
    }

    @Nested
    @DisplayName("Contrato: eventType padrão")
    class EventTypeContract {

        @Test
        @DisplayName("eventType deve retornar o nome simples da classe")
        void eventType_shouldReturnSimpleClassName() {
            FundsReservedEvent event = FundsReservedEvent.of(
                    CORRELATION, ORDER_ID, WALLET_ID, AssetType.BRL, PRICE);

            assertThat(event.eventType()).isEqualTo("FundsReservedEvent");
        }
    }
}
