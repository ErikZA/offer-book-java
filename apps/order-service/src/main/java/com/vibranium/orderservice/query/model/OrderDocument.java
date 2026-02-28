package com.vibranium.orderservice.query.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Documento MongoDB do Read Model de Ordens (Query Side — CQRS).
 *
 * <p>Representa o estado consolidado de uma Ordem e seu histórico completo
 * de eventos de domínio para auditoria de traders. É populado de forma assíncrona
 * por {@link com.vibranium.orderservice.query.consumer.OrderEventProjectionConsumer}
 * via eventos RabbitMQ — eventual consistency intencional (design CQRS).</p>
 *
 * <p><strong>Índices obrigatórios:</strong></p>
 * <ul>
 *   <li>{@code userId + createdAt DESC} — suporta a query paginada
 *       {@code findByUserIdOrderByCreatedAtDesc} em O(log n) mesmo com milhões de documentos.</li>
 * </ul>
 *
 * <p>{@code orderId} é o {@code @Id} do documento, garantindo unicidade e lookup O(1).</p>
 *
 * <p><strong>Decisão Debezium vs RabbitMQ Router:</strong> O padrão ideal seria CDC via
 * Debezium capturando o WAL do PostgreSQL (Outbox Pattern), eliminando o risco de evento
 * perdido entre a escrita no banco e a publicação na fila. Para este sprint, usamos
 * RabbitMQ consumers diretos por consistência com o stack existente; adoção do Debezium
 * é tech debt registrado para sprint futuro.</p>
 */
@Document(collection = "orders")
@CompoundIndex(name = "idx_userId_createdAt", def = "{'userId': 1, 'createdAt': -1}")
public class OrderDocument {

    /** Chave primária: UUID da ordem (mesmo ID usado no PostgreSQL). Imutável após criação. */
    @Id
    private String orderId;

    /** Keycloak ID do usuário — base do filtro de segurança (JWT {@code sub}). */
    private String userId;

    /** BUY ou SELL. */
    private String orderType;

    /**
     * Estado atual da máquina de estados da ordem:
     * {@code PENDING → OPEN → PARTIAL → FILLED | CANCELLED}.
     */
    private String status;

    /** Preço limite em BRL. */
    private BigDecimal price;

    /** Quantidade total de VIBRANIUM na ordem original. */
    private BigDecimal originalQty;

    /** Quantidade ainda não executada (decrementada em matches parciais). */
    private BigDecimal remainingQty;

    /** Timestamp de criação (de {@code OrderReceivedEvent.occurredOn()}). */
    private Instant createdAt;

    /** Timestamp da última modificação pelo consumidor de projeção. */
    private Instant updatedAt;

    /**
     * Linha do tempo auditável de todos os eventos de domínio que afetaram esta ordem.
     *
     * <p>Cada entrada é imutável e idempotente: o consumidor verifica o {@code eventId}
     * antes de inserir para evitar duplicatas em caso de re-entrega da mensagem.</p>
     */
    private List<OrderHistoryEntry> history = new ArrayList<>();

    // =========================================================================
    // Record imutável para cada entrada no histórico de eventos
    // =========================================================================

    /**
     * Entrada imutável no histórico de eventos da ordem.
     *
     * @param eventId    UUID do {@link com.vibranium.contracts.events.DomainEvent} — chave de idempotência.
     * @param eventType  Tipo do evento em string (ex: "ORDER_RECEIVED", "FUNDS_RESERVED").
     * @param detail     Informação relevante do evento (ex: routing key, status anterior, motivo).
     * @param occurredOn Timestamp do evento conforme declarado no contrato de domínio.
     */
    public record OrderHistoryEntry(
            String  eventId,
            String  eventType,
            String  detail,
            Instant occurredOn
    ) {}

    // =========================================================================
    // Factory Method
    // =========================================================================

    /**
     * Cria um novo documento de ordem no estado inicial PENDING.
     *
     * @param orderId     ID da ordem.
     * @param userId      Keycloak ID do usuário.
     * @param orderType   BUY ou SELL.
     * @param price       Preço limite.
     * @param originalQty Quantidade total.
     * @param createdAt   Timestamp do evento {@code OrderReceivedEvent}.
     * @return Documento no estado PENDING com lista de history vazia.
     */
    public static OrderDocument createPending(String orderId, String userId,
                                               String orderType,
                                               BigDecimal price, BigDecimal originalQty,
                                               Instant createdAt) {
        OrderDocument doc = new OrderDocument();
        doc.orderId     = orderId;
        doc.userId      = userId;
        doc.orderType   = orderType;
        doc.status      = "PENDING";
        doc.price       = price;
        doc.originalQty = originalQty;
        doc.remainingQty = originalQty;
        doc.createdAt   = createdAt;
        doc.updatedAt   = Instant.now();
        return doc;
    }

    // =========================================================================
    // Mutação de estado
    // =========================================================================

    /**
     * Adiciona uma entrada ao histórico somente se o {@code eventId} ainda não existe
     * (idempotência contra re-entrega de mensagem pelo RabbitMQ).
     *
     * @param entry Entrada a ser appendada.
     * @return {@code true} se inserida; {@code false} se já existia (duplicata descartada).
     */
    public boolean appendHistory(OrderHistoryEntry entry) {
        boolean alreadyExists = history.stream()
                .anyMatch(h -> h.eventId().equals(entry.eventId()));
        if (alreadyExists) {
            return false;
        }
        history.add(entry);
        this.updatedAt = Instant.now();
        return true;
    }

    /**
     * Transiciona o status da ordem e registra o timestamp da última atualização.
     *
     * @param newStatus Novo status da máquina de estados.
     */
    public void transitionStatus(String newStatus) {
        this.status    = newStatus;
        this.updatedAt = Instant.now();
    }

    /**
     * Atualiza a quantidade restante após um match parcial.
     *
     * @param remainingQty Quantidade restante após o match.
     */
    public void updateRemainingQty(BigDecimal remainingQty) {
        this.remainingQty = remainingQty;
        this.updatedAt    = Instant.now();
    }

    // =========================================================================
    // Getters (sem setters — mutação via métodos de domínio acima)
    // =========================================================================

    public String getOrderId()                { return orderId; }
    public String getUserId()                 { return userId; }
    public String getOrderType()              { return orderType; }
    public String getStatus()                 { return status; }
    public BigDecimal getPrice()              { return price; }
    public BigDecimal getOriginalQty()        { return originalQty; }
    public BigDecimal getRemainingQty()       { return remainingQty; }
    public Instant getCreatedAt()             { return createdAt; }
    public Instant getUpdatedAt()             { return updatedAt; }
    public List<OrderHistoryEntry> getHistory() { return history; }
}
