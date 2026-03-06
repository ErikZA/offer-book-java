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
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * AT-09 — Testa que prefetch=10 e concurrency permitem processar 100 mensagens
 * de 100 wallets diferentes em tempo razoável (<20s), sem erros de concorrência.
 *
 * <p>Com prefetch=1, cada mensagem seria processada sequencialmente: ~100s para 100ms/msg.
 * Com prefetch=10 e concurrency > 1, o consumer recebe até 10 mensagens por thread
 * e pode processar em paralelo, reduzindo o tempo total significativamente.</p>
 *
 * <p><b>FASE RED:</b> estes testes passarão após ajustar prefetch e concurrency
 * no application.yaml e no container factory.</p>
 */
@DisplayName("[Integration] AT-09 — Prefetch=10 com 100 ReserveFundsCommands de 100 wallets")
class WalletPrefetchConcurrencyTest extends AbstractIntegrationTest {

    private static final String EXCHANGE = "vibranium.commands";
    private static final String ROUTING_KEY = "wallet.commands.reserve-funds";

    private final List<Wallet> wallets = new ArrayList<>();

    @BeforeEach
    void createWallets() {
        // Cria 100 wallets diferentes, cada uma com R$ 1000 disponível
        for (int i = 0; i < 100; i++) {
            Wallet wallet = walletRepository.save(
                    Wallet.create(UUID.randomUUID(), new BigDecimal("1000.00"), new BigDecimal("100"))
            );
            wallets.add(wallet);
        }
    }

    @Test
    @DisplayName("100 ReserveFundsCommands de 100 wallets diferentes — todas processadas em < 20s, zero erros")
    void shouldProcess100MessagesFromDifferentWalletsWithinTimeLimit() throws Exception {
        // Arrange — publica 100 comandos, um por wallet
        for (int i = 0; i < 100; i++) {
            Wallet wallet = wallets.get(i);
            ReserveFundsCommand cmd = new ReserveFundsCommand(
                    UUID.randomUUID(),   // correlationId
                    UUID.randomUUID(),   // orderId
                    wallet.getId(),      // walletId — wallet própria
                    AssetType.BRL,
                    new BigDecimal("50.00"),
                    1
            );
            sendCommand(cmd, UUID.randomUUID().toString());
        }

        // Assert — todas as 100 mensagens processadas (reserva ou falha) em até 20s
        await()
                .atMost(20, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    long totalOutboxMessages = outboxMessageRepository.count();
                    // Cada comando gera exatamente 1 evento no outbox (Reserved ou Failed)
                    assertThat(totalOutboxMessages)
                            .as("Todas as 100 mensagens devem gerar evento no outbox")
                            .isEqualTo(100);
                });

        // Assert — zero erros de concorrência: saldo de cada wallet consistente
        for (Wallet original : wallets) {
            Wallet updated = walletRepository.findById(original.getId()).orElseThrow();
            // Saldo total (available + locked) deve ser preservado
            BigDecimal totalBrl = updated.getBrlAvailable().add(updated.getBrlLocked());
            assertThat(totalBrl)
                    .as("Invariante: brlAvailable + brlLocked == saldo inicial para wallet %s", original.getId())
                    .isEqualByComparingTo("1000.00");
        }

        // Assert — todas reservaram com sucesso (cada wallet tem saldo suficiente)
        long reservedCount = outboxMessageRepository.findAll().stream()
                .filter(e -> e.getEventType().equals("FundsReservedEvent"))
                .count();
        assertThat(reservedCount).isEqualTo(100);
    }

    @Test
    @DisplayName("100 ReserveFundsCommands — todas as chaves de idempotência registradas (sem duplicatas)")
    void shouldRegisterAllIdempotencyKeys() throws Exception {
        // Arrange — publica 100 comandos
        for (int i = 0; i < 100; i++) {
            Wallet wallet = wallets.get(i);
            ReserveFundsCommand cmd = new ReserveFundsCommand(
                    UUID.randomUUID(), UUID.randomUUID(),
                    wallet.getId(), AssetType.BRL,
                    new BigDecimal("50.00"), 1
            );
            sendCommand(cmd, UUID.randomUUID().toString());
        }

        // Assert — 100 chaves de idempotência únicas
        await()
                .atMost(20, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    long idempotencyCount = idempotencyKeyRepository.count();
                    assertThat(idempotencyCount)
                            .as("Deve ter 100 idempotency keys registradas")
                            .isEqualTo(100);
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
        rabbitTemplate.send(EXCHANGE, ROUTING_KEY, message);
    }
}
