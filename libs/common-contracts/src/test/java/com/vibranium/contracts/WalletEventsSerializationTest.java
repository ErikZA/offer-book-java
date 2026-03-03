package com.vibranium.contracts;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vibranium.contracts.enums.AssetType;
import com.vibranium.contracts.enums.FailureReason;
import com.vibranium.contracts.events.wallet.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Testa a serialização e deserialização JSON (round-trip) de todos os
 * eventos do contexto Wallet.
 *
 * Garante que os eventos sobrevivem intactos à jornada pelo RabbitMQ:
 * serialização ao publicar → bytes no broker → deserialização ao consumir.
 *
 * Aspectos críticos validados:
 * - BigDecimal preserva precisão (sem perda de casas decimais)
 * - Instant é serializado como epoch-millis (compatibilidade entre serviços)
 * - Enums são serializados como strings
 * - UUID preserva formato canônico
 */
@DisplayName("Wallet Events — JSON Serialization Round-Trip")
class WalletEventsSerializationTest {

    private static ObjectMapper mapper;

    @BeforeAll
    static void setUp() {
        mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            // Serializa Instant como epoch-millis (long) — não como nanosegundos
            .enable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(SerializationFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS)
            // Lê epoch-millis corretamente (padrão Jackson seria nanossegundos)
            .disable(DeserializationFeature.READ_DATE_TIMESTAMPS_AS_NANOSECONDS);
    }

    // -------------------------------------------------------------------------
    // WalletCreatedEvent
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("WalletCreatedEvent: round-trip preserva todos os campos")
    void walletCreatedEvent_roundTrip() throws Exception {
        UUID correlationId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        WalletCreatedEvent original = WalletCreatedEvent.of(correlationId, walletId, userId);

        String json = mapper.writeValueAsString(original);
        WalletCreatedEvent restored = mapper.readValue(json, WalletCreatedEvent.class);

        assertThat(restored.eventId()).isEqualTo(original.eventId());
        assertThat(restored.correlationId()).isEqualTo(correlationId);
        assertThat(restored.aggregateId()).isEqualTo(walletId.toString());
        assertThat(restored.walletId()).isEqualTo(walletId);
        assertThat(restored.userId()).isEqualTo(userId);
        // Instant serializado como epoch-millis: precisão trunçada para ms
        assertThat(restored.occurredOn()).isCloseTo(original.occurredOn(), within(1, ChronoUnit.MILLIS));
    }

    // -------------------------------------------------------------------------
    // FundsReservedEvent
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("FundsReservedEvent: round-trip preserva BigDecimal e AssetType enum")
    void fundsReservedEvent_roundTrip() throws Exception {
        UUID correlationId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("1234.5678");

        FundsReservedEvent original = FundsReservedEvent.of(
                correlationId, orderId, walletId, AssetType.BRL, amount);

        String json = mapper.writeValueAsString(original);
        FundsReservedEvent restored = mapper.readValue(json, FundsReservedEvent.class);

        assertThat(restored.orderId()).isEqualTo(orderId);
        assertThat(restored.walletId()).isEqualTo(walletId);
        assertThat(restored.asset()).isEqualTo(AssetType.BRL);
        assertThat(restored.reservedAmount()).isEqualByComparingTo(amount);
    }

    // -------------------------------------------------------------------------
    // FundsReservationFailedEvent
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("FundsReservationFailedEvent: round-trip preserva FailureReason e detail")
    void fundsReservationFailedEvent_roundTrip() throws Exception {
        UUID correlationId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String detail = "BRL available: 50.00, required: 200.00";

        FundsReservationFailedEvent original = FundsReservationFailedEvent.of(
                correlationId, orderId, "wallet-abc",
                FailureReason.INSUFFICIENT_FUNDS, detail);

        String json = mapper.writeValueAsString(original);
        FundsReservationFailedEvent restored = mapper.readValue(
                json, FundsReservationFailedEvent.class);

        assertThat(restored.reason()).isEqualTo(FailureReason.INSUFFICIENT_FUNDS);
        assertThat(restored.detail()).isEqualTo(detail);
        assertThat(restored.orderId()).isEqualTo(orderId);
    }

