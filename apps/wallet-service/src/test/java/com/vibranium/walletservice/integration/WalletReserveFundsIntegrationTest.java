package com.vibranium.walletservice.integration;

import com.vibranium.contracts.commands.wallet.ReserveFundsCommand;
import com.vibranium.contracts.enums.AssetType;
import com.vibranium.walletservice.AbstractIntegrationTest;
import com.vibranium.walletservice.domain.model.Wallet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * FASE RED — Testa a regra de negócio de reserva de fundos (lock).
 *
 * <p>Cobre três cenários críticos:</p>
 * <ul>
 *   <li><b>Cenário A</b> — Saldo insuficiente: deve gerar {@code FundsReservationFailedEvent} no Outbox.</li>
 *   <li><b>Cenário B</b> — Saldo suficiente: deve reservar o saldo e salvar {@code FundsReservedEvent} no Outbox.</li>
 *   <li><b>Cenário C</b> — Concorrência: duas threads simultâneas, apenas uma deve ter sucesso (Pessimistic Lock).</li>
 * </ul>
 *
 * <p><b>RED:</b> Todos os testes falharão até que o listener, serviço e
 * entidades estejam implementados (Fase Green).</p>
 */
@DisplayName("[RED] WalletReserveFunds - Lock de saldos com concorrência e Outbox")
class WalletReserveFundsIntegrationTest extends AbstractIntegrationTest {

    private static final String WALLET_COMMANDS_EXCHANGE = "wallet.commands";
    private static final String RESERVE_FUNDS_ROUTING_KEY = "wallet.command.reserve-funds";

    /** Carteira de teste com R$ 100,00 e 50 VIB disponíveis. */
    private Wallet testWallet;
    private UUID testUserId;

    @BeforeEach
    void setupWallet() {
        // Persiste uma carteira com saldo para uso nos cenários
        testUserId = UUID.randomUUID();
        testWallet = walletRepository.save(Wallet.create(testUserId, new BigDecimal("100.00"), new BigDecimal("50")));
    }

