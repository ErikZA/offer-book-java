package com.vibranium.walletservice.unit;

import com.vibranium.walletservice.infrastructure.outbox.EventRoute;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.*;

/**
 * [RED] Testes unitários para o enum {@link EventRoute}.
 *
 * <p>Valida o mapeamento de {@code eventType} (String) para
 * exchange + routing-key corretos. Falha até que {@code EventRoute} seja criado.</p>
 */
@DisplayName("[RED] EventRoute - Roteamento dinâmico de eventos do Outbox")
class EventRouteTest {

    // -------------------------------------------------------------------------
    // Mapeamento correto para cada tipo de evento
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "{0} → exchange={1}, routingKey={2}")
    @CsvSource({
        "FundsReservedEvent,          vibranium.events, wallet.events.funds-reserved",
        "FundsReservationFailedEvent, vibranium.events, wallet.events.funds-reservation-failed",
        "FundsSettledEvent,           vibranium.events, wallet.events.funds-settled",
        "WalletCreatedEvent,          vibranium.events, wallet.events.wallet-created"
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
    // Enum deve ter exatamente os 4 tipos esperados
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("EventRoute deve conter exatamente 4 rotas")
    void shouldHaveExactlyFourRoutes() {
        assertThat(EventRoute.values()).hasSize(4);
    }
}
