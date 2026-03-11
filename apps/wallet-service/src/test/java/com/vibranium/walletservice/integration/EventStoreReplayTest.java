package com.vibranium.walletservice.integration;

import com.vibranium.walletservice.AbstractIntegrationTest;
import com.vibranium.walletservice.application.service.EventStoreService;
import com.vibranium.walletservice.domain.model.EventStoreEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Teste de integração para replay temporal do Event Store do wallet-service.
 *
 * <p>Simula o ciclo de vida de uma Wallet via eventos:</p>
 * <pre>
 *   T1: WalletCreatedEvent      → carteira criada
 *   T2: FundsReservedEvent      → saldo bloqueado
 *   T3: FundsSettledEvent       → trade liquidado
 *   T4: FundsReleasedEvent      → saldo liberado (compensação)
 * </pre>
 *
 * <p>Valida que o replay temporal retorna apenas os eventos até o instante informado,
 * permitindo reconstrução do estado em qualquer ponto no tempo.</p>
 */
@DisplayName("EventStore — Replay Temporal (wallet-service)")
class EventStoreReplayTest extends AbstractIntegrationTest {

    @Autowired
    private EventStoreService eventStoreService;

    @Test
    @DisplayName("Replay até T2 deve retornar apenas WalletCreatedEvent e FundsReservedEvent")
    void replayUntilT2_shouldReturnFirstTwoEvents() {
        // Arrange — insere 4 eventos simulando ciclo de vida da Wallet
        String aggregateId = UUID.randomUUID().toString();
        UUID correlationId = UUID.randomUUID();

        Instant t1 = Instant.parse("2026-01-01T10:00:00Z");
        Instant t2 = Instant.parse("2026-01-01T10:00:05Z");
        Instant t3 = Instant.parse("2026-01-01T10:00:10Z");
        Instant t4 = Instant.parse("2026-01-01T10:00:15Z");

        eventStoreService.append(UUID.randomUUID(), aggregateId, "Wallet",
                "WalletCreatedEvent",
                "{\"userId\":\"abc\"}", t1, correlationId, 1);

        eventStoreService.append(UUID.randomUUID(), aggregateId, "Wallet",
                "FundsReservedEvent",
                "{\"asset\":\"BRL\",\"amount\":\"100.00\"}", t2, correlationId, 1);

        eventStoreService.append(UUID.randomUUID(), aggregateId, "Wallet",
                "FundsSettledEvent",
                "{\"matchId\":\"match-1\"}", t3, correlationId, 1);

        eventStoreService.append(UUID.randomUUID(), aggregateId, "Wallet",
                "FundsReleasedEvent",
                "{\"asset\":\"BRL\",\"amount\":\"50.00\"}", t4, correlationId, 1);

        // Act — replay até T2 (inclusive)
        List<EventStoreEntry> eventsUntilT2 = eventStoreService.getEventsUntil(aggregateId, t2);

        // Assert — deve retornar apenas os 2 primeiros eventos
        assertThat(eventsUntilT2).hasSize(2);
        assertThat(eventsUntilT2.get(0).getEventType()).isEqualTo("WalletCreatedEvent");
        assertThat(eventsUntilT2.get(1).getEventType()).isEqualTo("FundsReservedEvent");
    }

    @Test
    @DisplayName("Replay até T4 deve retornar todos os 4 eventos")
    void replayUntilT4_shouldReturnAllFourEvents() {
        // Arrange
        String aggregateId = UUID.randomUUID().toString();
        UUID correlationId = UUID.randomUUID();

        Instant t1 = Instant.parse("2026-02-01T10:00:00Z");
        Instant t2 = Instant.parse("2026-02-01T10:00:05Z");
        Instant t3 = Instant.parse("2026-02-01T10:00:10Z");
        Instant t4 = Instant.parse("2026-02-01T10:00:15Z");

        eventStoreService.append(UUID.randomUUID(), aggregateId, "Wallet",
                "WalletCreatedEvent", "{}", t1, correlationId, 1);
        eventStoreService.append(UUID.randomUUID(), aggregateId, "Wallet",
                "FundsReservedEvent", "{}", t2, correlationId, 1);
        eventStoreService.append(UUID.randomUUID(), aggregateId, "Wallet",
                "FundsSettledEvent", "{}", t3, correlationId, 1);
        eventStoreService.append(UUID.randomUUID(), aggregateId, "Wallet",
                "FundsReleasedEvent", "{}", t4, correlationId, 1);

        // Act — replay até T4 (inclusive)
        List<EventStoreEntry> eventsUntilT4 = eventStoreService.getEventsUntil(aggregateId, t4);

        // Assert
        assertThat(eventsUntilT4).hasSize(4);
        assertThat(eventsUntilT4).extracting(EventStoreEntry::getEventType)
                .containsExactly("WalletCreatedEvent", "FundsReservedEvent",
                        "FundsSettledEvent", "FundsReleasedEvent");
    }

