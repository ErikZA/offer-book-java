package com.vibranium.contracts;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vibranium.contracts.enums.FailureReason;
import com.vibranium.contracts.enums.OrderType;
import com.vibranium.contracts.events.order.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testa a serialização e deserialização JSON (round-trip) de todos os
 * eventos do contexto Order.
 *
 * Validações críticas:
 * - OrderType enum (BUY/SELL) preservado como string
 * - MatchExecutedEvent contém os dois lados do trade intactos
 * - Campos de rastreabilidade (correlationId, aggregateId) propagados corretamente
 */
@DisplayName("Order Events — JSON Serialization Round-Trip")
class OrderEventsSerializationTest {

    private static ObjectMapper mapper;

    @BeforeAll
    static void setUp() {
        mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .enable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(SerializationFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS)
            .disable(DeserializationFeature.READ_DATE_TIMESTAMPS_AS_NANOSECONDS);
    }

    // -------------------------------------------------------------------------
    // OrderReceivedEvent
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("OrderReceivedEvent: round-trip preserva todos os campos de entrada da Saga")
    void orderReceivedEvent_roundTrip() throws Exception {
        UUID correlationId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();
        BigDecimal price = new BigDecimal("155.50");
        BigDecimal amount = new BigDecimal("12.00");

        OrderReceivedEvent original = OrderReceivedEvent.of(
                correlationId, orderId, userId, walletId,
                OrderType.BUY, price, amount);

        String json = mapper.writeValueAsString(original);
        OrderReceivedEvent restored = mapper.readValue(json, OrderReceivedEvent.class);

        assertThat(restored.orderId()).isEqualTo(orderId);
        assertThat(restored.userId()).isEqualTo(userId);
        assertThat(restored.walletId()).isEqualTo(walletId);
        assertThat(restored.orderType()).isEqualTo(OrderType.BUY);
        assertThat(restored.price()).isEqualByComparingTo(price);
        assertThat(restored.amount()).isEqualByComparingTo(amount);
        assertThat(restored.correlationId()).isEqualTo(correlationId);
    }

    // -------------------------------------------------------------------------
    // OrderAddedToBookEvent
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("OrderAddedToBookEvent: round-trip com SELL preserva remainingAmount")
    void orderAddedToBookEvent_roundTrip_sell() throws Exception {
        UUID orderId = UUID.randomUUID();
        BigDecimal remaining = new BigDecimal("50.00");

        OrderAddedToBookEvent original = OrderAddedToBookEvent.of(
                UUID.randomUUID(), orderId, OrderType.SELL,
                new BigDecimal("160.00"), remaining);

        String json = mapper.writeValueAsString(original);
        OrderAddedToBookEvent restored = mapper.readValue(json, OrderAddedToBookEvent.class);

        assertThat(restored.orderId()).isEqualTo(orderId);
        assertThat(restored.orderType()).isEqualTo(OrderType.SELL);
        assertThat(restored.remainingAmount()).isEqualByComparingTo(remaining);
    }

    // -------------------------------------------------------------------------
    // MatchExecutedEvent — EVENTO CRÍTICO
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("MatchExecutedEvent: round-trip preserva ambos os lados do match")
    void matchExecutedEvent_roundTrip() throws Exception {
        UUID correlationId = UUID.randomUUID();
        UUID buyOrderId = UUID.randomUUID();
        UUID sellOrderId = UUID.randomUUID();
        UUID buyerUserId = UUID.randomUUID();
        UUID sellerUserId = UUID.randomUUID();
        UUID buyerWalletId = UUID.randomUUID();
        UUID sellerWalletId = UUID.randomUUID();
        BigDecimal matchPrice = new BigDecimal("157.33");
        BigDecimal matchAmount = new BigDecimal("3.25");

        MatchExecutedEvent original = MatchExecutedEvent.of(
                correlationId, buyOrderId, sellOrderId,
                buyerUserId, sellerUserId,
                buyerWalletId, sellerWalletId,
                matchPrice, matchAmount);

        String json = mapper.writeValueAsString(original);
        MatchExecutedEvent restored = mapper.readValue(json, MatchExecutedEvent.class);

        assertThat(restored.buyOrderId()).isEqualTo(buyOrderId);
        assertThat(restored.sellOrderId()).isEqualTo(sellOrderId);
        assertThat(restored.buyerUserId()).isEqualTo(buyerUserId);
        assertThat(restored.sellerUserId()).isEqualTo(sellerUserId);
        assertThat(restored.buyerWalletId()).isEqualTo(buyerWalletId);
        assertThat(restored.sellerWalletId()).isEqualTo(sellerWalletId);
        assertThat(restored.matchPrice()).isEqualByComparingTo(matchPrice);
        assertThat(restored.matchAmount()).isEqualByComparingTo(matchAmount);
        assertThat(restored.matchId()).isNotNull();
        assertThat(restored.correlationId()).isEqualTo(correlationId);
    }

