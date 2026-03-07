package com.vibranium.orderservice.integration;

import com.vibranium.orderservice.domain.model.EventStoreEntry;
import com.vibranium.orderservice.domain.repository.EventStoreRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

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
@DisplayName("EventStore — Append-Only Integrity (AT-14)")
class EventStoreAppendOnlyTest extends AbstractIntegrationTest {

    @Autowired
    private EventStoreRepository eventStoreRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
                    "Order",
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
                "Order",
                "OrderReceivedEvent",
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
                "Order",
                "OrderReceivedEvent",
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
    @DisplayName("Inserir evento com event_id duplicado deve lançar DataIntegrityViolationException")
    void duplicateEventIdShouldBeRejected() {
        // Arrange
        UUID eventId = UUID.randomUUID();
        EventStoreEntry first = new EventStoreEntry(
                eventId,
                UUID.randomUUID().toString(),
                "Order",
                "OrderReceivedEvent",
                "{}",
                Instant.now(),
                UUID.randomUUID(),
                1
        );
        eventStoreRepository.saveAndFlush(first);

        // Act + Assert — segunda inserção com mesmo eventId deve falhar
        EventStoreEntry duplicate = new EventStoreEntry(
                eventId,
                UUID.randomUUID().toString(),
                "Order",
                "DuplicateEvent",
                "{}",
                Instant.now(),
                UUID.randomUUID(),
                1
        );
        assertThatThrownBy(() -> eventStoreRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
