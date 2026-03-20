package com.vibranium.contracts;

import com.vibranium.contracts.messaging.EventRoute;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("EventRoute contract")
class EventRouteContractTest {

    @ParameterizedTest(name = "{0} -> {2}")
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
    void shouldResolveAllKnownEvents(String eventType, String exchange, String routingKey) {
        EventRoute route = EventRoute.fromEventType(eventType);

        assertThat(route.getExchange()).isEqualTo(exchange);
        assertThat(route.getRoutingKey()).isEqualTo(routingKey);
    }

    @Test
    void shouldRejectUnknownEventType() {
        assertThatThrownBy(() -> EventRoute.fromEventType("UnknownEvent"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown event type: UnknownEvent");
    }

    @Test
    void shouldContainAllMappedRoutes() {
        assertThat(EventRoute.values()).hasSize(13);
    }
}
