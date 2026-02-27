package com.vibranium.walletservice.domain.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade do padrão Transactional Outbox.
 *
 * <p>Cada evento de domínio (ex: {@code FundsReservedEvent}) é gravado nesta
 * tabela dentro da MESMA transação que alterou o estado do {@link Wallet}.
 * Um scheduler separado (outbox relay) lê as mensagens {@code processed=false}
 * e as publica no RabbitMQ, garantindo entrega sem necessidade de 2-phase commit.</p>
 *
 * <p>A granularidade do índice parcial {@code WHERE processed = FALSE} garante
 * que o scheduler percorra apenas mensagens pendentes com custo O(pendentes),
 * não O(total).</p>
 */
@Entity
@Table(name = "outbox_message")
public class OutboxMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Nome qualificado do evento de domínio (ex: {@code FundsReservedEvent}).
     * Usado pelo relay para rotear para o exchange correto.
     */
    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    /**
     * ID do aggregate raiz relacionado (walletId ou matchId).
     * Gravado como String para não acoplar ao tipo UUID.
     */
    @Column(name = "aggregate_id", nullable = false, length = 36)
    private String aggregateId;

    /** Payload JSON completo do evento de domínio serializado. */
    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * {@code false} = pendente de publicação no broker.
     * {@code true}  = já publicado e confirmado pelo relay.
     */
    @Column(name = "processed", nullable = false)
    private boolean processed;

    /** Construtor protegido exigido pelo JPA. Não usar diretamente. */
    protected OutboxMessage() {}

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    /**
     * Cria uma nova mensagem de outbox pronta para ser gravada.
     *
     * @param eventType   Nome do tipo do evento (classe simples ou FQCN).
     * @param aggregateId ID do aggregate raiz como String.
     * @param payload     Payload JSON já serializado.
     * @return Nova instância não persistida com {@code processed=false}.
     */
    public static OutboxMessage create(String eventType, String aggregateId, String payload) {
        OutboxMessage msg = new OutboxMessage();
        msg.eventType = eventType;
        msg.aggregateId = aggregateId;
        msg.payload = payload;
        msg.processed = false;
        msg.createdAt = Instant.now();
        return msg;
    }

    // -------------------------------------------------------------------------
    // Behaviour
    // -------------------------------------------------------------------------

    /**
     * Marca a mensagem como processada. Deve ser chamado pelo relay após
     * confirmação de publicação no broker (ack do producer).
     */
    public void markAsProcessed() {
        this.processed = true;
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public UUID getId() { return id; }

    public String getEventType() { return eventType; }

    public String getAggregateId() { return aggregateId; }

    public String getPayload() { return payload; }

    public Instant getCreatedAt() { return createdAt; }

    public boolean isProcessed() { return processed; }
}