    // -------------------------------------------------------------------------
    // Cenário A — Insufficient Funds
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("[Cenário A] Saldo insuficiente deve gerar FundsReservationFailedEvent no Outbox")
    void scenarioA_insufficientFunds_shouldPublishFailedEventToOutbox() throws Exception {
        // Arrange — tenta reservar R$ 200 com apenas R$ 100 disponíveis
        ReserveFundsCommand command = new ReserveFundsCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),        // orderId
                testWallet.getId(),       // walletId
                AssetType.BRL,
                new BigDecimal("200.00"), 1 // valor > disponível
        );

        sendCommand(command, UUID.randomUUID().toString());

        // Assert — deve gerar evento de falha no Outbox
        await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    var events = outboxMessageRepository.findAll();
                    assertThat(events)
                            .anyMatch(e -> e.getEventType().equals("FundsReservationFailedEvent"));
                });

        // Assert — saldo NÃO deve ter sido alterado
        var wallet = walletRepository.findById(testWallet.getId()).orElseThrow();
        assertThat(wallet.getBrlAvailable()).isEqualByComparingTo("100.00");
        assertThat(wallet.getBrlLocked()).isEqualByComparingTo("0");
    }

    // -------------------------------------------------------------------------
    // Cenário B — Sufficient Funds
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("[Cenário B] Saldo suficiente deve bloquear saldo e gerar FundsReservedEvent no Outbox")
    void scenarioB_sufficientFunds_shouldLockBalanceAndPublishReservedEventToOutbox() throws Exception {
        // Arrange — reserva R$ 60 com R$ 100 disponíveis
        UUID orderId = UUID.randomUUID();
        ReserveFundsCommand command = new ReserveFundsCommand(
                UUID.randomUUID(),
                orderId,
                testWallet.getId(),
                AssetType.BRL,
                new BigDecimal("60.00"), 1
        );

        sendCommand(command, UUID.randomUUID().toString());

        // Assert — saldo deve ter sido transferido de available → locked
        await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    var wallet = walletRepository.findById(testWallet.getId()).orElseThrow();
                    assertThat(wallet.getBrlAvailable()).isEqualByComparingTo("40.00");
                    assertThat(wallet.getBrlLocked()).isEqualByComparingTo("60.00");
                });

        // Assert — evento de sucesso deve estar no Outbox
        await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    var events = outboxMessageRepository.findAll();
                    assertThat(events)
                            .anyMatch(e -> e.getEventType().equals("FundsReservedEvent"));
                });
    }

    @Test
    @DisplayName("[Cenário B] Reserva de Vibranium deve bloquear VIB corretamente")
    void scenarioB_sufficientVibranium_shouldLockVibBalance() throws Exception {
        // Arrange — reserva 20 VIB com 50 disponíveis
        ReserveFundsCommand command = new ReserveFundsCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                testWallet.getId(),
                AssetType.VIBRANIUM,
                new BigDecimal("20"), 1
        );

        sendCommand(command, UUID.randomUUID().toString());

        await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    var wallet = walletRepository.findById(testWallet.getId()).orElseThrow();
                    assertThat(wallet.getVibAvailable()).isEqualByComparingTo("30");
                    assertThat(wallet.getVibLocked()).isEqualByComparingTo("20");
                });
    }

    // -------------------------------------------------------------------------
    // Cenário C — Concorrência / Pessimistic Lock
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("[Cenário C] Duas threads simultâneas: apenas uma deve reservar R$80, a outra deve falhar")
    void scenarioC_concurrentReservations_pessimisticLockShouldPreventRaceCondition()
            throws InterruptedException {
        // Arrange — cada thread tenta reservar R$80 (total R$160 > R$100 disponíveis)
        int threadCount = 2;
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        List<Exception> exceptions = new ArrayList<>();

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int threadIdx = i;
            executor.submit(() -> {
                try {
                    startGate.await(); // aguarda o sinal de partida simultânea
                    ReserveFundsCommand command = new ReserveFundsCommand(
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            testWallet.getId(),
                            AssetType.BRL,
                            new BigDecimal("80.00"), 1
                    );
                    sendCommand(command, UUID.randomUUID().toString());
                } catch (Exception e) {
                    synchronized (exceptions) {
                        exceptions.add(e);
                    }
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Act — dispara as threads simultaneamente
        startGate.countDown();
        doneLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        // Assert — aguarda o processamento assíncrono
        await()
                .atMost(15, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    var events = outboxMessageRepository.findAll();
                    long reservedCount = events.stream()
                            .filter(e -> e.getEventType().equals("FundsReservedEvent"))
                            .count();
                    long failedCount = events.stream()
                            .filter(e -> e.getEventType().equals("FundsReservationFailedEvent"))
                            .count();

                    // Deve haver exatamente 1 sucesso e 1 falha
                    assertThat(reservedCount).isEqualTo(1);
                    assertThat(failedCount).isEqualTo(1);
                });

        // Assert — saldo final: R$20 disponível, R$80 bloqueado (apenas 1 reserva passou)
        var wallet = walletRepository.findById(testWallet.getId()).orElseThrow();
        assertThat(wallet.getBrlAvailable()).isEqualByComparingTo("20.00");
        assertThat(wallet.getBrlLocked()).isEqualByComparingTo("80.00");

        // Sem exceções não tratadas nas threads
        assertThat(exceptions).isEmpty();
    }

    @Test
    @DisplayName("[Cenário C] 10 threads simultâneas, saldo único de R$100: apenas reservas que cabem passam")
    void scenarioC_tenConcurrentThreadsTryingToReserveR100_onlyFitPassAsync()
            throws InterruptedException {
        // Arrange — 10 threads tentando reservar R$15 cada (total R$150 > R$100)
        int threadCount = 10;
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    ReserveFundsCommand command = new ReserveFundsCommand(
                            UUID.randomUUID(), UUID.randomUUID(),
                            testWallet.getId(), AssetType.BRL,
                            new BigDecimal("15.00"), 1
                    );
                    sendCommand(command, UUID.randomUUID().toString());
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startGate.countDown();
        doneLatch.await(20, TimeUnit.SECONDS);
        executor.shutdown();

        // Assert — a soma de tudo reservado não pode ultrapassar R$100
        await()
                .atMost(20, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    var wallet = walletRepository.findById(testWallet.getId()).orElseThrow();
                    // brlAvailable + brlLocked deve sempre ser exatamente R$100
                    assertThat(wallet.getBrlAvailable().add(wallet.getBrlLocked()))
                            .isEqualByComparingTo("100.00");

                    // Saldo disponível nunca deve ser negativo
                    assertThat(wallet.getBrlAvailable()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
                });
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private void sendCommand(ReserveFundsCommand command, String messageId) throws Exception {
        String json = objectMapper.writeValueAsString(command);
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setMessageId(messageId);
        props.setType(ReserveFundsCommand.class.getName());
        Message message = new Message(json.getBytes(StandardCharsets.UTF_8), props);
        rabbitTemplate.send(WALLET_COMMANDS_EXCHANGE, RESERVE_FUNDS_ROUTING_KEY, message);
    }
}
