package com.vibranium.orderservice.domain.repository;

import com.vibranium.orderservice.domain.model.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

/**
 * Repositório de idempotência para eventos de domínio.
 *
 * <p>A operação principal é um simples {@code save(new ProcessedEvent(eventId))}.
 * Se o {@code eventId} já estiver na tabela, o banco lançará
 * {@link org.springframework.dao.DataIntegrityViolationException} por violação
 * de PK, sinalizando re-entrega ao consumer que deve ignorar o evento.</p>
 *
 * <p>O método {@link #deleteByProcessedAtBefore(Instant)} é utilizado pelo
 * {@link com.vibranium.orderservice.application.service.IdempotencyKeyCleanupJob}
 * para aplicar a política de retenção de 7 dias.</p>
 *
 * @see com.vibranium.orderservice.domain.model.ProcessedEvent
 */
@Repository
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {

    /**
     * Remove todos os registros cujo {@code processed_at} é anterior ao instante informado.
     * Usado pelo job de limpeza para aplicar a retenção de 7 dias.
     *
     * @param cutoff Instante de corte — registros anteriores a este serão deletados.
     */
    @Modifying
    @Query("DELETE FROM ProcessedEvent e WHERE e.processedAt < :cutoff")
    void deleteByProcessedAtBefore(@Param("cutoff") Instant cutoff);
}
