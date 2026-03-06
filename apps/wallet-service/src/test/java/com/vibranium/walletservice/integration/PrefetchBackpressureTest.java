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
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * AT-09 — Testa backpressure: o RabbitMQ não entrega mais mensagens que o
 * prefetch configurado. Publica 1000 mensagens e verifica que o processamento
 * ocorre de forma controlada sem OOM ou starvation.
 *
 * <p>Com prefetch=10, o broker entrega no máximo 10 mensagens por consumer thread
 * antes de aguardar ACKs. Isso evita que o consumer seja inundado de mensagens
 * e garante backpressure natural.</p>
 *
 * <p><b>FASE RED:</b> passará após prefetch=10 e concurrency configurados.</p>
 */
@DisplayName("[Integration] AT-09 — Prefetch Backpressure (1000 mensagens, sem OOM)")
class PrefetchBackpressureTest extends AbstractIntegrationTest {

    private static final String EXCHANGE = "vibranium.commands";
    private static final String ROUTING_KEY = "wallet.commands.reserve-funds";

    @BeforeEach
    void createWallets() {
        // Cria 1000 wallets para evitar concorrência na mesma wallet
        for (int i = 0; i < 1000; i++) {
            walletRepository.save(
                    Wallet.create(UUID.randomUUID(), new BigDecimal("10000.00"), new BigDecimal("1000"))
            );
        }
    }

    @Test
    @DisplayName("1000 mensagens processadas sem OOM — backpressure via prefetch funciona")
    void thousandMessages_processedWithoutOOM() throws Exception {
        var allWallets = walletRepository.findAll();
        assertThat(allWallets).hasSize(1000);

        // Arrange — publica 1000 mensagens, uma por wallet
        for (int i = 0; i < 1000; i++) {
            ReserveFundsCommand cmd = new ReserveFundsCommand(
                    UUID.randomUUID(), UUID.randomUUID(),
                    allWallets.get(i).getId(), AssetType.BRL,
                    new BigDecimal("5.00"), 1
            );
            sendCommand(cmd, UUID.randomUUID().toString());
        }

        // Assert — todas as 1000 mensagens processadas (pode demorar mais com backpressure)
        await()
                .atMost(60, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    long totalOutbox = outboxMessageRepository.count();
                    assertThat(totalOutbox)
                            .as("Todas as 1000 mensagens devem gerar evento no outbox")
                            .isEqualTo(1000);
                });

        // Assert — 1000 idempotency keys registradas
        long idempotencyCount = idempotencyKeyRepository.count();
        assertThat(idempotencyCount).isEqualTo(1000);

        // Assert — sem OOM: JVM não crashou e o teste chegou até aqui
        // Verifica que a memória heap está dentro de limites aceitáveis
        Runtime runtime = Runtime.getRuntime();
        long usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        assertThat(usedMb)
                .as("Heap usage deve estar dentro de limites razoáveis (< 512MB)")
                .isLessThan(512);
    }

    @Test
    @DisplayName("Fila reserve-funds esvaziada após processamento completo")
    void queueShouldBeEmptyAfterProcessing() throws Exception {
        var allWallets = walletRepository.findAll();
        int messageCount = 100; // Subset menor para verificar esvaziamento da fila

        // Arrange — publica 100 mensagens
        for (int i = 0; i < messageCount; i++) {
            ReserveFundsCommand cmd = new ReserveFundsCommand(
                    UUID.randomUUID(), UUID.randomUUID(),
                    allWallets.get(i).getId(), AssetType.BRL,
                    new BigDecimal("5.00"), 1
            );
            sendCommand(cmd, UUID.randomUUID().toString());
        }

        // Assert — aguarda processamento completo
        await()
                .atMost(30, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    long totalOutbox = outboxMessageRepository.count();
                    assertThat(totalOutbox).isEqualTo(messageCount);
                });

        // Assert — fila esvaziada (todas as mensagens ACKed)
        await()
                .atMost(5, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    var queueInfo = rabbitAdmin.getQueueInfo("wallet.commands.reserve-funds");
                    assertThat(queueInfo).isNotNull();
                    assertThat(queueInfo.getMessageCount())
                            .as("Fila deve estar vazia após processamento")
                            .isZero();
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
