package com.vibranium.orderservice.integration;

import com.vibranium.contracts.enums.AssetType;
import com.vibranium.contracts.enums.OrderStatus;
import com.vibranium.contracts.enums.OrderType;
import com.vibranium.contracts.events.wallet.FundsReservedEvent;
import com.vibranium.orderservice.domain.model.Order;
import com.vibranium.orderservice.domain.repository.OrderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Testes de integração para deduplicação de ordens via consumer (AT-16).
 *
 * <p>Verifica que o fluxo completo {@code FundsReservedEvent → Consumer → Redis Lua}
 * não produz duplicatas no sorted set quando o mesmo evento é re-entregue pelo
 * RabbitMQ (cenário at-least-once delivery).</p>
 *
 * <p>Este teste valida a combinação das duas camadas de proteção:</p>
 * <ol>
 *   <li>{@code ProcessedEvent} no PostgreSQL (Fase 1 — idempotência JPA)</li>
 *   <li>{@code HEXISTS} no Lua script (Fase 2 — deduplicação Redis)</li>
 * </ol>
 */
@DisplayName("AT-16: Deduplicação de Ordens via Consumer (re-delivery simulada)")
class RedisDeduplicationConsumerTest extends AbstractIntegrationTest {

    private static final String FUNDS_RESERVED_RK = "wallet.events.funds-reserved";

    @Value("${app.redis.keys.asks:{vibranium}:asks}")
    private String asksKey;

    @Value("${app.redis.keys.bids:{vibranium}:bids}")
    private String bidsKey;

    @Value("${app.redis.keys.order-index:{vibranium}:order_index}")
    private String orderIndexKey;

    @Autowired
    private OrderRepository orderRepository;

    @BeforeEach
    void setup() {
        orderRepository.deleteAll();
        redisTemplate.delete(asksKey);
        redisTemplate.delete(bidsKey);
        redisTemplate.delete(orderIndexKey);
    }

    @AfterEach
    void tearDown() {
        redisTemplate.delete(asksKey);
        redisTemplate.delete(bidsKey);
        redisTemplate.delete(orderIndexKey);
    }

    // =========================================================================
    // TC-DEDUP-CONSUMER-1: re-delivery de FundsReservedEvent para ordem NO_MATCH
    // =========================================================================

    @Test
    @DisplayName("TC-DEDUP-CONSUMER-1: re-delivery de evento para ordem sem match → sorted set tem 1 entrada, ACK sem erro")
    void whenDuplicateFundsReservedEvent_forNoMatchOrder_thenSortedSetHasOnlyOneEntry() {
        BigDecimal price = new BigDecimal("490.00");
        BigDecimal qty   = new BigDecimal("10.00000000");

        UUID bidOrderId  = UUID.randomUUID();
        UUID bidCorrelId = UUID.randomUUID();
        UUID bidWalletId = UUID.randomUUID();

        // Cria ordem no banco (PENDING)
        Order order = Order.create(bidOrderId, bidCorrelId, UUID.randomUUID().toString(),
                bidWalletId, OrderType.BUY, price, qty);
        orderRepository.save(order);

        // Evento com eventId fixo (simula re-entrega exata)
        FundsReservedEvent event = FundsReservedEvent.of(
                bidCorrelId, bidOrderId, bidWalletId, AssetType.BRL, price.multiply(qty));

        // 1ª entrega: ordem processada, BID inserido no livro (sem match)
        rabbitTemplate.convertAndSend("vibranium.events", FUNDS_RESERVED_RK, event);

        await().atMost(8, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    Order updated = orderRepository.findById(bidOrderId).orElseThrow();
                    assertThat(updated.getStatus()).isEqualTo(OrderStatus.OPEN);
                });

        assertThat(redisTemplate.opsForZSet().zCard(bidsKey))
                .as("BID deve estar no livro após 1ª entrega")
                .isEqualTo(1L);

        // 2ª entrega (re-delivery simulada com MESMO eventId)
        rabbitTemplate.convertAndSend("vibranium.events", FUNDS_RESERVED_RK, event);

        // Aguarda processamento e verifica que não houve duplicata
        await().during(2, TimeUnit.SECONDS)
                .atMost(4, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    // Status não deve mudar
                    Order updated = orderRepository.findById(bidOrderId).orElseThrow();
                    assertThat(updated.getStatus())
                            .as("Status permanece OPEN após re-delivery")
                            .isEqualTo(OrderStatus.OPEN);

                    // Sorted set deve ter exatamente 1 entrada (sem duplicata)
                    assertThat(redisTemplate.opsForZSet().zCard(bidsKey))
                            .as("BID sorted set NÃO deve conter duplicata — ZCARD permanece 1")
                            .isEqualTo(1L);
                });
    }

    // =========================================================================
    // TC-DEDUP-CONSUMER-2: re-delivery de FundsReservedEvent para SELL sem match
    // =========================================================================

    @Test
    @DisplayName("TC-DEDUP-CONSUMER-2: re-delivery de evento SELL sem match → asks tem 1 entrada")
    void whenDuplicateFundsReservedEvent_forSellNoMatch_thenAsksSortedSetHasOnlyOneEntry() {
        BigDecimal price = new BigDecimal("510.00");
        BigDecimal qty   = new BigDecimal("5.00000000");

        UUID askOrderId  = UUID.randomUUID();
        UUID askCorrelId = UUID.randomUUID();
        UUID askWalletId = UUID.randomUUID();

        Order order = Order.create(askOrderId, askCorrelId, UUID.randomUUID().toString(),
                askWalletId, OrderType.SELL, price, qty);
        orderRepository.save(order);

        FundsReservedEvent event = FundsReservedEvent.of(
                askCorrelId, askOrderId, askWalletId, AssetType.VIBRANIUM, qty);

        // 1ª entrega
        rabbitTemplate.convertAndSend("vibranium.events", FUNDS_RESERVED_RK, event);

        await().atMost(8, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    Order updated = orderRepository.findById(askOrderId).orElseThrow();
                    assertThat(updated.getStatus()).isEqualTo(OrderStatus.OPEN);
                });

        assertThat(redisTemplate.opsForZSet().zCard(asksKey)).isEqualTo(1L);

        // 2ª entrega (re-delivery)
        rabbitTemplate.convertAndSend("vibranium.events", FUNDS_RESERVED_RK, event);

        await().during(2, TimeUnit.SECONDS)
                .atMost(4, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    assertThat(redisTemplate.opsForZSet().zCard(asksKey))
                            .as("ASK sorted set NÃO deve conter duplicata")
                            .isEqualTo(1L);
                });
    }
}
