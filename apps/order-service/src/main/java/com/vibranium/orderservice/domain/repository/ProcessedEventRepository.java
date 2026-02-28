package com.vibranium.orderservice.domain.repository;

import com.vibranium.orderservice.domain.model.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Repositório de idempotência para eventos de domínio.
 *
 * <p>A operação principal é um simples {@code save(new ProcessedEvent(eventId))}.
 * Se o {@code eventId} já estiver na tabela, o banco lançará
 * {@link org.springframework.dao.DataIntegrityViolationException} por violação
 * de PK, sinalizando re-entrega ao consumer que deve ignorar o evento.</p>
 *
 * @see com.vibranium.orderservice.domain.model.ProcessedEvent
 */
@Repository
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {
}
