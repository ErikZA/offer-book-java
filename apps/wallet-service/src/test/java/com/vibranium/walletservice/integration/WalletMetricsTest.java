package com.vibranium.walletservice.integration;

import com.vibranium.contracts.commands.wallet.ReserveFundsCommand;
import com.vibranium.contracts.commands.wallet.SettleFundsCommand;
import com.vibranium.contracts.enums.AssetType;
import com.vibranium.walletservice.AbstractIntegrationTest;
import com.vibranium.walletservice.application.service.WalletService;
import com.vibranium.walletservice.domain.model.Wallet;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AT-15.2 — Testes de métricas de negócio Micrometer no wallet-service.
 *
 * <p>Valida que as métricas {@code vibranium.funds.reserved},
 * {@code vibranium.funds.settled} e {@code vibranium.outbox.queue.depth}
 * são incrementadas corretamente nos fluxos de negócio.</p>
 */
@DisplayName("WalletMetrics — Métricas de negócio Micrometer (AT-15.2)")
class WalletMetricsTest extends AbstractIntegrationTest {

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private WalletService walletService;

    private UUID buyerWalletId;
    private UUID sellerWalletId;

    @BeforeEach
    void setup() {
        // Cria duas carteiras com saldo para os testes
        UUID buyerUserId  = UUID.randomUUID();
        UUID sellerUserId = UUID.randomUUID();

        Wallet buyer = Wallet.create(buyerUserId, new BigDecimal("10000.00"), BigDecimal.ZERO);
        buyer = walletRepository.save(buyer);
        buyerWalletId = buyer.getId();

        Wallet seller = Wallet.create(sellerUserId, BigDecimal.ZERO, new BigDecimal("100.00"));
        seller = walletRepository.save(seller);
        sellerWalletId = seller.getId();
    }

    // =========================================================================
    // vibranium.funds.reserved
    // =========================================================================

    @Test
    @DisplayName("reserveFunds deve incrementar vibranium.funds.reserved com tag asset=BRL")
    void reserveFunds_shouldIncrementFundsReservedCounter() {
        double before = getCounterValue("vibranium.funds.reserved", "asset", "BRL");

        ReserveFundsCommand cmd = new ReserveFundsCommand(
                UUID.randomUUID(), UUID.randomUUID(), buyerWalletId,
                AssetType.BRL, new BigDecimal("100.00"), 1
        );
        walletService.reserveFunds(cmd, UUID.randomUUID().toString());

        double after = getCounterValue("vibranium.funds.reserved", "asset", "BRL");
        assertThat(after - before).isEqualTo(1.0);
    }

    @Test
    @DisplayName("reserveFunds VIBRANIUM deve incrementar com tag asset=VIBRANIUM")
    void reserveFunds_vibranium_shouldIncrementFundsReservedCounter() {
        double before = getCounterValue("vibranium.funds.reserved", "asset", "VIBRANIUM");

        ReserveFundsCommand cmd = new ReserveFundsCommand(
                UUID.randomUUID(), UUID.randomUUID(), sellerWalletId,
                AssetType.VIBRANIUM, new BigDecimal("10.00"), 1
        );
        walletService.reserveFunds(cmd, UUID.randomUUID().toString());

        double after = getCounterValue("vibranium.funds.reserved", "asset", "VIBRANIUM");
        assertThat(after - before).isEqualTo(1.0);
    }

    // =========================================================================
    // vibranium.funds.settled
    // =========================================================================

    @Test
    @DisplayName("settleFunds deve incrementar vibranium.funds.settled")
    void settleFunds_shouldIncrementFundsSettledCounter() {
        // Reserva fundos primeiro (pré-condição para settlement)
        ReserveFundsCommand reserveBuy = new ReserveFundsCommand(
                UUID.randomUUID(), UUID.randomUUID(), buyerWalletId,
                AssetType.BRL, new BigDecimal("500.00"), 1
        );
        walletService.reserveFunds(reserveBuy, UUID.randomUUID().toString());

        ReserveFundsCommand reserveSell = new ReserveFundsCommand(
                UUID.randomUUID(), UUID.randomUUID(), sellerWalletId,
                AssetType.VIBRANIUM, new BigDecimal("5.00"), 1
        );
        walletService.reserveFunds(reserveSell, UUID.randomUUID().toString());

        double before = getCounterValue("vibranium.funds.settled");

        SettleFundsCommand settleCmd = new SettleFundsCommand(
                UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(),
                buyerWalletId, sellerWalletId,
                new BigDecimal("100.00"), new BigDecimal("5.00"), 1
        );
        walletService.settleFunds(settleCmd, UUID.randomUUID().toString());

        double after = getCounterValue("vibranium.funds.settled");
        assertThat(after - before).isEqualTo(1.0);
    }

    // =========================================================================
    // vibranium.outbox.queue.depth (Gauge)
    // =========================================================================

    @Test
    @DisplayName("Após reserveFunds, vibranium.outbox.queue.depth deve refletir mensagens pendentes")
    void reserveFunds_shouldReflectOutboxDepth() {
        ReserveFundsCommand cmd = new ReserveFundsCommand(
                UUID.randomUUID(), UUID.randomUUID(), buyerWalletId,
                AssetType.BRL, new BigDecimal("100.00"), 1
        );
        walletService.reserveFunds(cmd, UUID.randomUUID().toString());

        double depth = getGaugeValue("vibranium.outbox.queue.depth");
        assertThat(depth).isGreaterThanOrEqualTo(1.0);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private double getCounterValue(String name, String tagKey, String tagValue) {
        var counter = meterRegistry.find(name).tag(tagKey, tagValue).counter();
        return counter != null ? counter.count() : 0.0;
    }

    private double getCounterValue(String name) {
        var counter = meterRegistry.find(name).counter();
        return counter != null ? counter.count() : 0.0;
    }

    private double getGaugeValue(String name) {
        var gauge = meterRegistry.find(name).gauge();
        return gauge != null ? gauge.value() : 0.0;
    }
}
