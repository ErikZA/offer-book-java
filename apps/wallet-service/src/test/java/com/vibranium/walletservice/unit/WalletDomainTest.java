package com.vibranium.walletservice.unit;

import com.vibranium.contracts.enums.AssetType;
import com.vibranium.walletservice.domain.model.Wallet;
import com.vibranium.walletservice.exception.InsufficientFundsException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
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
}