    @Test
    @DisplayName("Replay completo sem filtro temporal deve retornar todos os eventos")
    void fullReplay_shouldReturnAllEvents() {
        // Arrange
        String aggregateId = UUID.randomUUID().toString();
        UUID correlationId = UUID.randomUUID();
        Instant base = Instant.parse("2026-03-01T10:00:00Z");

        eventStoreService.append(UUID.randomUUID(), aggregateId, "Wallet",
                "WalletCreatedEvent", "{}", base, correlationId, 1);
        eventStoreService.append(UUID.randomUUID(), aggregateId, "Wallet",
                "FundsReservedEvent", "{}", base.plusSeconds(5), correlationId, 1);
        eventStoreService.append(UUID.randomUUID(), aggregateId, "Wallet",
                "FundsSettledEvent", "{}", base.plusSeconds(10), correlationId, 1);

        // Act
        List<EventStoreEntry> allEvents = eventStoreService.getEventsByAggregateId(aggregateId);

        // Assert
        assertThat(allEvents).hasSize(3);
        assertThat(allEvents).extracting(EventStoreEntry::getEventType)
                .containsExactly("WalletCreatedEvent", "FundsReservedEvent", "FundsSettledEvent");
    }

    @Test
    @DisplayName("Replay de agregado inexistente deve retornar lista vazia")
    void replayForNonExistentAggregate_shouldReturnEmptyList() {
        String nonExistentId = UUID.randomUUID().toString();

        List<EventStoreEntry> events = eventStoreService.getEventsByAggregateId(nonExistentId);

        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("Eventos de agregados diferentes não devem interferir entre si")
    void eventsFromDifferentAggregates_shouldBeIsolated() {
        // Arrange
        String walletA = UUID.randomUUID().toString();
        String walletB = UUID.randomUUID().toString();
        Instant now = Instant.now();

        eventStoreService.append(UUID.randomUUID(), walletA, "Wallet",
                "FundsReservedEvent", "{\"wallet\":\"A\"}", now, UUID.randomUUID(), 1);
        eventStoreService.append(UUID.randomUUID(), walletB, "Wallet",
                "FundsSettledEvent", "{\"wallet\":\"B\"}", now, UUID.randomUUID(), 1);
        eventStoreService.append(UUID.randomUUID(), walletA, "Wallet",
                "FundsReleasedEvent", "{\"wallet\":\"A\"}", now.plusSeconds(1), UUID.randomUUID(), 1);

        // Act
        List<EventStoreEntry> eventsA = eventStoreService.getEventsByAggregateId(walletA);
        List<EventStoreEntry> eventsB = eventStoreService.getEventsByAggregateId(walletB);

        // Assert
        assertThat(eventsA).hasSize(2);
        assertThat(eventsB).hasSize(1);
        assertThat(eventsA).extracting(EventStoreEntry::getEventType)
                .containsExactly("FundsReservedEvent", "FundsReleasedEvent");
        assertThat(eventsB).extracting(EventStoreEntry::getEventType)
                .containsExactly("FundsSettledEvent");
    }

    @Test
    @DisplayName("Sequence IDs devem ser monotonicamente crescentes entre eventos")
    void sequenceIds_shouldBeMonotonicallyIncreasing() {
        String aggregateId = UUID.randomUUID().toString();
        Instant base = Instant.now();

        for (int i = 0; i < 5; i++) {
            eventStoreService.append(UUID.randomUUID(), aggregateId, "Wallet",
                    "TestEvent_" + i, "{}", base.plusSeconds(i), UUID.randomUUID(), 1);
        }

        List<EventStoreEntry> events = eventStoreService.getEventsByAggregateId(aggregateId);

        assertThat(events).hasSize(5);
        for (int i = 1; i < events.size(); i++) {
            assertThat(events.get(i).getSequenceId())
                    .isGreaterThan(events.get(i - 1).getSequenceId());
        }
    }
}
