package com.vibranium.walletservice.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes de cobertura para os métodos package-private do agregado {@link Wallet}.
 *
 * <p><strong>Motivo de existência:</strong> O método de ciclo de vida JPA
 * {@code touch()} é package-private e não pode ser acessado a partir do
 * pacote {@code unit}. Esta classe reside no mesmo pacote que {@link Wallet}
 * para exercitá-lo diretamente, garantindo cobertura Jacoco.</p>
 *
 * <p><strong>Escopo:</strong> sem Spring, sem banco — roda em &lt; 1 segundo.</p>
 */
@DisplayName("Wallet — Métodos de ciclo de vida JPA (package-private)")
class WalletLifecycleTest {

    /**
     * Verifica que {@code touch()} (callback {@code @PreUpdate}) atualiza
     * {@code updatedAt} para o instante atual.
     *
     * <p>Este método é chamado automaticamente pelo JPA antes de cada
     * {@code UPDATE}. O teste garante que a invariante de rastreamento temporal
     * esteja correta antes de qualquer persistência.</p>
     */
    @Test
    @DisplayName("touch(): deve atualizar updatedAt para o instante atual (@PreUpdate)")
    void touch_updatesUpdatedAt() {
        Wallet wallet = Wallet.create(UUID.randomUUID(), new BigDecimal("100.00"), BigDecimal.ZERO);
        Instant before = Instant.now().minusMillis(1);

        // Chama o callback @PreUpdate diretamente (package-private)
        wallet.touch();

        assertThat(wallet.getUpdatedAt()).isAfterOrEqualTo(before);
    }

    @Test
    @DisplayName("touch(): deve preservar todos os saldos ao atualizar timestamp")
    void touch_preservesAllBalances() {
        BigDecimal brl = new BigDecimal("250.00");
        BigDecimal vib = new BigDecimal("5.00");
        Wallet wallet  = Wallet.create(UUID.randomUUID(), brl, vib);

        wallet.touch();

        assertThat(wallet.getBrlAvailable()).isEqualByComparingTo(brl);
        assertThat(wallet.getVibAvailable()).isEqualByComparingTo(vib);
        assertThat(wallet.getBrlLocked()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(wallet.getVibLocked()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
