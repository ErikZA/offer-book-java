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
 *                  ↘        ↘ CANCELLED
 * </pre>
 *
 * <p>Esta tabela NÃO é o Read Model — consultas de leitura vão ao MongoDB.
 * O único propósito é rastrear o estado transacional enquanto a Saga
 * de bloqueio de fundos está em andamento, e após, durante o match.</p>
 *
 * <p>{@code @Version} garante Optimistic Locking para evitar atualizações
 * concorrentes conflitantes (ex: FundsLocked + FundsReservationFailed chegando
 * ao mesmo tempo para a mesma ordem).</p>
 *
 * <h3>Invariantes do agregado Order</h3>
 * <ul>
 *   <li>{@code remainingAmount >= 0} — nunca negativo.</li>
 *   <li>A quantidade executada acumulada nunca pode exceder {@code originalAmount}.</li>
 *   <li>{@code FILLED}    → {@code remainingAmount == 0}.</li>
 *   <li>{@code PARTIAL}   → {@code 0 < remainingAmount < originalAmount}.</li>
 *   <li>{@code OPEN}      → {@code remainingAmount == originalAmount}.</li>
 *   <li>{@code CANCELLED} → {@code remainingAmount > 0} (liquidação parcial não ocorreu).</li>
 * </ul>
 *
 * <h3>Política de concorrência</h3>
 * <p>{@code @Version} (optimistic locking) detecta conflitos de escrita simultânea.
 * Um evento de match recebido após cancelamento ({@code CANCELLED + applyMatch})
 * lança {@link IllegalStateException}, fazendo o container RabbitMQ enviar NACK
 * e a mensagem ir para a DLQ configurada no {@code RabbitMQConfig}.</p>
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
    // Máquina de estados — API semântica pública
    // -------------------------------------------------------------------------

    /**
     * Transita a ordem de {@code PENDING} para {@code OPEN}.
     *
     * <p>Chamado pelo {@code FundsReservedEventConsumer} quando o wallet-service
     * confirma o bloqueio de fundos e a ordem entra no livro de ofertas (Redis).</p>
     *
     * @throws IllegalStateException se o status atual não for {@code PENDING}.
     */
    public void markAsOpen() {
        requireStatus(OrderStatus.PENDING, "markAsOpen");
        this.status    = OrderStatus.OPEN;
        this.updatedAt = Instant.now();
    }

    /**
     * Cancela a ordem registrando o motivo técnico da falha.
     *
     * <p>Permitido apenas quando a ordem ainda não foi completamente executada.
     * Uma ordem {@code FILLED} não pode ser revertida — a liquidação já ocorreu
     * e o wallet-service confirmou a transferência de ativos.</p>
     *
     * @param reason Motivo padronizado (ex: {@code "INSUFFICIENT_FUNDS"}) ou mensagem livre.
     * @throws IllegalStateException se o status for {@code FILLED}.
     */
    public void cancel(String reason) {
        if (this.status == OrderStatus.FILLED) {
            throw new IllegalStateException(
                "Ordem FILLED não pode ser cancelada (orderId=%s)".formatted(this.id));
        }
        this.status             = OrderStatus.CANCELLED;
        this.cancellationReason = reason;
        this.updatedAt          = Instant.now();
    }

    /**
     * Decrementa a quantidade restante após um match (total ou parcial).
     *
     * <p>Transições possíveis:</p>
     * <ul>
     *   <li>{@code OPEN    → FILLED}  quando {@code remainingAmount == 0}</li>
     *   <li>{@code OPEN    → PARTIAL} quando {@code remainingAmount > 0}</li>
     *   <li>{@code PARTIAL → FILLED}  quando {@code remainingAmount == 0}</li>
     *   <li>{@code PARTIAL → PARTIAL} quando {@code remainingAmount > 0}</li>
     * </ul>
     *
     * <p><strong>Política DLQ:</strong> se chamado em status terminal ({@code CANCELLED},
     * {@code FILLED}) ou {@code PENDING}, lança {@link IllegalStateException}. O container
     * RabbitMQ captura a exceção, envia NACK e a mensagem é roteada para a DLQ,
     * impedindo corrupção silenciosa de dados em race conditions.</p>
     *
     * @param executedQty Quantidade executada no match. Deve ser positiva e ≤ {@code remainingAmount}.
     * @throws IllegalArgumentException se {@code executedQty} for nula, zero ou negativa,
     *                                  ou exceder {@code remainingAmount}.
     * @throws IllegalStateException    se o status não permitir match
     *                                  ({@code CANCELLED}, {@code FILLED}, {@code PENDING}).
     */
    public void applyMatch(BigDecimal executedQty) {
        // Pré-condição 1: status deve ser OPEN ou PARTIAL
        requireStatusIn("applyMatch", OrderStatus.OPEN, OrderStatus.PARTIAL);

        // Pré-condição 2: quantidade executada deve ser positiva
        if (executedQty == null || executedQty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(
                "executedQty deve ser positivo: %s (orderId=%s)".formatted(executedQty, this.id));
        }

        // Pré-condição 3: não pode exceder a quantidade restante
        if (executedQty.compareTo(this.remainingAmount) > 0) {
            throw new IllegalArgumentException(
                "executedQty=%s excede remainingAmount=%s (orderId=%s)"
                    .formatted(executedQty, this.remainingAmount, this.id));
        }

        this.remainingAmount = this.remainingAmount.subtract(executedQty)
                                                   .stripTrailingZeros();
        this.updatedAt       = Instant.now();

        if (this.remainingAmount.compareTo(BigDecimal.ZERO) <= 0) {
            this.remainingAmount = BigDecimal.ZERO;
            this.status          = OrderStatus.FILLED;
        } else {
            this.status = OrderStatus.PARTIAL;
        }
    }

    // -------------------------------------------------------------------------
    // Máquina de estados — API restrita (package-private)
    // -------------------------------------------------------------------------

    /**
     * Reverte um match que falhou na liquidação. Restaura a quantidade executada de volta
     * para {@code remainingAmount} e ajusta o status da ordem.
     *
     * <p>Regras de transição de status:</p>
     * <ul>
     *   <li>FILLED → PARTIAL (se remainingAmount &lt; amount original após revert)</li>
     *   <li>FILLED → OPEN (se remainingAmount == amount — todos os matches revertidos)</li>
     *   <li>PARTIAL → PARTIAL (permanece parcial com mais remainingAmount)</li>
     *   <li>PARTIAL → OPEN (se remainingAmount == amount)</li>
     * </ul>
     *
     * <p>Não é chamado para ordens CANCELLED — a compensação já foi emitida pelo cleanup.</p>
     *
     * @param revertedQty quantidade a restaurar (tipicamente {@code matchAmount} do match revertido)
     * @throws IllegalStateException se a ordem estiver PENDING ou CANCELLED (estados que não admitem revert)
     * @throws IllegalArgumentException se {@code revertedQty} resultar em remainingAmount &gt; amount
     */
    public void revertMatch(BigDecimal revertedQty) {
        if (this.status == OrderStatus.PENDING || this.status == OrderStatus.CANCELLED) {
            throw new IllegalStateException(
                    "Cannot revert match on order %s in status %s".formatted(this.id, this.status));
        }

        BigDecimal newRemaining = this.remainingAmount.add(revertedQty);
        if (newRemaining.compareTo(this.amount) > 0) {
            throw new IllegalArgumentException(
                    "Revert would exceed original amount: remaining=%s + revert=%s > amount=%s"
                            .formatted(this.remainingAmount, revertedQty, this.amount));
        }

        this.remainingAmount = newRemaining;

        // Ajusta status: se remainingAmount voltou ao total, a ordem está OPEN (sem fills)
        if (this.remainingAmount.compareTo(this.amount) == 0) {
            this.status = OrderStatus.OPEN;
        } else {
            this.status = OrderStatus.PARTIAL;
        }

        this.updatedAt = Instant.now();
    }

    /**
     * Força uma transição de estado sem validação da máquina de estados.
     *
     * <p><strong>Uso exclusivo em testes</strong> do mesmo pacote que precisam
     * montar cenários arbitrários (ex: forçar {@code OPEN} para testar idempotência).
     * Nunca deve ser chamado em código de produção.</p>
     *
     * @param newStatus O novo estado desejado.
     */
    void transitionTo(OrderStatus newStatus) {
        this.status    = newStatus;
        this.updatedAt = Instant.now();
    }

    // -------------------------------------------------------------------------
    // Guards internos — legibilidade e reutilização
    // -------------------------------------------------------------------------

    /**
     * Valida que o status atual é o esperado antes de executar uma operação.
     *
     * @param expected  Status exigido.
     * @param operation Nome da operação (usado na mensagem de erro).
     * @throws IllegalStateException se o status atual diferir do esperado.
     */
    private void requireStatus(OrderStatus expected, String operation) {
        if (this.status != expected) {
            throw new IllegalStateException(
                "Operação '%s' inválida para orderId=%s: status atual=%s, esperado=%s"
                    .formatted(operation, this.id, this.status, expected));
        }
    }

    /**
     * Valida que o status atual está dentro dos estados permitidos.
     *
     * @param operation Nome da operação (usado na mensagem de erro).
     * @param allowed   Estados que permitem a operação.
     * @throws IllegalStateException se o status atual não estiver na lista de permitidos.
     */
    private void requireStatusIn(String operation, OrderStatus... allowed) {
        for (OrderStatus s : allowed) {
            if (this.status == s) return;
        }
        throw new IllegalStateException(
            "Operação '%s' inválida para orderId=%s: status=%s não é um dos permitidos=%s"
                .formatted(operation, this.id, this.status, java.util.Arrays.toString(allowed)));
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
