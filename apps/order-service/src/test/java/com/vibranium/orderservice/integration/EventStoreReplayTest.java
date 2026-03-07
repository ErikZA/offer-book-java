package com.vibranium.orderservice.integration;

import com.vibranium.orderservice.application.service.EventStoreService;
import com.vibranium.orderservice.domain.model.EventStoreEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Teste de integração para replay temporal do Event Store.
 *
 * <p>Simula o ciclo de vida de uma Ordem via eventos:</p>
 * <pre>
 *   T1: OrderReceivedEvent   → estado PENDING
 *   T2: FundsReservedEvent   → estado OPEN
 *   T3: MatchExecutedEvent   → estado PARTIAL ou preparando match
 *   T4: OrderFilledEvent     → estado FILLED
 * </pre>
 *
 * <p>Valida que o replay temporal retorna apenas os eventos até o instante informado,
 * permitindo reconstrução do estado em qualquer ponto no tempo.</p>
 */
@DisplayName("EventStore — Replay Temporal (AT-14)")
class EventStoreReplayTest extends AbstractIntegrationTest {

    @Autowired
    private EventStoreService eventStoreService;

    @Test
    @DisplayName("Replay até T2 deve retornar apenas OrderReceivedEvent e FundsReservedEvent")
    void replayUntilT2_shouldReturnFirstTwoEvents() {
        // Arrange — insere 4 eventos simulando ciclo de vida da Ordem
        String aggregateId = UUID.randomUUID().toString();
        UUID correlationId = UUID.randomUUID();

        Instant t1 = Instant.parse("2026-01-01T10:00:00Z");
        Instant t2 = Instant.parse("2026-01-01T10:00:05Z");
        Instant t3 = Instant.parse("2026-01-01T10:00:10Z");
        Instant t4 = Instant.parse("2026-01-01T10:00:15Z");

        eventStoreService.append(UUID.randomUUID(), aggregateId, "Order",
                "OrderReceivedEvent",
                "{\"status\":\"PENDING\"}", t1, correlationId, 1);

        eventStoreService.append(UUID.randomUUID(), aggregateId, "Order",
                "FundsReservedEvent",
                "{\"status\":\"OPEN\"}", t2, correlationId, 1);

        eventStoreService.append(UUID.randomUUID(), aggregateId, "Order",
                "MatchExecutedEvent",
                "{\"matchedAmount\":\"1.5\"}", t3, correlationId, 1);

        eventStoreService.append(UUID.randomUUID(), aggregateId, "Order",
                "OrderFilledEvent",
                "{\"status\":\"FILLED\"}", t4, correlationId, 1);

        // Act — replay até T2 (inclusive)
        List<EventStoreEntry> eventsUntilT2 = eventStoreService.getEventsUntil(aggregateId, t2);

        // Assert — deve retornar apenas os 2 primeiros eventos (estado OPEN)
        assertThat(eventsUntilT2).hasSize(2);
        assertThat(eventsUntilT2.get(0).getEventType()).isEqualTo("OrderReceivedEvent");
        assertThat(eventsUntilT2.get(1).getEventType()).isEqualTo("FundsReservedEvent");

        // O último evento no replay até T2 é o FundsReservedEvent, indicando estado OPEN
        assertThat(eventsUntilT2.get(1).getPayload()).contains("OPEN");
    }

    @Test
    @DisplayName("Replay até T4 deve retornar todos os 4 eventos — estado FILLED")
    void replayUntilT4_shouldReturnAllFourEvents() {
        // Arrange
        String aggregateId = UUID.randomUUID().toString();
        UUID correlationId = UUID.randomUUID();

        Instant t1 = Instant.parse("2026-02-01T10:00:00Z");
        Instant t2 = Instant.parse("2026-02-01T10:00:05Z");
        Instant t3 = Instant.parse("2026-02-01T10:00:10Z");
        Instant t4 = Instant.parse("2026-02-01T10:00:15Z");

        eventStoreService.append(UUID.randomUUID(), aggregateId, "Order",
                "OrderReceivedEvent",
                "{\"status\":\"PENDING\"}", t1, correlationId, 1);

        eventStoreService.append(UUID.randomUUID(), aggregateId, "Order",
                "FundsReservedEvent",
                "{\"status\":\"OPEN\"}", t2, correlationId, 1);

        eventStoreService.append(UUID.randomUUID(), aggregateId, "Order",
                "MatchExecutedEvent",
                "{\"matchedAmount\":\"2.0\"}", t3, correlationId, 1);

        eventStoreService.append(UUID.randomUUID(), aggregateId, "Order",
                "OrderFilledEvent",
                "{\"status\":\"FILLED\"}", t4, correlationId, 1);

        // Act — replay até T4 (inclusive)
        List<EventStoreEntry> eventsUntilT4 = eventStoreService.getEventsUntil(aggregateId, t4);

        // Assert — deve retornar todos os 4 eventos
        assertThat(eventsUntilT4).hasSize(4);
        assertThat(eventsUntilT4.get(0).getEventType()).isEqualTo("OrderReceivedEvent");
        assertThat(eventsUntilT4.get(1).getEventType()).isEqualTo("FundsReservedEvent");
        assertThat(eventsUntilT4.get(2).getEventType()).isEqualTo("MatchExecutedEvent");
        assertThat(eventsUntilT4.get(3).getEventType()).isEqualTo("OrderFilledEvent");

        // O último evento indica estado FILLED
        assertThat(eventsUntilT4.get(3).getPayload()).contains("FILLED");
    }

    @Test
    @DisplayName("Replay completo sem filtro temporal deve retornar todos os eventos")
    void fullReplay_shouldReturnAllEvents() {
        // Arrange
        String aggregateId = UUID.randomUUID().toString();
        UUID correlationId = UUID.randomUUID();
        Instant base = Instant.parse("2026-03-01T10:00:00Z");

        eventStoreService.append(UUID.randomUUID(), aggregateId, "Order",
                "OrderReceivedEvent", "{}", base, correlationId, 1);
        eventStoreService.append(UUID.randomUUID(), aggregateId, "Order",
                "FundsReservedEvent", "{}", base.plusSeconds(5), correlationId, 1);
        eventStoreService.append(UUID.randomUUID(), aggregateId, "Order",
                "MatchExecutedEvent", "{}", base.plusSeconds(10), correlationId, 1);
        eventStoreService.append(UUID.randomUUID(), aggregateId, "Order",
                "OrderFilledEvent", "{}", base.plusSeconds(15), correlationId, 1);

        // Act — replay completo
        List<EventStoreEntry> allEvents = eventStoreService.getEventsByAggregateId(aggregateId);

        // Assert
        assertThat(allEvents).hasSize(4);
        assertThat(allEvents).extracting(EventStoreEntry::getEventType)
                .containsExactly("OrderReceivedEvent", "FundsReservedEvent",
                        "MatchExecutedEvent", "OrderFilledEvent");
    }

    @Test
    @DisplayName("Replay de agregado inexistente deve retornar lista vazia")
    void replayForNonExistentAggregate_shouldReturnEmptyList() {
        String nonExistentId = UUID.randomUUID().toString();

        List<EventStoreEntry> events = eventStoreService.getEventsByAggregateId(nonExistentId);

        assertThat(events).isEmpty();
    }
}
