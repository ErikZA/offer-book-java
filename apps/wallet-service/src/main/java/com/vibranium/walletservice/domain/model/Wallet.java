package com.vibranium.walletservice.domain.model;

import com.vibranium.contracts.enums.AssetType;
import com.vibranium.walletservice.web.exception.InsufficientFundsException;
import com.vibranium.walletservice.web.exception.InsufficientLockedFundsException;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Aggregate Root da carteira do usuário.
 *
 * <p>Cada usuário possui exatamente uma carteira (UNIQUE em user_id).
 * Mantém os saldos dos dois ativos da plataforma: BRL (moeda de cotação)
 * e VIBRANIUM (ativo negociado).</p>
 *
 * <p>Os saldos são separados em "available" (disponível para operar)
 * e "locked" (bloqueado aguardando match/liquidação). A invariante
 * financeira — nunca negativo — é reforçada tanto pela lógica de domínio
 * quanto pelas constraints CHECK do PostgreSQL.</p>
 *
 * <p>O lock pessimista ({@code SELECT FOR UPDATE}) é aplicado pelo
 * {@code WalletRepository} antes de qualquer escrita, garantindo que
 * atualizações concorrentes sejam serializadas sem race conditions.</p>
 *
 * <h3>Invariantes do agregado Wallet:</h3>
 * <ul>
 *   <li>Nenhum saldo pode ser negativo.</li>
 *   <li>Locked nunca pode exceder o available anterior à reserva.</li>
 *   <li>Toda operação deve preservar consistência interna.</li>
 *   <li>Wallet é aggregate root e controla seu próprio estado.</li>
 * </ul>
 *
 * <p>Wallet utiliza optimistic locking via {@code @Version} para detectar
 * conflitos em atualizações concorrentes na camada JPA.</p>
 */
@Entity
@Table(name = "tb_wallet")
public class Wallet {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Referência ao usuário no Keycloak. Imutável após criação.
     * Deve corresponder ao {@code sub} JWT do token de autenticação.
     */
    @Column(name = "user_id", nullable = false, unique = true, updatable = false)
    private UUID userId;

    @Column(name = "brl_available", nullable = false, precision = 19, scale = 8)
    private BigDecimal brlAvailable;

    @Column(name = "brl_locked", nullable = false, precision = 19, scale = 8)
    private BigDecimal brlLocked;

    @Column(name = "vib_available", nullable = false, precision = 19, scale = 8)
    private BigDecimal vibAvailable;

    @Column(name = "vib_locked", nullable = false, precision = 19, scale = 8)
    private BigDecimal vibLocked;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Versão para optimistic locking — incrementada automaticamente pelo JPA
     * a cada {@code UPDATE}. Garante detecção de conflitos sem bloquear leituras.
     */
    @Version
    private Long version;

    /** Construtor protegido exigido pelo JPA. Não usar diretamente. */
    protected Wallet() {}

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    /**
     * Cria uma nova carteira para o usuário com saldos iniciais configuráveis.
     * Saldos "locked" iniciam sempre em zero.
     *
     * @param userId       UUID do usuário no Keycloak.
     * @param brlAvailable Saldo BRL disponível inicial (ex: zero no onboarding).
     * @param vibAvailable Saldo VIB disponível inicial (ex: zero no onboarding).
     * @return Nova instância de Wallet não persistida.
     */
    public static Wallet create(UUID userId, BigDecimal brlAvailable, BigDecimal vibAvailable) {
        Wallet w = new Wallet();
        w.userId = userId;
        w.brlAvailable = brlAvailable;
        w.vibAvailable = vibAvailable;
        w.brlLocked = BigDecimal.ZERO;
        w.vibLocked = BigDecimal.ZERO;
        w.createdAt = Instant.now();
        w.updatedAt = Instant.now();
        return w;
    }

    // -------------------------------------------------------------------------
    // JPA lifecycle
    // -------------------------------------------------------------------------

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    // -------------------------------------------------------------------------
    // Domain behaviour
    // -------------------------------------------------------------------------

    /**
     * Reserva (bloqueia) saldo para uma ordem: move {@code amount} de
     * "available" para "locked". Valida saldo antes de qualquer modificação.
     *
     * @param asset  Tipo do ativo (BRL para BUY, VIBRANIUM para SELL).
     * @param amount Valor a bloquear — deve ser positivo.
     * @throws InsufficientFundsException se saldo disponível for insuficiente.
     */
    public void reserveFunds(AssetType asset, BigDecimal amount) {
        if (asset == AssetType.BRL) {
            if (brlAvailable.compareTo(amount) < 0) {
                throw new InsufficientFundsException(
                        "saldo BRL insuficiente: disponível=" + brlAvailable + ", solicitado=" + amount);
            }
            brlAvailable = brlAvailable.subtract(amount);
            brlLocked = brlLocked.add(amount);
        } else {
            if (vibAvailable.compareTo(amount) < 0) {
                throw new InsufficientFundsException(
                        "saldo VIB insuficiente: disponível=" + vibAvailable + ", solicitado=" + amount);
            }
            vibAvailable = vibAvailable.subtract(amount);
            vibLocked = vibLocked.add(amount);
        }
    }

