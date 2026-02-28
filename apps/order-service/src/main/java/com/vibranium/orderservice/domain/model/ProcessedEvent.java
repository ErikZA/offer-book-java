package com.vibranium.orderservice.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Registro de idempotência para eventos de domínio consumidos pelo order-service.
 *
 * <p>Impede que o RabbitMQ at-least-once delivery processe o mesmo
 * {@link com.vibranium.contracts.events.DomainEvent#eventId()} mais de uma vez.
 * Antes de executar qualquer lógica de negócio, o consumer persiste esta entidade;
 * se o {@code event_id} já existir, a {@code DataIntegrityViolationException} (PK duplicada)
 * sinaliza que o evento é uma re-entrega e deve ser descartado silenciosamente.</p>
 *
 * <p>Mesma estratégia utilizada em {@code idempotency_key} do wallet-service.</p>
 *
 * @see com.vibranium.orderservice.domain.repository.ProcessedEventRepository
 */
@Entity
@Table(name = "tb_processed_events")
public class ProcessedEvent {

    /**
     * UUID imutável do evento, propagado diretamente do campo {@code eventId}
     * do {@link com.vibranium.contracts.events.DomainEvent}.
     */
    @Id
    @Column(name = "event_id", updatable = false, nullable = false)
    private UUID eventId;

    /** Timestamp UTC de quando o evento foi aceito pela primeira vez. */
    @Column(name = "processed_at", updatable = false, nullable = false)
    private Instant processedAt;

    /** Construtor exigido pelo JPA. */
    protected ProcessedEvent() {}

    /**
     * Cria um registro de evento processado.
     *
     * @param eventId UUID único do evento (imutável).
     */
    public ProcessedEvent(UUID eventId) {
        this.eventId = eventId;
    }

    @PrePersist
    void onPersist() {
        this.processedAt = Instant.now();
    }

    public UUID    getEventId()     { return eventId; }
    public Instant getProcessedAt() { return processedAt; }
}
