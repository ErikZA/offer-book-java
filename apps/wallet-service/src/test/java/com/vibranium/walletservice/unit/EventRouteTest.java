package com.vibranium.walletservice.unit;

import com.vibranium.contracts.messaging.EventRoute;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.*;

/**
 * Testes unitários para o contrato comum {@link EventRoute}.
 *
 * <p>Valida o mapeamento de {@code eventType} (String) para
 * exchange + routing-key corretos para os eventos de wallet-service e order-service.</p>
 */
@DisplayName("EventRoute - Roteamento dinâmico de eventos do Outbox")
class EventRouteTest {

    // -------------------------------------------------------------------------
    // Mapeamento correto para cada tipo de evento
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "{0} → exchange={1}, routingKey={2}")
    @CsvSource({
        "FundsReservedEvent,          vibranium.events, wallet.events.funds-reserved",
        "FundsReservationFailedEvent, vibranium.events, wallet.events.funds-reservation-failed",
        "FundsSettledEvent,           vibranium.events, wallet.events.funds-settled",
        "WalletCreatedEvent,          vibranium.events, wallet.events.wallet-created",
        "FundsReleasedEvent,          vibranium.events, wallet.events.funds-released",
        "FundsReleaseFailedEvent,     vibranium.events, wallet.events.funds-release-failed",
        "FundsSettlementFailedEvent,  vibranium.events, wallet.events.funds-settlement-failed",
        "OrderReceivedEvent,          vibranium.events, order.events.order-received",
        "OrderAddedToBookEvent,       vibranium.events, order.events.order-added-to-book",
        "MatchExecutedEvent,          vibranium.events, order.events.match-executed",
        "OrderFilledEvent,            vibranium.events, order.events.order-filled",
        "OrderPartiallyFilledEvent,   vibranium.events, order.events.order-partially-filled",
        "OrderCancelledEvent,         vibranium.events, order.events.order-cancelled"
    })
    @DisplayName("fromEventType deve retornar exchange e routing-key corretos")
    void shouldResolveCorrectRouteForEventType(String eventType, String exchange, String routingKey) {
        EventRoute route = EventRoute.fromEventType(eventType.trim());

        assertThat(route.getExchange()).isEqualTo(exchange.trim());
        assertThat(route.getRoutingKey()).isEqualTo(routingKey.trim());
    }

    // -------------------------------------------------------------------------
    // Tipo desconhecido deve lançar exceção
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("fromEventType deve lançar IllegalArgumentException para tipo desconhecido")
    void shouldThrowForUnknownEventType() {
        assertThatThrownBy(() -> EventRoute.fromEventType("UnknownEvent"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown event type: UnknownEvent");
    }

    // -------------------------------------------------------------------------
    // Constantes de exchange — todos os eventos usam a mesma exchange
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Todos os eventos devem usar a exchange vibranium.events")
    void allEventsShouldUseVibraniumEventsExchange() {
        for (EventRoute route : EventRoute.values()) {
            assertThat(route.getExchange())
                    .as("Exchange do route %s", route.name())
                    .isEqualTo("vibranium.events");
        }
    }

    // -------------------------------------------------------------------------
    // Routing-keys não devem ser nulas ou vazias
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Todos os EventRoute devem ter routing-key não nula e não vazia")
    void allRoutesShouldHaveNonBlankRoutingKey() {
        for (EventRoute route : EventRoute.values()) {
            assertThat(route.getRoutingKey())
                    .as("RoutingKey do route %s", route.name())
                    .isNotBlank();
        }
    }

    // -------------------------------------------------------------------------
    // Enum deve ter exatamente os eventos de wallet + order
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("EventRoute deve conter exatamente 13 rotas")
    void shouldHaveExactlyThirteenRoutes() {
        assertThat(EventRoute.values()).hasSize(13);
    }
}
