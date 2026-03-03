package com.vibranium.walletservice.integration;

import com.vibranium.contracts.commands.wallet.SettleFundsCommand;
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
 * Testa a liquidação de fundos após um match executado.
 *
 * <p>O {@code SettleFundsCommand} é disparado pelo {@code order-service} após o motor
 * de match cruzar uma compra com uma venda. O wallet-service deve:
 * <ul>
 *   <li>Remover BRL bloqueado do comprador e adicionar VIB disponível.</li>
 *   <li>Remover VIB bloqueado do vendedor e adicionar BRL disponível.</li>
 *   <li>Salvar {@code FundsSettledEvent} no Outbox na mesma transação.</li>
 * </ul>
 *
 * <p>Setup usa métodos de domínio ({@code reserveFunds}) para construir o estado
 * pre-condição — sem setters diretos, respeitando as invariantes do agregado.</p>
 */
@DisplayName("WalletSettleFunds - Liquidação pós-match com atomicidade garantida")
class WalletSettleFundsIntegrationTest extends AbstractIntegrationTest {

    private static final String WALLET_COMMANDS_EXCHANGE = "wallet.commands";
    private static final String SETTLE_FUNDS_ROUTING_KEY = "wallet.command.settle-funds";

    private Wallet buyerWallet;
    private Wallet sellerWallet;
    private UUID buyOrderId;
    private UUID sellOrderId;

    @BeforeEach
    void setupWallets() {
        // Comprador: cria com R$200 disponíveis e reserva via domínio — saldo fica locked
        buyerWallet = Wallet.create(UUID.randomUUID(), new BigDecimal("200.00"), BigDecimal.ZERO);
        buyerWallet.reserveFunds(AssetType.BRL, new BigDecimal("200.00"));
        buyerWallet = walletRepository.save(buyerWallet);

        // Vendedor: cria com 10 VIB disponíveis e reserva via domínio — saldo fica locked
        sellerWallet = Wallet.create(UUID.randomUUID(), BigDecimal.ZERO, new BigDecimal("10"));
        sellerWallet.reserveFunds(AssetType.VIBRANIUM, new BigDecimal("10"));
        sellerWallet = walletRepository.save(sellerWallet);

        buyOrderId = UUID.randomUUID();
        sellOrderId = UUID.randomUUID();
    }

    // -------------------------------------------------------------------------
    // Happy Path
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Liquidação bem-sucedida: comprador recebe VIB, vendedor recebe BRL")
    void shouldSettleFundsCorrectlyForBothParties() throws Exception {
        // Arrange — match de 10 VIB a R$20 cada (total R$200)
        SettleFundsCommand command = new SettleFundsCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),       // matchId
                buyOrderId,
                sellOrderId,
                buyerWallet.getId(),
                sellerWallet.getId(),
                new BigDecimal("20.00"), // matchPrice
                new BigDecimal("10"), 1  // matchAmount (VIB)
        );

        sendCommand(command, UUID.randomUUID().toString());

        // Assert — comprador: BRL locked decrementado, VIB available incrementado
        await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    var buyer = walletRepository.findById(buyerWallet.getId()).orElseThrow();
                    assertThat(buyer.getBrlLocked()).isEqualByComparingTo("0.00");
                    assertThat(buyer.getVibAvailable()).isEqualByComparingTo("10");
                });

        // Assert — vendedor: VIB locked decrementado, BRL available incrementado
        await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    var seller = walletRepository.findById(sellerWallet.getId()).orElseThrow();
                    assertThat(seller.getVibLocked()).isEqualByComparingTo("0");
                    assertThat(seller.getBrlAvailable()).isEqualByComparingTo("200.00");
                });
    }

    @Test
    @DisplayName("Deve gravar FundsSettledEvent no Outbox na mesma transação da liquidação")
    void shouldPersistFundsSettledEventInOutbox() throws Exception {
        UUID matchId = UUID.randomUUID();
        SettleFundsCommand command = new SettleFundsCommand(
                UUID.randomUUID(), matchId, buyOrderId, sellOrderId,
                buyerWallet.getId(), sellerWallet.getId(),
                new BigDecimal("20.00"), new BigDecimal("10"), 1
        );

        sendCommand(command, UUID.randomUUID().toString());

        await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    var events = outboxMessageRepository.findAll();
                    assertThat(events)
                            .anyMatch(e -> e.getEventType().equals("FundsSettledEvent"));
                });
    }

    // -------------------------------------------------------------------------
    // Cenário de Falha
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Liquidação com carteira compradora inexistente deve gerar FundsSettlementFailedEvent")
    void shouldGenerateFailedEventWhenBuyerWalletNotFound() throws Exception {
        // Arrange — buyerWalletId inválido
        SettleFundsCommand command = new SettleFundsCommand(
                UUID.randomUUID(), UUID.randomUUID(), buyOrderId, sellOrderId,
                UUID.randomUUID(), // walletId inexistente
                sellerWallet.getId(),
                new BigDecimal("20.00"), new BigDecimal("10"), 1
        );

        sendCommand(command, UUID.randomUUID().toString());

        await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    var events = outboxMessageRepository.findAll();
                    assertThat(events)
                            .anyMatch(e -> e.getEventType().equals("FundsSettlementFailedEvent"));
                });
    }

    @Test
    @DisplayName("Liquidação com saldo bloqueado insuficiente (inconsistência) deve gerar falha e não alterar saldos")
    void shouldFailAndNotAlterBalancesWhenLockedFundsAreInsufficient() throws Exception {
        // Arrange — recria comprador com apenas R$50 bloqueados, mas o match exige R$200
        // Usa reserveFunds() para construir o estado via domínio (sem setters diretos)
        Wallet lowBuyerWallet = Wallet.create(UUID.randomUUID(), new BigDecimal("50.00"), BigDecimal.ZERO);
        lowBuyerWallet.reserveFunds(AssetType.BRL, new BigDecimal("50.00"));
        lowBuyerWallet = walletRepository.save(lowBuyerWallet);

        SettleFundsCommand command = new SettleFundsCommand(
                UUID.randomUUID(), UUID.randomUUID(), buyOrderId, sellOrderId,
                lowBuyerWallet.getId(), sellerWallet.getId(),
                new BigDecimal("20.00"), new BigDecimal("10"), 1 // total = R$200
        );

        sendCommand(command, UUID.randomUUID().toString());

        // Assert — saldo do comprador permanece inalterado após a falha
        final UUID lowBuyerId = lowBuyerWallet.getId();
        await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    var buyer = walletRepository.findById(lowBuyerId).orElseThrow();
                    assertThat(buyer.getBrlLocked()).isEqualByComparingTo("50.00");
                    assertThat(buyer.getVibAvailable()).isEqualByComparingTo("0");

                    var events = outboxMessageRepository.findAll();
                    assertThat(events)
                            .anyMatch(e -> e.getEventType().equals("FundsSettlementFailedEvent"));
                });
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private void sendCommand(SettleFundsCommand command, String messageId) throws Exception {
        String json = objectMapper.writeValueAsString(command);
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setMessageId(messageId);
        props.setType(SettleFundsCommand.class.getName());
        Message message = new Message(json.getBytes(StandardCharsets.UTF_8), props);
        rabbitTemplate.send(WALLET_COMMANDS_EXCHANGE, SETTLE_FUNDS_ROUTING_KEY, message);
    }
}
