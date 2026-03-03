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
 * FASE RED — Testa a idempotência do processamento de comandos via RabbitMQ.
 *
 * <p>O RabbitMQ usa entrega at-least-once: a mesma mensagem pode chegar
 * mais de uma vez. A tabela {@code idempotency_key} garante que o
 * processamento ocorre exatamente uma vez por {@code messageId}.</p>
 *
 * <p><b>RED:</b> Falharão até a Fase Green.</p>
 */
@DisplayName("[RED] WalletIdempotency - Proteção contra processamento duplicado de mensagens")
class WalletIdempotencyIntegrationTest extends AbstractIntegrationTest {

    private static final String WALLET_COMMANDS_EXCHANGE = "wallet.commands";
    private static final String RESERVE_FUNDS_ROUTING_KEY = "wallet.command.reserve-funds";

    private Wallet testWallet;

    @BeforeEach
    void setup() {
        testWallet = walletRepository.save(
                Wallet.create(UUID.randomUUID(), new BigDecimal("500.00"), new BigDecimal("100"))
        );
    }

    // -------------------------------------------------------------------------
    // Testes de Idempotência
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Mesma mensagem entregue 3 vezes deve processar apenas 1 vez (idempotência por messageId)")
    void sameMessageDelivered3Times_shouldProcessOnlyOnce() throws Exception {
        // Arrange — mesmo messageId nas 3 entregas
        String fixedMessageId = UUID.randomUUID().toString();
        UUID orderId = UUID.randomUUID();
        ReserveFundsCommand command = new ReserveFundsCommand(
                UUID.randomUUID(), orderId,
                testWallet.getId(), AssetType.BRL,
                new BigDecimal("50.00"), 1
        );
        String json = objectMapper.writeValueAsString(command);

        // Act — entrega 3 vezes
        for (int i = 0; i < 3; i++) {
            MessageProperties props = new MessageProperties();
            props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            props.setMessageId(fixedMessageId); // mesma chave!
            Message message = new Message(json.getBytes(StandardCharsets.UTF_8), props);
            rabbitTemplate.send(WALLET_COMMANDS_EXCHANGE, RESERVE_FUNDS_ROUTING_KEY, message);
        }

        // Assert — apenas 1 evento no Outbox
        await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    long eventCount = outboxMessageRepository.countByEventType("FundsReservedEvent");
                    assertThat(eventCount).isEqualTo(1);
                });

        // Assert — saldo alterado apenas uma vez (R$500 - R$50 = R$450)
        var wallet = walletRepository.findById(testWallet.getId()).orElseThrow();
        assertThat(wallet.getBrlAvailable()).isEqualByComparingTo("450.00");
        assertThat(wallet.getBrlLocked()).isEqualByComparingTo("50.00");
    }

    @Test
    @DisplayName("Mensagens com messageIds diferentes devem ser processadas independentemente")
    void differentMessageIds_shouldBeProcessedIndependently() throws Exception {
        // Arrange — dois comandos com messageIds distintos reservando R$50 cada
        ReserveFundsCommand command1 = new ReserveFundsCommand(
                UUID.randomUUID(), UUID.randomUUID(),
                testWallet.getId(), AssetType.BRL, new BigDecimal("50.00"), 1
        );
        ReserveFundsCommand command2 = new ReserveFundsCommand(
                UUID.randomUUID(), UUID.randomUUID(),
                testWallet.getId(), AssetType.BRL, new BigDecimal("50.00"), 1
        );

        // Act
        sendCommand(command1, UUID.randomUUID().toString());
        sendCommand(command2, UUID.randomUUID().toString());

        // Assert — 2 eventos no Outbox, R$400 disponíveis, R$100 bloqueados
        await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    long count = outboxMessageRepository.countByEventType("FundsReservedEvent");
                    assertThat(count).isEqualTo(2);
                });

        var wallet = walletRepository.findById(testWallet.getId()).orElseThrow();
        assertThat(wallet.getBrlAvailable()).isEqualByComparingTo("400.00");
        assertThat(wallet.getBrlLocked()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("Mensagem sem messageId deve ser rejeitada com NACK sem requeue")
    void messageWithoutMessageId_shouldBeNackedWithoutRequeue() throws Exception {
        // Arrange — mensagem sem messageId definido
        ReserveFundsCommand command = new ReserveFundsCommand(
                UUID.randomUUID(), UUID.randomUUID(),
                testWallet.getId(), AssetType.BRL, new BigDecimal("50.00"), 1
        );
        String json = objectMapper.writeValueAsString(command);

        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        // messageId propositalmente ausente
        Message message = new Message(json.getBytes(StandardCharsets.UTF_8), props);
        rabbitTemplate.send(WALLET_COMMANDS_EXCHANGE, RESERVE_FUNDS_ROUTING_KEY, message);

        // Assert — saldo não deve ser alterado (mensagem foi rejeitada)
        await()
                .during(3, TimeUnit.SECONDS)
                .atMost(4, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    var wallet = walletRepository.findById(testWallet.getId()).orElseThrow();
                    assertThat(wallet.getBrlAvailable()).isEqualByComparingTo("500.00");
                    assertThat(wallet.getBrlLocked()).isEqualByComparingTo("0");
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
