package com.vibranium.orderservice.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Entrada imutável no Event Store do order-service.
 *
 * <p>Representa um evento de domínio já ocorrido, gravado de forma append-only
 * na tabela {@code tb_event_store}. A imutabilidade é garantida por triggers
 * PostgreSQL que rejeitam UPDATE e DELETE.</p>
 *
 * <p>Cada entrada contém o payload completo do evento como JSONB, permitindo
 * replay temporal e queries ad-hoc para auditoria e compliance.</p>
 *
 * <p><strong>Não confundir com {@link OrderOutboxMessage}:</strong> o outbox é um
 * relay temporário para o broker; o Event Store é o registro permanente e imutável
 * de todos os eventos de domínio.</p>
 */
@Entity
@Table(name = "tb_event_store")
public class EventStoreEntry {

    /** Sequência monotônica global — ordering absoluto dos eventos. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "sequence_id", updatable = false, nullable = false)
    private Long sequenceId;

    /** UUID único do evento (DomainEvent.eventId()). */
    @Column(name = "event_id", updatable = false, nullable = false)
    private UUID eventId;

    /** ID do agregado que originou o evento (ex: orderId). */
    @Column(name = "aggregate_id", updatable = false, nullable = false)
    private String aggregateId;

    /** Tipo do agregado (ex: "Order"). */
    @Column(name = "aggregate_type", updatable = false, nullable = false, length = 100)
    private String aggregateType;

    /** Tipo do evento (ex: "OrderReceivedEvent"). */
    @Column(name = "event_type", updatable = false, nullable = false, length = 150)
    private String eventType;

    /** Payload completo do evento serializado como JSONB. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", updatable = false, nullable = false, columnDefinition = "jsonb")
    private String payload;

    /** Timestamp UTC de quando o fato ocorreu no domínio. */
    @Column(name = "occurred_on", updatable = false, nullable = false)
    private Instant occurredOn;

    /** ID de correlação da Saga (tracing distribuído). */
    @Column(name = "correlation_id", updatable = false, nullable = false)
    private UUID correlationId;

    /** Versão do schema do evento para evolução futura. */
    @Column(name = "schema_version", updatable = false, nullable = false)
    private int schemaVersion;

    /** Construtor protegido exigido pelo JPA. Não usar diretamente. */
    protected EventStoreEntry() {}

    /**
     * Cria uma entrada no Event Store.
     *
     * @param eventId       UUID único do evento.
     * @param aggregateId   ID do agregado (ex: orderId como String).
     * @param aggregateType tipo do agregado (ex: "Order").
     * @param eventType     tipo do evento (ex: "OrderReceivedEvent").
     * @param payload       JSON serializado do evento.
     * @param occurredOn    timestamp de quando o fato ocorreu.
     * @param correlationId ID de correlação da Saga.
     * @param schemaVersion versão do schema do evento.
     */
    public EventStoreEntry(UUID eventId, String aggregateId, String aggregateType,
                           String eventType, String payload, Instant occurredOn,
                           UUID correlationId, int schemaVersion) {
        this.eventId       = eventId;
        this.aggregateId   = aggregateId;
        this.aggregateType = aggregateType;
        this.eventType     = eventType;
        this.payload       = payload;
        this.occurredOn    = occurredOn;
        this.correlationId = correlationId;
        this.schemaVersion = schemaVersion;
    }

    public Long getSequenceId()     { return sequenceId; }
    public UUID getEventId()        { return eventId; }
    public String getAggregateId()  { return aggregateId; }
    public String getAggregateType() { return aggregateType; }
    public String getEventType()    { return eventType; }
    public String getPayload()      { return payload; }
    public Instant getOccurredOn()  { return occurredOn; }
    public UUID getCorrelationId()  { return correlationId; }
    public int getSchemaVersion()   { return schemaVersion; }
}