    /**
     * Libera (desbloqueia) saldo do caminho compensatório da Saga: move {@code amount}
     * de "locked" de volta para "available". Operação simétrica a {@link #reserveFunds}.
     *
     * <p>Valida ambas as pré-condições antes de qualquer mutação — se alguma falhar,
     * nenhum campo é alterado (atomicidade da operação).</p>
     *
     * @param asset  Tipo do ativo (BRL ou VIBRANIUM).
     * @param amount Valor a devolver ao saldo disponível — deve ser positivo.
     * @throws IllegalArgumentException          se {@code amount} for zero ou negativo.
     * @throws InsufficientLockedFundsException  se o saldo bloqueado for menor que {@code amount}.
     */
    public void releaseFunds(AssetType asset, BigDecimal amount) {
        // Invariante 1: amount deve ser positivo
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    "amount deve ser positivo para release: recebido=" + amount);
        }

        if (asset == AssetType.BRL) {
            // Invariante 2: locked não pode ficar negativo
            if (brlLocked.compareTo(amount) < 0) {
                throw new InsufficientLockedFundsException(
                        "BRL locked insuficiente para release: locked=" + brlLocked + ", solicitado=" + amount);
            }
            brlLocked     = brlLocked.subtract(amount);
            brlAvailable  = brlAvailable.add(amount);
        } else {
            if (vibLocked.compareTo(amount) < 0) {
                throw new InsufficientLockedFundsException(
                        "VIB locked insuficiente para release: locked=" + vibLocked + ", solicitado=" + amount);
            }
            vibLocked     = vibLocked.subtract(amount);
            vibAvailable  = vibAvailable.add(amount);
        }
    }

    /**
     * Liquida um trade do lado comprador: libera o BRL bloqueado e credita o VIB recebido.
     *
     * <p>Encapsula a invariante: {@code brlToRelease} não pode exceder o {@code brlLocked}
     * atual. Se violada, lança exceção sem modificar nenhum campo.</p>
     *
     * @param brlToRelease Valor em BRL a ser removido de {@code brlLocked}.
     * @param vibToCredit  Quantidade de VIB a ser adicionada a {@code vibAvailable}.
     * @throws InsufficientFundsException se {@code brlLocked} for menor que {@code brlToRelease}.
     */
    public void applyBuySettlement(BigDecimal brlToRelease, BigDecimal vibToCredit) {
        if (this.brlLocked.compareTo(brlToRelease) < 0) {
            throw new InsufficientFundsException(
                    "BRL locked insuficiente: locked=" + brlLocked + ", necessário=" + brlToRelease);
        }
        this.brlLocked = this.brlLocked.subtract(brlToRelease);
        this.vibAvailable = this.vibAvailable.add(vibToCredit);
    }

    /**
     * Liquida um trade do lado vendedor: libera o VIB bloqueado e credita o BRL recebido.
     *
     * <p>Encapsula a invariante: {@code vibToRelease} não pode exceder o {@code vibLocked}
     * atual. Se violada, lança exceção sem modificar nenhum campo.</p>
     *
     * @param vibToRelease Quantidade de VIB a ser removida de {@code vibLocked}.
     * @param brlToCredit  Valor em BRL a ser adicionado a {@code brlAvailable}.
     * @throws InsufficientFundsException se {@code vibLocked} for menor que {@code vibToRelease}.
     */
    public void applySellSettlement(BigDecimal vibToRelease, BigDecimal brlToCredit) {
        if (this.vibLocked.compareTo(vibToRelease) < 0) {
            throw new InsufficientFundsException(
                    "VIB locked insuficiente: locked=" + vibLocked + ", necessário=" + vibToRelease);
        }
        this.vibLocked = this.vibLocked.subtract(vibToRelease);
        this.brlAvailable = this.brlAvailable.add(brlToCredit);
    }

    /**
     * Ajusta o saldo disponível via delta (positivo = crédito, negativo = débito).
     * Valida AMBOS os deltas antes de aplicar qualquer modificação — garantindo
     * atomicidade: se um falhar, nenhum é aplicado.
     *
     * @param brlDelta Delta de BRL (pode ser nulo para não alterar).
     * @param vibDelta Delta de VIB (pode ser nulo para não alterar).
     * @throws InsufficientFundsException se qualquer saldo resultante for negativo.
     */
    public void adjustBalance(BigDecimal brlDelta, BigDecimal vibDelta) {
        // Valida tudo antes de modificar qualquer campo
        if (brlDelta != null && brlAvailable.add(brlDelta).compareTo(BigDecimal.ZERO) < 0) {
            throw new InsufficientFundsException(
                    "O saldo BRL ficaria negativo após o ajuste: disponível=" + brlAvailable + ", delta=" + brlDelta);
        }
        if (vibDelta != null && vibAvailable.add(vibDelta).compareTo(BigDecimal.ZERO) < 0) {
            throw new InsufficientFundsException(
                    "O saldo VIB ficaria negativo após o ajuste: disponível=" + vibAvailable + ", delta=" + vibDelta);
        }
        // Aplica as mudanças
        if (brlDelta != null) {
            brlAvailable = brlAvailable.add(brlDelta);
        }
        if (vibDelta != null) {
            vibAvailable = vibAvailable.add(vibDelta);
        }
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    public UUID getId() { return id; }

    public UUID getUserId() { return userId; }

    public BigDecimal getBrlAvailable() { return brlAvailable; }

    public BigDecimal getBrlLocked() { return brlLocked; }

    public BigDecimal getVibAvailable() { return vibAvailable; }

    public BigDecimal getVibLocked() { return vibLocked; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }

    /** Retorna a versão de optimistic locking atual para fins de diagnóstico. */
    public Long getVersion() { return version; }

    // Nota: setters públicos de saldo foram removidos intencionalmente (US-005).
    // Toda mutação de estado deve ocorrer via métodos de domínio:
    //   - reserveFunds()         → bloqueia saldo para uma ordem
    //   - releaseFunds()         → devolve saldo bloqueado (caminho compensatório Saga)
    //   - applyBuySettlement()   → liquida trade no lado comprador
    //   - applySellSettlement()  → liquida trade no lado vendedor
    //   - adjustBalance()        → ajuste administrativo via delta
    // Isso garante que as invariantes do agregado não possam ser violadas
    // por código externo à camada de domínio.
}
