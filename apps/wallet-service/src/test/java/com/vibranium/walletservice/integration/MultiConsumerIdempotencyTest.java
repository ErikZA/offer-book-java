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
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * AT-09 — Testa que multi-consumer (2+ container factories na mesma fila)
 * processam mensagens exatamente 1x graças à idempotência por tabela.
 *
 * <p>Em produção, múltiplas instâncias do wallet-service consumirão da mesma fila
 * via round-robin nativo do RabbitMQ. O RabbitMQ garante que cada mensagem
 * é entregue a exatamente UM consumer por vez. Porém, em cenários de redelivery
 * (NACK + requeue, network partition), a mesma mensagem pode ser entregue a
 * instâncias diferentes. A idempotência por {@code messageId} na tabela
 * {@code tb_idempotency_key} protege contra processamento duplicado.</p>
 *
 * <p>Este teste simula o cenário publicando 50 mensagens únicas e verificando
 * que cada uma gera exatamente 1 evento no outbox — sem duplicatas.</p>
 *
 * <p><b>FASE RED:</b> este teste passará com prefetch=10 e idempotência habilitada.</p>
 */
@DisplayName("[Integration] AT-09 — Multi-Consumer Idempotency (50 mensagens, zero duplicatas)")
class MultiConsumerIdempotencyTest extends AbstractIntegrationTest {

    private static final String EXCHANGE = "vibranium.commands";
    private static final String ROUTING_KEY = "wallet.commands.reserve-funds";

    private Wallet testWallet;

    @BeforeEach
    void setupWallet() {
        // Wallet com saldo alto para absorver todas as 50 reservas
        testWallet = walletRepository.save(
                Wallet.create(UUID.randomUUID(), new BigDecimal("100000.00"), new BigDecimal("100000"))
        );
    }

    @Test
    @DisplayName("50 mensagens únicas processadas exatamente 1x — IdempotencyKey garante deduplicação")
    void fiftyUniqueMessages_eachProcessedExactlyOnce() throws Exception {
        int messageCount = 50;

        // Arrange — publica 50 mensagens únicas
        for (int i = 0; i < messageCount; i++) {
            ReserveFundsCommand cmd = new ReserveFundsCommand(
                    UUID.randomUUID(), UUID.randomUUID(),
                    testWallet.getId(), AssetType.BRL,
                    new BigDecimal("10.00"), 1
            );
            sendCommand(cmd, UUID.randomUUID().toString());
        }

        // Assert — exatamente 50 eventos gerados (1 por mensagem)
        await()
                .atMost(20, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    long outboxCount = outboxMessageRepository.count();
                    assertThat(outboxCount)
                            .as("Cada mensagem deve gerar exatamente 1 evento no outbox")
                            .isEqualTo(messageCount);
                });

        // Assert — 50 chaves de idempotência (1 por messageId)
        long idempotencyCount = idempotencyKeyRepository.count();
        assertThat(idempotencyCount).isEqualTo(messageCount);

        // Assert — todos os 50 são FundsReservedEvent (saldo era suficiente)
        long reservedCount = outboxMessageRepository.findAll().stream()
                .filter(e -> e.getEventType().equals("FundsReservedEvent"))
                .count();
        assertThat(reservedCount).isEqualTo(messageCount);
    }

    @Test
    @DisplayName("Mensagens duplicadas (mesmo messageId) — processadas exatamente 1x")
    void duplicateMessages_processedExactlyOnce() throws Exception {
        String sharedMessageId = UUID.randomUUID().toString();

        // Arrange — publica a MESMA mensagem (mesmo messageId) 5 vezes
        for (int i = 0; i < 5; i++) {
            ReserveFundsCommand cmd = new ReserveFundsCommand(
                    UUID.randomUUID(), UUID.randomUUID(),
                    testWallet.getId(), AssetType.BRL,
                    new BigDecimal("10.00"), 1
            );
            sendCommand(cmd, sharedMessageId);
        }

        // Assert — apenas 1 evento no outbox (idempotência descarta duplicatas)
        await()
                .atMost(15, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    long outboxCount = outboxMessageRepository.count();
                    assertThat(outboxCount)
                            .as("Mensagens com mesmo messageId devem gerar apenas 1 evento")
                            .isEqualTo(1);
                });

        // Assert — somente 1 chave de idempotência
        long idempotencyCount = idempotencyKeyRepository.count();
        assertThat(idempotencyCount).isEqualTo(1);

        // Assert — saldo reservado apenas 1x
        Wallet updated = walletRepository.findById(testWallet.getId()).orElseThrow();
        assertThat(updated.getBrlLocked()).isEqualByComparingTo("10.00");
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