    // -------------------------------------------------------------------------
    // OrderPartiallyFilledEvent
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("OrderPartiallyFilledEvent: round-trip preserva filledAmount e remainingAmount")
    void orderPartiallyFilledEvent_roundTrip() throws Exception {
        UUID orderId = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();
        BigDecimal filled = new BigDecimal("40.00");
        BigDecimal remaining = new BigDecimal("60.00");

        OrderPartiallyFilledEvent original = OrderPartiallyFilledEvent.of(
                UUID.randomUUID(), orderId, matchId, filled, remaining);

        String json = mapper.writeValueAsString(original);
        OrderPartiallyFilledEvent restored = mapper.readValue(
                json, OrderPartiallyFilledEvent.class);

        assertThat(restored.filledAmount()).isEqualByComparingTo(filled);
        assertThat(restored.remainingAmount()).isEqualByComparingTo(remaining);
        assertThat(restored.matchId()).isEqualTo(matchId);
    }

    // -------------------------------------------------------------------------
    // OrderFilledEvent
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("OrderFilledEvent: round-trip com averagePrice calculado")
    void orderFilledEvent_roundTrip() throws Exception {
        UUID orderId = UUID.randomUUID();
        BigDecimal avgPrice = new BigDecimal("156.425"); // média de 2 matches

        OrderFilledEvent original = OrderFilledEvent.of(
                UUID.randomUUID(), orderId, new BigDecimal("100.00"), avgPrice);

        String json = mapper.writeValueAsString(original);
        OrderFilledEvent restored = mapper.readValue(json, OrderFilledEvent.class);

        assertThat(restored.orderId()).isEqualTo(orderId);
        assertThat(restored.averagePrice()).isEqualByComparingTo(avgPrice);
        assertThat(restored.totalFilled()).isEqualByComparingTo(new BigDecimal("100.00"));
    }

    // -------------------------------------------------------------------------
    // OrderCancelledEvent
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("OrderCancelledEvent: round-trip com FailureReason INSUFFICIENT_FUNDS")
    void orderCancelledEvent_roundTrip_insufficientFunds() throws Exception {
        UUID orderId = UUID.randomUUID();
        String detail = "BRL available: 0.00, required: 500.00";

        OrderCancelledEvent original = OrderCancelledEvent.of(
                UUID.randomUUID(), orderId,
                FailureReason.INSUFFICIENT_FUNDS, detail);

        String json = mapper.writeValueAsString(original);
        OrderCancelledEvent restored = mapper.readValue(json, OrderCancelledEvent.class);

        assertThat(restored.orderId()).isEqualTo(orderId);
        assertThat(restored.reason()).isEqualTo(FailureReason.INSUFFICIENT_FUNDS);
        assertThat(restored.detail()).isEqualTo(detail);
    }

    @Test
    @DisplayName("OrderCancelledEvent: round-trip com FailureReason SAGA_TIMEOUT")
    void orderCancelledEvent_roundTrip_sagaTimeout() throws Exception {
        UUID orderId = UUID.randomUUID();

        OrderCancelledEvent original = OrderCancelledEvent.of(
                UUID.randomUUID(), orderId,
                FailureReason.SAGA_TIMEOUT, "Saga exceeded 30s SLA");

        String json = mapper.writeValueAsString(original);
        OrderCancelledEvent restored = mapper.readValue(json, OrderCancelledEvent.class);

        assertThat(restored.reason()).isEqualTo(FailureReason.SAGA_TIMEOUT);
    }
}
