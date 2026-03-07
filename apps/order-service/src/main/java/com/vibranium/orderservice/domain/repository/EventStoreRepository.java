package com.vibranium.orderservice.domain.repository;

import com.vibranium.orderservice.domain.model.EventStoreEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Repositório Spring Data JPA para o Event Store imutável ({@code tb_event_store}).
 *
 * <p>Provê queries de leitura para replay de eventos e auditoria.
 * A escrita é feita exclusivamente via {@code save()} — a tabela é append-only,
 * protegida por triggers PostgreSQL contra UPDATE e DELETE.</p>
 *
 * <p><strong>Nota:</strong> métodos {@code deleteById}, {@code deleteAll}, etc. herdados
 * de {@link JpaRepository} lançarão exceção no banco devido ao trigger
 * {@code trg_event_store_deny_delete}. Isso é intencional — o Event Store
 * nunca deve ter dados expurgados.</p>
 */
@Repository
public interface EventStoreRepository extends JpaRepository<EventStoreEntry, Long> {

    /**
     * Retorna todos os eventos de um agregado, ordenados pela sequência de inserção.
     * Usado para replay completo e auditoria.
     *
     * @param aggregateId ID do agregado (ex: orderId como String).
     * @return Lista ordenada de eventos do agregado.
     */
    List<EventStoreEntry> findByAggregateIdOrderBySequenceIdAsc(String aggregateId);

    /**
     * Retorna eventos de um agregado até um instante específico (inclusive).
     * Usado para reconstrução temporal: "estado da Ordem X no instante T".
     *
     * @param aggregateId ID do agregado.
     * @param until       timestamp limite (inclusive) para o replay.
     * @return Lista ordenada de eventos do agregado até o instante informado.
     */
    List<EventStoreEntry> findByAggregateIdAndOccurredOnLessThanEqualOrderBySequenceIdAsc(
            String aggregateId, Instant until);

    /**
     * Retorna todos os eventos de um tipo de agregado, ordenados pela sequência.
     * Usado para auditoria global por tipo.
     *
     * @param aggregateType tipo do agregado (ex: "Order").
     * @return Lista ordenada de eventos.
     */
    List<EventStoreEntry> findByAggregateTypeOrderBySequenceIdAsc(String aggregateType);
}
