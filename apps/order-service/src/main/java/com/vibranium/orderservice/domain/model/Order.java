package com.vibranium.orderservice.domain.model;

import com.vibranium.contracts.enums.OrderStatus;
import com.vibranium.contracts.enums.OrderType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Entidade de estado transacional de uma Ordem no Command Side (CQRS).
 *
 * <p>Representa a máquina de estados da Saga de Ordem:</p>
 * <pre>
 *   PENDING → OPEN → PARTIAL → FILLED
 *                           ↘ CANCELLED
 * </pre>
 *
 * <p>Esta tabela NÃO é o Read Model — consultas de leitura vão ao MongoDB.
 * O único propósito é rastrear o estado transacional enquanto a Saga
 * de bloqueio de fundos está em andamento, e após, durante o match.</p>
 *
 * <p>{@code @Version} garante Optimistic Locking para evitar atualizações
 * concorrentes conflitantes (ex: FundsLocked + FundsReservationFailed chegando
 * ao mesmo tempo para a mesma ordem).</p>
 */
@Entity
@Table(name = "tb_orders")
public class Order {

    /** ID gerado pelo order-service (não pelo banco). Imutável após criação. */
    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** Propaga o correlationId pelo ciclo completo da Saga (tracing distribuído). */
    @Column(name = "correlation_id", nullable = false, updatable = false)
    private UUID correlationId;

    /**
     * keycloak_id do usuário — deve existir em {@code tb_user_registry}.
     * FK lógica (não enforced no banco) para evitar JOINs em alta frequência.
     */
    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    /** Carteira do usuário informada na requisição REST. */
    @Column(name = "wallet_id", nullable = false)
    private UUID walletId;

    /** BUY ou SELL. */
    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false, length = 4)
    private OrderType orderType;

    /** Preço limite em BRL (8 casas decimais). */
    @Column(name = "price", nullable = false, precision = 18, scale = 8)
    private BigDecimal price;

    /** Quantidade total de VIBRANIUM (8 casas decimais). */
    @Column(name = "amount", nullable = false, precision = 18, scale = 8)
    private BigDecimal amount;

    /** Quantidade ainda não executada. Decrementada a cada match parcial. */
    @Column(name = "remaining_amount", nullable = false, precision = 18, scale = 8)
    private BigDecimal remainingAmount;

    /** Estado da máquina de estados. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private OrderStatus status;

    /**
     * Motivo técnico do cancelamento (ex: INSUFFICIENT_FUNDS).
     * Preenchido somente quando {@code status == CANCELLED}.
     */
    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    /** Timestamp de criação, imutável após persistência. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Timestamp da última atualização de estado. */
    @Column(name = "updated_at")
    private Instant updatedAt;

    /**
     * Versão do Optimistic Lock. Incrementado automaticamente pelo JPA em
     * cada {@code UPDATE}. Garante detecção de conflitos de escrita concorrente.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** Construtor exigido pelo JPA. */
    protected Order() {}

    /**
     * Factory method — cria uma ordem no estado inicial {@code PENDING}.
     *
     * @param id            UUID da ordem (gerado pelo controller).
     * @param correlationId UUID da correlação propagado pela Saga.
     * @param userId        keycloakId do usuário.
     * @param walletId      UUID da carteira.
     * @param orderType     BUY ou SELL.
     * @param price         Preço limite.
     * @param amount        Quantidade desejada.
     * @return Nova instância de Order no estado PENDING.
     */
    public static Order create(UUID id, UUID correlationId, String userId, UUID walletId,
                               OrderType orderType, BigDecimal price, BigDecimal amount) {
        Order order = new Order();
        order.id              = id;
        order.correlationId   = correlationId;
        order.userId          = userId;
        order.walletId        = walletId;
        order.orderType       = orderType;
        order.price           = price;
        order.amount          = amount;
        order.remainingAmount = amount;          // inicia com quantidade total disponível
        order.status          = OrderStatus.PENDING;
        return order;
    }

    // -------------------------------------------------------------------------
    // Máquina de estados — transições permitidas
    // -------------------------------------------------------------------------

    /**
     * Força uma transição de estado.
     *
     * <p>Não valida a máquina de estados em ordem para facilitar os testes;
     * a lógica de validação fica no serviço. Em produção, o {@code OrderCommandService}
     * é responsável por chamar apenas transições permitidas.</p>
     *
     * @param newStatus O novo estado desejado.
     */
    public void transitionTo(OrderStatus newStatus) {
        this.status = newStatus;
        this.updatedAt = Instant.now();
    }

    /**
     * Cancela a ordem registrando o motivo técnico da falha.
     *
     * @param reason Motivo padronizado ou mensagem livre para auditoria.
     */
    public void cancel(String reason) {
        this.status             = OrderStatus.CANCELLED;
        this.cancellationReason = reason;
        this.updatedAt          = Instant.now();
    }

    /**
     * Decrementa a quantidade restante após um match parcial.
     * Se {@code remainingAmount} chegar a zero, transita para FILLED.
     *
     * @param executedQty Quantidade executada no match.
     */
    public void applyMatch(BigDecimal executedQty) {
        this.remainingAmount = this.remainingAmount.subtract(executedQty);
        this.updatedAt       = Instant.now();

        if (this.remainingAmount.compareTo(BigDecimal.ZERO) <= 0) {
            this.remainingAmount = BigDecimal.ZERO;
            this.status          = OrderStatus.FILLED;
        } else {
            this.status = OrderStatus.PARTIAL;
        }
    }

    // -------------------------------------------------------------------------
    // JPA lifecycle — define timestamps automaticamente
    // -------------------------------------------------------------------------

    @PrePersist
    void onPersist() {
        this.createdAt = Instant.now();
        if (this.status == null) this.status = OrderStatus.PENDING;
        if (this.remainingAmount == null) this.remainingAmount = this.amount;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public UUID        getId()                 { return id; }
    public UUID        getCorrelationId()      { return correlationId; }
    public String      getUserId()             { return userId; }
    public UUID        getWalletId()           { return walletId; }
    public OrderType   getOrderType()          { return orderType; }
    public BigDecimal  getPrice()              { return price; }
    public BigDecimal  getAmount()             { return amount; }
    public BigDecimal  getRemainingAmount()    { return remainingAmount; }
    public OrderStatus getStatus()             { return status; }
    public String      getCancellationReason() { return cancellationReason; }
    public Instant     getCreatedAt()          { return createdAt; }
    public Instant     getUpdatedAt()          { return updatedAt; }
    public Long        getVersion()            { return version; }
}
