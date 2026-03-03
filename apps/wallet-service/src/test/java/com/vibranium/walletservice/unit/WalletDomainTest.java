package com.vibranium.walletservice.unit;

import com.vibranium.contracts.enums.AssetType;
import com.vibranium.walletservice.domain.model.Wallet;
import com.vibranium.walletservice.exception.InsufficientFundsException;
import com.vibranium.walletservice.exception.InsufficientLockedFundsException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testes unitários do agregado {@link Wallet} — validam invariantes de domínio puro,
 * sem contexto Spring ou banco de dados.
 *
 * <p>Segue a estratégia TDD: os testes foram escritos antes da implementação
 * para guiar o design (FASE RED → GREEN).</p>
 *
 * <p>Invariantes validadas:</p>
 * <ul>
 *   <li>Nenhum saldo pode ser negativo.</li>
 *   <li>Locked nunca pode exceder o available anterior à reserva.</li>
 *   <li>Toda operação de settlement preserva a consistência interna.</li>
 *   <li>Wallet é aggregate root e controla seu próprio estado.</li>
 * </ul>
 */
@DisplayName("WalletDomain — Invariantes do agregado Wallet")
class WalletDomainTest {

    // -------------------------------------------------------------------------
    // Helpers de factory
    // -------------------------------------------------------------------------

    private Wallet walletWith(BigDecimal brlAvailable, BigDecimal vibAvailable) {
        return Wallet.create(UUID.randomUUID(), brlAvailable, vibAvailable);
    }

    /**
     * Cria carteira e bloqueia fundos via {@code reserveFunds} para simular
     * estado pós-reserva de forma semanticamente correta, sem setters.
     */
    private Wallet walletWithBrlLocked(BigDecimal brlLocked) {
        Wallet w = walletWith(brlLocked, BigDecimal.ZERO);
        w.reserveFunds(AssetType.BRL, brlLocked);
        return w;
    }

    private Wallet walletWithVibLocked(BigDecimal vibLocked) {
        Wallet w = walletWith(BigDecimal.ZERO, vibLocked);
        w.reserveFunds(AssetType.VIBRANIUM, vibLocked);
        return w;
    }

    // =========================================================================
    // reserveFunds
    // =========================================================================

    @Nested
    @DisplayName("reserveFunds()")
    class ReserveFundsTests {

        @Test
        @DisplayName("Com saldo suficiente: deve reduzir available e aumentar locked")
        void reserveFunds_withSufficientBalance_reducesAvailableAndIncreasesLocked() {
            // Arrange
            Wallet wallet = walletWith(new BigDecimal("500.00"), BigDecimal.ZERO);

            // Act
            wallet.reserveFunds(AssetType.BRL, new BigDecimal("150.00"));

            // Assert
            assertThat(wallet.getBrlAvailable()).isEqualByComparingTo("350.00");
            assertThat(wallet.getBrlLocked()).isEqualByComparingTo("150.00");
        }