    // -------------------------------------------------------------------------
    // FundsSettledEvent
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("FundsSettledEvent: round-trip preserva ambos os lados do trade")
    void fundsSettledEvent_roundTrip() throws Exception {
        UUID correlationId = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();
        UUID buyOrderId = UUID.randomUUID();
        UUID sellOrderId = UUID.randomUUID();
        UUID buyerWalletId = UUID.randomUUID();
        UUID sellerWalletId = UUID.randomUUID();
        BigDecimal price = new BigDecimal("155.75");
        BigDecimal amount = new BigDecimal("8.500");

        FundsSettledEvent original = FundsSettledEvent.of(
                correlationId, matchId, buyOrderId, sellOrderId,
                buyerWalletId, sellerWalletId, price, amount);

        String json = mapper.writeValueAsString(original);
        FundsSettledEvent restored = mapper.readValue(json, FundsSettledEvent.class);

        assertThat(restored.matchId()).isEqualTo(matchId);
        assertThat(restored.buyOrderId()).isEqualTo(buyOrderId);
        assertThat(restored.sellOrderId()).isEqualTo(sellOrderId);
        assertThat(restored.buyerWalletId()).isEqualTo(buyerWalletId);
        assertThat(restored.sellerWalletId()).isEqualTo(sellerWalletId);
        assertThat(restored.matchPrice()).isEqualByComparingTo(price);
        assertThat(restored.matchAmount()).isEqualByComparingTo(amount);
    }

    // -------------------------------------------------------------------------
    // FundsReleasedEvent
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("FundsReleasedEvent: round-trip preserva BigDecimal e AssetType enum")
    void fundsReleasedEvent_roundTrip() throws Exception {
        UUID correlationId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("987.6543");

        FundsReleasedEvent original = FundsReleasedEvent.of(
                correlationId, orderId, walletId, AssetType.BRL, amount);

        String json = mapper.writeValueAsString(original);
        FundsReleasedEvent restored = mapper.readValue(json, FundsReleasedEvent.class);

        assertThat(restored.eventId()).isEqualTo(original.eventId());
        assertThat(restored.correlationId()).isEqualTo(correlationId);
        assertThat(restored.aggregateId()).isEqualTo(walletId.toString());
        assertThat(restored.orderId()).isEqualTo(orderId);
        assertThat(restored.walletId()).isEqualTo(walletId);
        assertThat(restored.asset()).isEqualTo(AssetType.BRL);
        assertThat(restored.releasedAmount()).isEqualByComparingTo(amount);
        assertThat(restored.occurredOn()).isCloseTo(original.occurredOn(), within(1, ChronoUnit.MILLIS));
        assertThat(restored.schemaVersion()).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // FundsReleaseFailedEvent
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("FundsReleaseFailedEvent: round-trip preserva orderId, FailureReason e detail")
    void fundsReleaseFailedEvent_roundTrip() throws Exception {
        UUID correlationId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        String detail = "Wallet lock record not found for orderId";

        FundsReleaseFailedEvent original = FundsReleaseFailedEvent.of(
                correlationId, orderId, "wallet-xyz",
                FailureReason.RELEASE_DB_ERROR, detail);

        String json = mapper.writeValueAsString(original);
        FundsReleaseFailedEvent restored = mapper.readValue(json, FundsReleaseFailedEvent.class);

        assertThat(restored.eventId()).isEqualTo(original.eventId());
        assertThat(restored.correlationId()).isEqualTo(correlationId);
        assertThat(restored.aggregateId()).isEqualTo("wallet-xyz");
        assertThat(restored.orderId()).isEqualTo(orderId);
        assertThat(restored.reason()).isEqualTo(FailureReason.RELEASE_DB_ERROR);
        assertThat(restored.detail()).isEqualTo(detail);
        assertThat(restored.schemaVersion()).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // Precisão de BigDecimal (crítico para sistema financeiro)
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("BigDecimal: precisão de 8 casas decimais deve ser preservada no round-trip")
    void bigDecimal_highPrecision_preserved() throws Exception {
        BigDecimal highPrecision = new BigDecimal("0.00000001"); // satoshi-level precision
        FundsReservedEvent event = FundsReservedEvent.of(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                AssetType.VIBRANIUM, highPrecision);

        String json = mapper.writeValueAsString(event);
        FundsReservedEvent restored = mapper.readValue(json, FundsReservedEvent.class);

        assertThat(restored.reservedAmount()).isEqualByComparingTo(highPrecision);
    }
}
