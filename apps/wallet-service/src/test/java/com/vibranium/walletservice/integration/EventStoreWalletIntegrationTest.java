package com.vibranium.walletservice.integration;

import com.vibranium.contracts.commands.wallet.ReleaseFundsCommand;
import com.vibranium.contracts.commands.wallet.ReserveFundsCommand;
import com.vibranium.contracts.commands.wallet.SettleFundsCommand;
import com.vibranium.contracts.enums.AssetType;
import com.vibranium.walletservice.AbstractIntegrationTest;
import com.vibranium.walletservice.application.service.EventStoreService;
import com.vibranium.walletservice.application.service.WalletService;
import com.vibranium.walletservice.domain.model.EventStoreEntry;
import com.vibranium.walletservice.domain.model.Wallet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Teste de integração que valida que o WalletService grava eventos
 * no Event Store atomicamente junto com a operação de domínio e o outbox.
 *
 * <p>Todos os testes operam contra PostgreSQL real via Testcontainers,
 * garantindo que a transação atômica (saldo + outbox + event store)
 * funciona end-to-end.</p>
 */
@DisplayName("EventStore — Integração com WalletService")
class EventStoreWalletIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private WalletService walletService;

    @Autowired
    private EventStoreService eventStoreService;

    @Test
    @DisplayName("createWallet deve gravar WalletCreatedEvent no Event Store")
    void createWallet_shouldAppendToEventStore() {
        // Arrange
        UUID userId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        String messageId = UUID.randomUUID().toString();

        // Act
        walletService.createWallet(userId, correlationId, messageId);

        // Assert — verifica que pelo menos a wallet foi criada
        Wallet wallet = walletRepository.findByUserId(userId).orElseThrow();
        String walletId = wallet.getId().toString();

        List<EventStoreEntry> events = eventStoreService.getEventsByAggregateId(walletId);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getEventType()).isEqualTo("WalletCreatedEvent");
        assertThat(events.get(0).getAggregateType()).isEqualTo("Wallet");
        assertThat(events.get(0).getCorrelationId()).isEqualTo(correlationId);
        assertThat(events.get(0).getPayload()).contains(userId.toString());
    }

    @Test
    @DisplayName("reserveFunds com sucesso deve gravar FundsReservedEvent no Event Store")
    void reserveFunds_success_shouldAppendToEventStore() {
        // Arrange — cria wallet com saldo
        UUID userId = UUID.randomUUID();
        Wallet wallet = Wallet.create(userId, new BigDecimal("1000.00"), BigDecimal.ZERO);
        wallet = walletRepository.save(wallet);
        UUID walletId = wallet.getId();
        UUID orderId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();

        ReserveFundsCommand cmd = new ReserveFundsCommand(
                correlationId, orderId, walletId, AssetType.BRL, new BigDecimal("100.00"), 1
        );

        // Act
        walletService.reserveFunds(cmd, UUID.randomUUID().toString());

        // Assert
        List<EventStoreEntry> events = eventStoreService.getEventsByAggregateId(walletId.toString());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getEventType()).isEqualTo("FundsReservedEvent");
        assertThat(events.get(0).getAggregateType()).isEqualTo("Wallet");
        assertThat(events.get(0).getPayload()).contains("100.00");
    }

    @Test
    @DisplayName("reserveFunds com saldo insuficiente deve gravar FundsReservationFailedEvent no Event Store")
    void reserveFunds_insufficientFunds_shouldAppendFailedEvent() {
        // Arrange — wallet com saldo zero
        UUID userId = UUID.randomUUID();
        Wallet wallet = Wallet.create(userId, BigDecimal.ZERO, BigDecimal.ZERO);
        wallet = walletRepository.save(wallet);
        UUID walletId = wallet.getId();

        ReserveFundsCommand cmd = new ReserveFundsCommand(
                UUID.randomUUID(), UUID.randomUUID(), walletId,
                AssetType.BRL, new BigDecimal("100.00"), 1
        );

        // Act
        walletService.reserveFunds(cmd, UUID.randomUUID().toString());

        // Assert
        List<EventStoreEntry> events = eventStoreService.getEventsByAggregateId(walletId.toString());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getEventType()).isEqualTo("FundsReservationFailedEvent");
    }

    @Test
    @DisplayName("releaseFunds com sucesso deve gravar FundsReleasedEvent no Event Store")
    void releaseFunds_success_shouldAppendToEventStore() {
        // Arrange — cria wallet e reserva fundos primeiro
        UUID userId = UUID.randomUUID();
        Wallet wallet = Wallet.create(userId, new BigDecimal("1000.00"), BigDecimal.ZERO);
        wallet = walletRepository.save(wallet);
        UUID walletId = wallet.getId();
        UUID orderId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();

        // Reserva primeiro
        ReserveFundsCommand reserveCmd = new ReserveFundsCommand(
                correlationId, orderId, walletId, AssetType.BRL, new BigDecimal("100.00"), 1
        );
        walletService.reserveFunds(reserveCmd, UUID.randomUUID().toString());

        // Act — libera os fundos
        ReleaseFundsCommand releaseCmd = new ReleaseFundsCommand(
                correlationId, orderId, walletId, AssetType.BRL, new BigDecimal("100.00"), 1
        );
        walletService.releaseFunds(releaseCmd, UUID.randomUUID().toString());

        // Assert — deve ter 2 eventos: FundsReserved + FundsReleased
        List<EventStoreEntry> events = eventStoreService.getEventsByAggregateId(walletId.toString());
        assertThat(events).hasSize(2);
        assertThat(events).extracting(EventStoreEntry::getEventType)
                .containsExactly("FundsReservedEvent", "FundsReleasedEvent");
    }

    @Test
    @DisplayName("settleFunds com sucesso deve gravar FundsSettledEvent no Event Store")
    void settleFunds_success_shouldAppendToEventStore() {
        // Arrange — cria buyer com BRL, seller com VIB
        UUID buyerUserId = UUID.randomUUID();
        UUID sellerUserId = UUID.randomUUID();
        Wallet buyer = Wallet.create(buyerUserId, new BigDecimal("1000.00"), BigDecimal.ZERO);
        Wallet seller = Wallet.create(sellerUserId, BigDecimal.ZERO, new BigDecimal("10.00"));
        buyer = walletRepository.save(buyer);
        seller = walletRepository.save(seller);

        // Reserva BRL no buyer e VIB no seller
        walletService.reserveFunds(new ReserveFundsCommand(
                UUID.randomUUID(), UUID.randomUUID(), buyer.getId(),
                AssetType.BRL, new BigDecimal("500.00"), 1
        ), UUID.randomUUID().toString());
        walletService.reserveFunds(new ReserveFundsCommand(
                UUID.randomUUID(), UUID.randomUUID(), seller.getId(),
                AssetType.VIBRANIUM, new BigDecimal("5.00"), 1
        ), UUID.randomUUID().toString());

        UUID matchId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();

        SettleFundsCommand settleCmd = new SettleFundsCommand(
                correlationId, matchId,
                UUID.randomUUID(), UUID.randomUUID(),
                buyer.getId(), seller.getId(),
                new BigDecimal("100.00"), new BigDecimal("5.00"),
                1
        );

        // Act
        walletService.settleFunds(settleCmd, UUID.randomUUID().toString());

        // Assert — o evento é gravado com aggregateId = matchId
        List<EventStoreEntry> events = eventStoreService.getEventsByAggregateId(matchId.toString());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getEventType()).isEqualTo("FundsSettledEvent");
        assertThat(events.get(0).getAggregateType()).isEqualTo("Wallet");
    }

    @Test
    @DisplayName("Todos os eventos devem ter payload JSON e correlationId preenchidos")
    void allEvents_shouldHavePayloadAndCorrelationId() {
        // Arrange — cria wallet e reserva
        UUID userId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        Wallet wallet = Wallet.create(userId, new BigDecimal("500.00"), BigDecimal.ZERO);
        wallet = walletRepository.save(wallet);

        ReserveFundsCommand cmd = new ReserveFundsCommand(
                correlationId, UUID.randomUUID(), wallet.getId(),
                AssetType.BRL, new BigDecimal("50.00"), 1
        );
        walletService.reserveFunds(cmd, UUID.randomUUID().toString());

        // Assert
        List<EventStoreEntry> events = eventStoreService.getEventsByAggregateId(wallet.getId().toString());
        assertThat(events).isNotEmpty();
        for (EventStoreEntry entry : events) {
            assertThat(entry.getPayload()).isNotEmpty();
            assertThat(entry.getCorrelationId()).isNotNull();
            assertThat(entry.getEventId()).isNotNull();
            assertThat(entry.getOccurredOn()).isNotNull();
            assertThat(entry.getSchemaVersion()).isGreaterThanOrEqualTo(1);
        }
    }
}