        @Test
        @DisplayName("Com saldo insuficiente: deve lançar InsufficientFundsException sem alterar estado")
        void reserveFunds_withInsufficientBalance_throwsInsufficientFundsException() {
            // Arrange
            Wallet wallet = walletWith(new BigDecimal("100.00"), BigDecimal.ZERO);
            BigDecimal antes = wallet.getBrlAvailable();

            // Act & Assert
            assertThatThrownBy(() -> wallet.reserveFunds(AssetType.BRL, new BigDecimal("200.00")))
                    .isInstanceOf(InsufficientFundsException.class)
                    .hasMessageContaining("saldo BRL insuficiente");

            // Estado não deve ter sido alterado após exceção
            assertThat(wallet.getBrlAvailable()).isEqualByComparingTo(antes);
            assertThat(wallet.getBrlLocked()).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    // =========================================================================
    // applyBuySettlement
    // =========================================================================

    @Nested
    @DisplayName("applyBuySettlement()")
    class ApplyBuySettlementTests {

        @Test
        @DisplayName("Com BRL locked válido: deve liberar BRL e creditar VIB corretamente")
        void applyBuySettlement_withValidLock_transfersCorrectly() {
            // Arrange — comprador com R$200 bloqueados aguardando match
            Wallet buyer = walletWithBrlLocked(new BigDecimal("200.00"));

            // Act — match de 10 VIB a R$20 cada = R$200 total
            buyer.applyBuySettlement(new BigDecimal("200.00"), new BigDecimal("10.00"));

            // Assert — BRL locked deve ser zerado, VIB available deve ser creditado
            assertThat(buyer.getBrlLocked()).isEqualByComparingTo("0.00");
            assertThat(buyer.getVibAvailable()).isEqualByComparingTo("10.00");
            // BRL available não muda para o comprador
            assertThat(buyer.getBrlAvailable()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("BRL locked insuficiente: deve lançar InsufficientFundsException sem alterar estado")
        void applyBuySettlement_withInsufficientLock_throwsException() {
            // Arrange — comprador só tem R$50 bloqueados mas match exige R$200
            Wallet buyer = walletWithBrlLocked(new BigDecimal("50.00"));
            BigDecimal lockedAntes = buyer.getBrlLocked();
            BigDecimal vibAntes = buyer.getVibAvailable();

            // Act & Assert
            assertThatThrownBy(() -> buyer.applyBuySettlement(new BigDecimal("200.00"), new BigDecimal("10.00")))
                    .isInstanceOf(InsufficientFundsException.class)
                    .hasMessageContaining("BRL locked insuficiente");

            // Estado preservado após exceção
            assertThat(buyer.getBrlLocked()).isEqualByComparingTo(lockedAntes);
            assertThat(buyer.getVibAvailable()).isEqualByComparingTo(vibAntes);
        }
    }

    // =========================================================================
    // applySellSettlement
    // =========================================================================

    @Nested
    @DisplayName("applySellSettlement()")
    class ApplySellSettlementTests {

        @Test
        @DisplayName("Com VIB locked válido: deve liberar VIB e creditar BRL corretamente")
        void applySellSettlement_withValidLock_transfersCorrectly() {
            // Arrange — vendedor com 10 VIB bloqueados aguardando match
            Wallet seller = walletWithVibLocked(new BigDecimal("10.00"));

            // Act — match: libera 10 VIB e credita R$200
            seller.applySellSettlement(new BigDecimal("10.00"), new BigDecimal("200.00"));

            // Assert — VIB locked zerado, BRL available creditado
            assertThat(seller.getVibLocked()).isEqualByComparingTo("0.00");
            assertThat(seller.getBrlAvailable()).isEqualByComparingTo("200.00");
            // VIB available não muda para o vendedor
            assertThat(seller.getVibAvailable()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("VIB locked insuficiente: deve lançar InsufficientFundsException sem alterar estado")
        void applySellSettlement_releasesBelowZero_throwsException() {
            // Arrange — vendedor com apenas 5 VIB bloqueados, match exige 10
            Wallet seller = walletWithVibLocked(new BigDecimal("5.00"));
            BigDecimal vibLockedAntes = seller.getVibLocked();
            BigDecimal brlAntes = seller.getBrlAvailable();

            // Act & Assert
            assertThatThrownBy(() -> seller.applySellSettlement(new BigDecimal("10.00"), new BigDecimal("200.00")))
                    .isInstanceOf(InsufficientFundsException.class)
                    .hasMessageContaining("VIB locked insuficiente");

            // Estado preservado após exceção
            assertThat(seller.getVibLocked()).isEqualByComparingTo(vibLockedAntes);
            assertThat(seller.getBrlAvailable()).isEqualByComparingTo(brlAntes);
        }
    }

    // =========================================================================
    // adjustBalance
    // =========================================================================

    @Nested
    @DisplayName("adjustBalance()")
    class AdjustBalanceTests {

        @Test
        @DisplayName("Crédito BRL positivo: deve aumentar brlAvailable sem alterar VIB")
        void adjustBalance_positiveBrlDelta_increasesBrlAvailable() {
            Wallet wallet = walletWith(new BigDecimal("100.00"), new BigDecimal("5.00"));

            wallet.adjustBalance(new BigDecimal("50.00"), null);

            assertThat(wallet.getBrlAvailable()).isEqualByComparingTo("150.00");
            assertThat(wallet.getVibAvailable()).isEqualByComparingTo("5.00");
        }

        @Test
        @DisplayName("Débito BRL negativo válido: deve reduzir brlAvailable sem alterar VIB")
        void adjustBalance_negativeBrlDelta_decreasesBrlAvailable() {
            Wallet wallet = walletWith(new BigDecimal("200.00"), BigDecimal.ZERO);

            wallet.adjustBalance(new BigDecimal("-80.00"), null);

            assertThat(wallet.getBrlAvailable()).isEqualByComparingTo("120.00");
        }

        @Test
        @DisplayName("Crédito VIB positivo: deve aumentar vibAvailable sem alterar BRL")
        void adjustBalance_positiveVibDelta_increasesVibAvailable() {
            Wallet wallet = walletWith(BigDecimal.ZERO, new BigDecimal("10.00"));

            wallet.adjustBalance(null, new BigDecimal("3.00"));

            assertThat(wallet.getVibAvailable()).isEqualByComparingTo("13.00");
            assertThat(wallet.getBrlAvailable()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("Ambos os deltas: deve ajustar BRL e VIB simultaneamente")
        void adjustBalance_bothDeltas_adjustsBothBalances() {
            Wallet wallet = walletWith(new BigDecimal("500.00"), new BigDecimal("10.00"));

            wallet.adjustBalance(new BigDecimal("-100.00"), new BigDecimal("2.00"));

            assertThat(wallet.getBrlAvailable()).isEqualByComparingTo("400.00");
            assertThat(wallet.getVibAvailable()).isEqualByComparingTo("12.00");
        }

        @Test
        @DisplayName("Débito BRL resultaria negativo: deve lançar InsufficientFundsException sem alterar estado")
        void adjustBalance_brlWouldGoNegative_throwsAndPreservesState() {
            Wallet wallet = walletWith(new BigDecimal("50.00"), new BigDecimal("5.00"));
            BigDecimal brlAntes = wallet.getBrlAvailable();
            BigDecimal vibAntes = wallet.getVibAvailable();

            // Débito de R$100 em carteira com R$50 → ficaria negativo
            assertThatThrownBy(() -> wallet.adjustBalance(new BigDecimal("-100.00"), null))
                    .isInstanceOf(InsufficientFundsException.class)
                    .hasMessageContaining("BRL");

            // Atomicidade: nenhum campo alterado
            assertThat(wallet.getBrlAvailable()).isEqualByComparingTo(brlAntes);
            assertThat(wallet.getVibAvailable()).isEqualByComparingTo(vibAntes);
        }

        @Test
        @DisplayName("Débito VIB resultaria negativo: deve lançar InsufficientFundsException sem alterar estado")
        void adjustBalance_vibWouldGoNegative_throwsAndPreservesState() {
            Wallet wallet = walletWith(new BigDecimal("100.00"), new BigDecimal("2.00"));
            BigDecimal brlAntes = wallet.getBrlAvailable();
            BigDecimal vibAntes = wallet.getVibAvailable();

            // Débito de 5 VIB em carteira com apenas 2 → ficaria negativo
            assertThatThrownBy(() -> wallet.adjustBalance(null, new BigDecimal("-5.00")))
                    .isInstanceOf(InsufficientFundsException.class)
                    .hasMessageContaining("VIB");

            // Atomicidade: nenhum campo alterado
            assertThat(wallet.getBrlAvailable()).isEqualByComparingTo(brlAntes);
            assertThat(wallet.getVibAvailable()).isEqualByComparingTo(vibAntes);
        }

        @Test
        @DisplayName("Atomicidade: BRL inválido + VIB válido → nenhum campo deve ser alterado")
        void adjustBalance_brlInvalidVibValid_neitherFieldChanged() {
            Wallet wallet = walletWith(new BigDecimal("10.00"), new BigDecimal("10.00"));
            BigDecimal brlAntes = wallet.getBrlAvailable();
            BigDecimal vibAntes = wallet.getVibAvailable();

            // BRL ficaria negativo mesmo com VIB sendo crédito válido
            assertThatThrownBy(() -> wallet.adjustBalance(new BigDecimal("-50.00"), new BigDecimal("5.00")))
                    .isInstanceOf(InsufficientFundsException.class);

            // Nenhum campo alterado — a validação ocorre antes de qualquer modificação
            assertThat(wallet.getBrlAvailable()).isEqualByComparingTo(brlAntes);
            assertThat(wallet.getVibAvailable()).isEqualByComparingTo(vibAntes);
        }
    }

    // =========================================================================
    // releaseFunds
    // =========================================================================

    @Nested
    @DisplayName("releaseFunds()")
    class ReleaseFundsTests {

        @Test
        @DisplayName("BRL: release válido deve mover locked → available mantendo invariante")
        void releaseFunds_validBrl_movesLockedToAvailable() {
            // Arrange — carteira com R$300 originalmente; R$100 foram reservados
            Wallet wallet = walletWith(new BigDecimal("300.00"), BigDecimal.ZERO);
            wallet.reserveFunds(AssetType.BRL, new BigDecimal("100.00"));
            // Estado: brlAvailable=200, brlLocked=100

            // Act — compensação: devolve R$100 de locked → available
            wallet.releaseFunds(AssetType.BRL, new BigDecimal("100.00"));

            // Assert
            assertThat(wallet.getBrlAvailable()).isEqualByComparingTo("300.00");
            assertThat(wallet.getBrlLocked()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("VIB: release válido deve mover VIB locked → VIB available")
        void releaseFunds_validVib_movesLockedToAvailable() {
            // Arrange — vendedor com 10 VIB reservados
            Wallet wallet = walletWith(BigDecimal.ZERO, new BigDecimal("10.00"));
            wallet.reserveFunds(AssetType.VIBRANIUM, new BigDecimal("10.00"));
            // Estado: vibAvailable=0, vibLocked=10

            // Act
            wallet.releaseFunds(AssetType.VIBRANIUM, new BigDecimal("10.00"));

            // Assert
            assertThat(wallet.getVibAvailable()).isEqualByComparingTo("10.00");
            assertThat(wallet.getVibLocked()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("locked insuficiente: deve lançar InsufficientLockedFundsException sem alterar estado")
        void releaseFunds_insufficientLocked_throwsExceptionAndPreservesState() {
            // Arrange — apenas R$50 bloqueados, mas tentando liberar R$200
            Wallet wallet = walletWithBrlLocked(new BigDecimal("50.00"));
            BigDecimal availableAntes = wallet.getBrlAvailable();
            BigDecimal lockedAntes    = wallet.getBrlLocked();

            // Act & Assert
            assertThatThrownBy(() -> wallet.releaseFunds(AssetType.BRL, new BigDecimal("200.00")))
                    .isInstanceOf(InsufficientLockedFundsException.class)
                    .hasMessageContaining("BRL");

            // Estado preservado — sem mutação parcial
            assertThat(wallet.getBrlAvailable()).isEqualByComparingTo(availableAntes);
            assertThat(wallet.getBrlLocked()).isEqualByComparingTo(lockedAntes);
        }

        @Test
        @DisplayName("amount zero: deve lançar IllegalArgumentException sem alterar estado")
        void releaseFunds_zeroAmount_throwsIllegalArgumentException() {
            Wallet wallet = walletWithBrlLocked(new BigDecimal("100.00"));

            assertThatThrownBy(() -> wallet.releaseFunds(AssetType.BRL, BigDecimal.ZERO))
                    .isInstanceOf(IllegalArgumentException.class);

            // Estado não alterado
            assertThat(wallet.getBrlLocked()).isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("amount negativo: deve lançar IllegalArgumentException sem alterar estado")
        void releaseFunds_negativeAmount_throwsIllegalArgumentException() {
            Wallet wallet = walletWithBrlLocked(new BigDecimal("100.00"));

            assertThatThrownBy(() -> wallet.releaseFunds(AssetType.BRL, new BigDecimal("-10.00")))
                    .isInstanceOf(IllegalArgumentException.class);

            // Estado não alterado
            assertThat(wallet.getBrlLocked()).isEqualByComparingTo("100.00");
        }
    }

    // =========================================================================
    // Wallet.create() — factory method e getters completos
    // =========================================================================

    @Nested
    @DisplayName("Wallet.create() e getters")
    class CreateAndGetterTests {

        @Test
        @DisplayName("create(): deve inicializar todos os campos corretamente com saldos locked = ZERO")
        void create_withValidParams_initializesAllFields() {
            UUID userId        = UUID.randomUUID();
            BigDecimal brlInit = new BigDecimal("1000.00");
            BigDecimal vibInit = new BigDecimal("50.00");
            Instant before     = Instant.now().minusMillis(1);

            Wallet wallet = Wallet.create(userId, brlInit, vibInit);

            // Saldos disponíveis
            assertThat(wallet.getBrlAvailable()).isEqualByComparingTo(brlInit);
            assertThat(wallet.getVibAvailable()).isEqualByComparingTo(vibInit);
            // Saldos bloqueados iniciam em ZERO
            assertThat(wallet.getBrlLocked()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(wallet.getVibLocked()).isEqualByComparingTo(BigDecimal.ZERO);
            // UserId preservado (imutável)
            assertThat(wallet.getUserId()).isEqualTo(userId);
            // Timestamps gerados na criação
            assertThat(wallet.getCreatedAt()).isAfterOrEqualTo(before);
            assertThat(wallet.getUpdatedAt()).isAfterOrEqualTo(before);
            // Version é null para instâncias não persistidas
            assertThat(wallet.getVersion()).isNull();
            // Id é null antes da persistência (gerado pelo JPA @GeneratedValue)
            assertThat(wallet.getId()).isNull();
        }

        @Test
        @DisplayName("create() com saldos zero: deve criar carteira zerada")
        void create_withZeroBalances_createsZeroWallet() {
            Wallet wallet = Wallet.create(UUID.randomUUID(), BigDecimal.ZERO, BigDecimal.ZERO);

            assertThat(wallet.getBrlAvailable()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(wallet.getVibAvailable()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(wallet.getBrlLocked()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(wallet.getVibLocked()).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }
}
