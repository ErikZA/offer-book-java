package com.vibranium.walletservice.integration;

import com.vibranium.walletservice.AbstractIntegrationTest;
import com.vibranium.walletservice.domain.model.EventStoreEntry;
import com.vibranium.walletservice.domain.repository.EventStoreRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Teste de integração que valida a propriedade append-only do Event Store.
 *
 * <p>Cenários validados:</p>
 * <ol>
 *   <li>Inserir 10 eventos e confirmar que SELECT retorna todos, ordenados.</li>
 *   <li>Tentar UPDATE via SQL nativo → exceção (trigger {@code trg_event_store_deny_update}).</li>
 *   <li>Tentar DELETE via SQL nativo → exceção (trigger {@code trg_event_store_deny_delete}).</li>
 *   <li>Constraint UNIQUE em {@code event_id} rejeita duplicatas.</li>
 * </ol>
 */
@DisplayName("EventStore — Append-Only Integrity (wallet-service)")
class EventStoreAppendOnlyTest extends AbstractIntegrationTest {

    @Autowired
    private EventStoreRepository eventStoreRepository;

    @Test
    @DisplayName("Deve inserir 10 eventos e retornar todos ordenados por sequence_id")
    void shouldInsert10EventsAndReturnAllOrdered() {
        // Arrange
        String aggregateId = UUID.randomUUID().toString();
        Instant baseTime = Instant.parse("2026-01-01T10:00:00Z");

        for (int i = 0; i < 10; i++) {
            eventStoreRepository.save(new EventStoreEntry(
                    UUID.randomUUID(),
                    aggregateId,
                    "Wallet",
                    "TestEvent_" + i,
                    "{\"index\":" + i + "}",
                    baseTime.plusSeconds(i),
                    UUID.randomUUID(),
                    1
            ));
        }

        // Act
        List<EventStoreEntry> events =
                eventStoreRepository.findByAggregateIdOrderBySequenceIdAsc(aggregateId);

        // Assert
        assertThat(events).hasSize(10);

        // Verifica ordenação crescente por sequence_id
        for (int i = 1; i < events.size(); i++) {
            assertThat(events.get(i).getSequenceId())
                    .isGreaterThan(events.get(i - 1).getSequenceId());
        }

        // Verifica que os tipos estão corretos e em ordem
        for (int i = 0; i < events.size(); i++) {
            assertThat(events.get(i).getEventType()).isEqualTo("TestEvent_" + i);
        }
    }

    @Test
    @DisplayName("UPDATE deve ser rejeitado pelo trigger trg_event_store_deny_update")
    void updateShouldBeRejectedByTrigger() {
        // Arrange — insere um evento válido
        UUID eventId = UUID.randomUUID();
        eventStoreRepository.save(new EventStoreEntry(
                eventId,
                UUID.randomUUID().toString(),
                "Wallet",
                "FundsReservedEvent",
                "{\"test\":true}",
                Instant.now(),
                UUID.randomUUID(),
                1
        ));

        // Act + Assert — UPDATE via SQL nativo deve ser rejeitado pelo trigger
        assertThatThrownBy(() ->
                jdbcTemplate.update(
                        "UPDATE tb_event_store SET event_type = 'HACKED' WHERE event_id = ?",
                        eventId
                )
        ).hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("DELETE deve ser rejeitado pelo trigger trg_event_store_deny_delete")
    void deleteShouldBeRejectedByTrigger() {
        // Arrange — insere um evento válido
        UUID eventId = UUID.randomUUID();
        eventStoreRepository.save(new EventStoreEntry(
                eventId,
                UUID.randomUUID().toString(),
                "Wallet",
                "FundsSettledEvent",
                "{\"test\":true}",
                Instant.now(),
                UUID.randomUUID(),
                1
        ));

        // Act + Assert — DELETE via SQL nativo deve ser rejeitado pelo trigger
        assertThatThrownBy(() ->
                jdbcTemplate.update(
                        "DELETE FROM tb_event_store WHERE event_id = ?",
                        eventId
                )
        ).hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("Constraint UNIQUE em event_id deve rejeitar duplicatas")
    void duplicateEventId_shouldBeRejected() {
        UUID eventId = UUID.randomUUID();
        String aggregateId = UUID.randomUUID().toString();

        // Primeiro insert — sucesso
        eventStoreRepository.saveAndFlush(new EventStoreEntry(
                eventId, aggregateId, "Wallet",
                "FundsReservedEvent", "{}", Instant.now(),
                UUID.randomUUID(), 1
        ));

        // Segundo insert com mesmo eventId — violação de UNIQUE constraint
        assertThatThrownBy(() ->
                eventStoreRepository.saveAndFlush(new EventStoreEntry(
                        eventId, aggregateId, "Wallet",
                        "FundsReservedEvent", "{}", Instant.now(),
                        UUID.randomUUID(), 1
                ))
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Campos imutáveis devem ser persistidos corretamente")
    void allFields_shouldBePersistedCorrectly() {
        // Arrange
        UUID eventId = UUID.randomUUID();
        String aggregateId = UUID.randomUUID().toString();
        UUID correlationId = UUID.randomUUID();
        Instant occurredOn = Instant.parse("2026-03-01T12:00:00Z");
        String payload = "{\"walletId\":\"" + aggregateId + "\",\"amount\":\"500.00\"}";

        eventStoreRepository.save(new EventStoreEntry(
                eventId, aggregateId, "Wallet",
                "FundsReservedEvent", payload, occurredOn,
                correlationId, 1
        ));

        // Act
        List<EventStoreEntry> events =
                eventStoreRepository.findByAggregateIdOrderBySequenceIdAsc(aggregateId);

        // Assert
        assertThat(events).hasSize(1);
        EventStoreEntry entry = events.get(0);
        assertThat(entry.getSequenceId()).isNotNull().isPositive();
        assertThat(entry.getEventId()).isEqualTo(eventId);
        assertThat(entry.getAggregateId()).isEqualTo(aggregateId);
        assertThat(entry.getAggregateType()).isEqualTo("Wallet");
        assertThat(entry.getEventType()).isEqualTo("FundsReservedEvent");
        // JSONB normaliza a ordem das chaves alfabeticamente
        assertThat(entry.getPayload()).contains(aggregateId).contains("500.00");
        assertThat(entry.getOccurredOn()).isEqualTo(occurredOn);
        assertThat(entry.getCorrelationId()).isEqualTo(correlationId);
        assertThat(entry.getSchemaVersion()).isEqualTo(1);
    }
}
