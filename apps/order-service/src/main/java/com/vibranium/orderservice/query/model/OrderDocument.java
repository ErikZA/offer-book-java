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
 * <p><strong>Estratégia de consistência eventual:</strong> O padrão Transactional Outbox
 * garante que o evento seja gravado atomicamente com a Ordem no PostgreSQL. O
 * {@code OrderOutboxPublisherService} faz o relay assíncrono para o RabbitMQ via
 * {@code SELECT FOR UPDATE SKIP LOCKED}, e o consumer {@code OrderEventProjectionConsumer}
 * projeta o evento neste documento MongoDB — eventual consistency intencional (CQRS).</p>
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
     * Cria um novo documento de ordem no estado inicial PENDING com todos os dados financeiros.
     *
     * <p>Chamado por {@code onOrderReceived()} quando o documento ainda não existe no MongoDB.
     * Se o documento já existir (criado lazily por evento out-of-order), o consumer deve
     * chamar {@link #enrichFields} para preencher os campos financeiros faltantes.</p>
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
        doc.orderId      = orderId;
        doc.userId       = userId;
        doc.orderType    = orderType;
        doc.status       = "PENDING";
        doc.price        = price;
        doc.originalQty  = originalQty;
        doc.remainingQty = originalQty;
        doc.createdAt    = createdAt;
        doc.updatedAt    = Instant.now();
        return doc;
    }

    /**
     * Cria um documento mínimo (stub) com apenas {@code orderId}, {@code status=PENDING}
     * e {@code createdAt}.
     *
     * <p><strong>Decisão de design — Criação Lazy Determinística (AT-05.1):</strong>
     * Quando um evento chega fora de ordem (ex: {@code FUNDS_RESERVED} antes de
     * {@code ORDER_RECEIVED}), o consumer não pode lançar exceção nem descartar o evento
     * silenciosamente. Cria-se este stub para garantir que o evento seja registrado no
     * histórico. Quando {@code ORDER_RECEIVED} chegar, {@link #enrichFields} preencherá
     * os campos financeiros faltantes de forma idempotente.</p>
     *
     * <p><strong>Campos intencionalmente nulos:</strong> {@code userId}, {@code orderType},
     * {@code price}, {@code originalQty}, {@code remainingQty}. Consumidores que dependem
     * de {@code remainingQty} (ex: {@code MATCH_EXECUTED}) devem tratar o valor nulo
     * explicitamente.</p>
     *
     * @param orderId   ID da ordem.
     * @param createdAt Timestamp do evento que originou a criação lazy.
     * @return Documento stub no estado PENDING com history vazia.
     */
    public static OrderDocument createMinimalPending(String orderId, Instant createdAt) {
        OrderDocument doc = new OrderDocument();
        doc.orderId   = orderId;
        doc.status    = "PENDING";
        doc.createdAt = createdAt;
        doc.updatedAt = Instant.now();
        // userId, orderType, price, originalQty, remainingQty: null intencionalmente.
        // Serão preenchidos quando ORDER_RECEIVED chegar via enrichFields().
        return doc;
    }

    /**
     * Enriquece o documento com dados financeiros do {@code OrderReceivedEvent},
     * preenchendo apenas os campos que ainda são {@code null}.
     *
     * <p><strong>Idempotente:</strong> se o campo já foi preenchido (documento criado
     * normalmente pelo {@code ORDER_RECEIVED}), não sobrescreve. Garante que um
     * {@code ORDER_RECEIVED} tardio (após criação lazy) preencha os dados corretamente
     * sem risco de regressão nos demais fluxos.</p>
     *
     * @param userId      Keycloak ID do usuário.
     * @param orderType   BUY ou SELL.
     * @param price       Preço limite.
     * @param originalQty Quantidade total da ordem.
     */
    public void enrichFields(String userId, String orderType,
                              BigDecimal price, BigDecimal originalQty) {
        // Preenche apenas campos ausentes — nunca sobrescreve dado já existente.
        if (this.userId == null)        this.userId       = userId;
        if (this.orderType == null)     this.orderType    = orderType;
        if (this.price == null)         this.price        = price;
        if (this.originalQty == null)   this.originalQty  = originalQty;
        // remainingQty: somente preenche se nunca foi tocado por um MATCH_EXECUTED.
        // Se já existe (foi decrementado), preservamos a quantidade parcialmente preenchida.
        if (this.remainingQty == null)  this.remainingQty = originalQty;
        this.updatedAt = Instant.now();
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
