package com.vibranium.walletservice.integration;

import com.vibranium.contracts.commands.wallet.ReleaseFundsCommand;
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
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Testa o fluxo completo de liberação compensatória de fundos (Saga).
 *
 * <p>Cobre quatro cenários críticos:</p>
 * <ul>
 *   <li><b>Cenário 1</b> — Happy path: saldo available é restaurado e locked zerado.</li>
 *   <li><b>Cenário 2</b> — Outbox: {@code FundsReleasedEvent} é gravado na mesma TX.</li>
 *   <li><b>Cenário 3</b> — Idempotência: segunda entrega idêntica é skip silencioso.</li>
 *   <li><b>Cenário 4</b> — Wallet não encontrada: {@code FundsReleaseFailedEvent} publicado.</li>
 * </ul>
 *
 * <p>Todos os testes são assíncronos via {@code Awaitility} — o command é enviado
 * via RabbitMQ e o teste aguarda o efeito observável no banco de dados.</p>
 */
@DisplayName("[IT] WalletReleaseFunds — Liberação compensatória de saldo (Saga Choreography)")
class WalletReleaseFundsIntegrationTest extends AbstractIntegrationTest {

    private static final String WALLET_COMMANDS_EXCHANGE  = "wallet.commands";
    private static final String RELEASE_FUNDS_ROUTING_KEY = "wallet.command.release-funds";

    /**
     * Carteira de teste com R$100,00 disponíveis e R$50,00 bloqueados —
     * simulando uma reserva prévia bem-sucedida.
     */
    private Wallet testWallet;

    @BeforeEach
    void setupWallet() {
        // Cria carteira com R$100 disponíveis, depois reserva R$50 para simular
        // o estado pós-reserva: brlAvailable=50, brlLocked=50
        Wallet w = Wallet.create(UUID.randomUUID(), new BigDecimal("100.00"), BigDecimal.ZERO);
        w.reserveFunds(AssetType.BRL, new BigDecimal("50.00")); // available=50, locked=50
        testWallet = walletRepository.save(w);
    }

    // -------------------------------------------------------------------------
    // Cenário 1 — Happy path: saldo available restaurado
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("[Cenário 1] Release bem-sucedido deve restaurar brlAvailable e zerar brlLocked")
    void testReleaseFunds_success_restoresAvailableBalance() throws Exception {
        // testWallet: brlAvailable=50, brlLocked=50
        // Após release de 50: brlAvailable=100, brlLocked=0
        ReleaseFundsCommand cmd = new ReleaseFundsCommand(
                UUID.randomUUID(), UUID.randomUUID(),
                testWallet.getId(), AssetType.BRL,
                new BigDecimal("50.00"), 1
        );

        sendCommand(cmd, UUID.randomUUID().toString());

        await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    Wallet w = walletRepository.findById(testWallet.getId()).orElseThrow();
                    assertThat(w.getBrlAvailable())
                            .as("brlAvailable deve ser restaurado para 100 após release de 50")
                            .isEqualByComparingTo("100.00");
                    assertThat(w.getBrlLocked())
                            .as("brlLocked deve ser zerado após release total")
                            .isEqualByComparingTo("0.00");
                });
    }

    // -------------------------------------------------------------------------
    // Cenário 2 — Outbox: FundsReleasedEvent gravado na mesma TX
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("[Cenário 2] Release bem-sucedido deve publicar FundsReleasedEvent no Outbox")
    void testReleaseFunds_outboxMessage_isPublished() throws Exception {
        // Release parcial de 30 (locked=50, disponível > 0)
        ReleaseFundsCommand cmd = new ReleaseFundsCommand(
                UUID.randomUUID(), UUID.randomUUID(),
                testWallet.getId(), AssetType.BRL,
                new BigDecimal("30.00"), 1
        );

        sendCommand(cmd, UUID.randomUUID().toString());

        await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    var events = outboxMessageRepository.findAll();
                    assertThat(events)
                            .anyMatch(e -> e.getEventType().equals("FundsReleasedEvent"));
                });

        // Valida também que saldo foi atualizado (TX atômica com outbox)
        Wallet w = walletRepository.findById(testWallet.getId()).orElseThrow();
        assertThat(w.getBrlAvailable()).isEqualByComparingTo("80.00"); // 50 + 30
        assertThat(w.getBrlLocked()).isEqualByComparingTo("20.00");    // 50 - 30
    }

    // -------------------------------------------------------------------------
    // Cenário 3 — Idempotência: segunda entrega com mesmo messageId é no-op
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("[Cenário 3] Segunda entrega com mesmo messageId deve ser skip silencioso (idempotência)")
    void testReleaseFunds_idempotency_secondCallIsNoOp() throws Exception {
        String messageId = UUID.randomUUID().toString();
        ReleaseFundsCommand cmd = new ReleaseFundsCommand(
                UUID.randomUUID(), UUID.randomUUID(),
                testWallet.getId(), AssetType.BRL,
                new BigDecimal("50.00"), 1
        );

        // Primeira entrega — deve processar normalmente
        sendCommand(cmd, messageId);

        await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    var events = outboxMessageRepository.findAll();
                    assertThat(events).anyMatch(e -> e.getEventType().equals("FundsReleasedEvent"));
                });

        // Segunda entrega com MESMO messageId — deve ser ignorada silenciosamente
        sendCommand(cmd, messageId);

        // Aguarda tempo para eventual processamento duplicado
        Thread.sleep(2_000);

        long releasedCount = outboxMessageRepository.findAll().stream()
                .filter(e -> e.getEventType().equals("FundsReleasedEvent"))
                .count();

        assertThat(releasedCount)
                .as("Segunda entrega com mesmo messageId não deve gerar novo FundsReleasedEvent")
                .isEqualTo(1);

        // Saldo não deve ter sido alterado uma segunda vez
        Wallet w = walletRepository.findById(testWallet.getId()).orElseThrow();
        assertThat(w.getBrlAvailable()).isEqualByComparingTo("100.00");
        assertThat(w.getBrlLocked()).isEqualByComparingTo("0.00");
    }

    // -------------------------------------------------------------------------
    // Cenário 4 — Wallet não encontrada: FundsReleaseFailedEvent publicado
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("[Cenário 4] Wallet não encontrada deve publicar FundsReleaseFailedEvent no Outbox")
    void testReleaseFunds_walletNotFound_publishesFailedEvent() throws Exception {
        UUID nonExistentWalletId = UUID.randomUUID();
        ReleaseFundsCommand cmd = new ReleaseFundsCommand(
                UUID.randomUUID(), UUID.randomUUID(),
                nonExistentWalletId, AssetType.BRL,
                new BigDecimal("10.00"), 1
        );

        sendCommand(cmd, UUID.randomUUID().toString());

        await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    var events = outboxMessageRepository.findAll();
                    assertThat(events)
                            .anyMatch(e -> e.getEventType().equals("FundsReleaseFailedEvent"));
                });
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private void sendCommand(ReleaseFundsCommand command, String messageId) throws Exception {
        String json = objectMapper.writeValueAsString(command);
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setMessageId(messageId);
        props.setType(ReleaseFundsCommand.class.getName());
        Message message = new Message(json.getBytes(StandardCharsets.UTF_8), props);
        rabbitTemplate.send(WALLET_COMMANDS_EXCHANGE, RELEASE_FUNDS_ROUTING_KEY, message);
    }
}
