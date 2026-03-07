package com.vibranium.orderservice.integration;

import com.vibranium.contracts.enums.OrderType;
import com.vibranium.orderservice.infrastructure.redis.RedisMatchEngineAdapter;
import com.vibranium.orderservice.infrastructure.redis.RedisMatchEngineAdapter.MatchResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AT-17: Testes de integração para métricas de compensação Redis.
 *
 * <p>Verifica que a métrica {@code vibranium.redis.compensation} é incrementada
 * toda vez que {@code undoMatch()} é invocado com sucesso.</p>
 */
@DisplayName("AT-17: Métricas de Compensação Redis")
class CompensationMetricsTest extends AbstractIntegrationTest {

    @Value("${app.redis.keys.asks:{vibranium}:asks}")
    private String asksKey;

    @Value("${app.redis.keys.bids:{vibranium}:bids}")
    private String bidsKey;

    @Value("${app.redis.keys.order-index:{vibranium}:order_index}")
    private String orderIndexKey;

    @Autowired
    private RedisMatchEngineAdapter matchEngine;

    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setup() {
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
    // Helpers
    // =========================================================================

    private static double priceToScore(BigDecimal price) {
        return price.multiply(BigDecimal.valueOf(100_000_000)).doubleValue();
    }

    private static String buildRedisValue(UUID orderId, UUID userId, UUID walletId,
                                          BigDecimal qty, UUID correlId) {
        return String.join("|",
                orderId.toString(), userId.toString(), walletId.toString(),
                qty.toPlainString(), correlId.toString(),
                String.valueOf(System.currentTimeMillis()));
    }

    // =========================================================================
    // TC-METRICS-1: undoMatch incrementa vibranium.redis.compensation
    // =========================================================================

    @Test
    @DisplayName("TC-METRICS-1: undoMatch incrementa vibranium.redis.compensation counter")
    void whenUndoMatchInvoked_thenCompensationCounterIsIncremented() {
        BigDecimal price = new BigDecimal("500.00");
        BigDecimal qty   = new BigDecimal("10.00000000");

        // Capturar valor atual da métrica (pode ter sido incrementada por testes anteriores)
        double countBefore = getCompensationCount();

        // Inserir ASK
        UUID askOrderId  = UUID.randomUUID();
        UUID askUserId   = UUID.randomUUID();
        UUID askWalletId = UUID.randomUUID();
        UUID askCorrelId = UUID.randomUUID();
        String askValue = buildRedisValue(askOrderId, askUserId, askWalletId, qty, askCorrelId);
        redisTemplate.opsForZSet().add(asksKey, askValue, priceToScore(price));
        redisTemplate.opsForHash().put(orderIndexKey, askOrderId.toString(),
                asksKey + "|" + (long) priceToScore(price) + "|" + askValue);

        // Match
        UUID buyOrderId  = UUID.randomUUID();
        UUID buyWalletId = UUID.randomUUID();
        UUID buyCorrelId = UUID.randomUUID();
        String buyUserId = UUID.randomUUID().toString();

        List<MatchResult> results = matchEngine.tryMatch(
                buyOrderId, buyUserId, buyWalletId,
                OrderType.BUY, price, qty, buyCorrelId);

        assertThat(results).hasSize(1);

        // Undo
        matchEngine.undoMatch(OrderType.BUY, buyOrderId, results,
                Map.of(askOrderId, price));

        // Métrica incrementada
        double countAfter = getCompensationCount();
        assertThat(countAfter).isGreaterThan(countBefore);
        assertThat(countAfter - countBefore).isEqualTo(1.0);
    }

    // =========================================================================
    // TC-METRICS-2: Múltiplos undos incrementam a métrica proporcionalmente
    // =========================================================================

    @Test
    @DisplayName("TC-METRICS-2: Dois undos incrementam a métrica em 2")
    void whenTwoUndosInvoked_thenCompensationCounterIncrementsByTwo() {
        BigDecimal price = new BigDecimal("500.00");
        BigDecimal qty   = new BigDecimal("10.00000000");

        double countBefore = getCompensationCount();

        // 1º match + undo
        doMatchAndUndo(price, qty);

        // 2º match + undo
        doMatchAndUndo(price, qty);

        double countAfter = getCompensationCount();
        assertThat(countAfter - countBefore).isEqualTo(2.0);
    }

    // =========================================================================
    // Helpers privados
    // =========================================================================

    private double getCompensationCount() {
        Counter counter = meterRegistry.find("vibranium.redis.compensation").counter();
        return counter != null ? counter.count() : 0.0;
    }

    private void doMatchAndUndo(BigDecimal price, BigDecimal qty) {
        // Limpar Redis para cada ciclo
        redisTemplate.delete(asksKey);
        redisTemplate.delete(bidsKey);
        redisTemplate.delete(orderIndexKey);

        UUID askOrderId  = UUID.randomUUID();
        UUID askUserId   = UUID.randomUUID();
        UUID askWalletId = UUID.randomUUID();
        UUID askCorrelId = UUID.randomUUID();
        String askValue = buildRedisValue(askOrderId, askUserId, askWalletId, qty, askCorrelId);
        redisTemplate.opsForZSet().add(asksKey, askValue, priceToScore(price));
        redisTemplate.opsForHash().put(orderIndexKey, askOrderId.toString(),
                asksKey + "|" + (long) priceToScore(price) + "|" + askValue);

        UUID buyOrderId  = UUID.randomUUID();
        UUID buyWalletId = UUID.randomUUID();
        UUID buyCorrelId = UUID.randomUUID();
        String buyUserId = UUID.randomUUID().toString();

        List<MatchResult> results = matchEngine.tryMatch(
                buyOrderId, buyUserId, buyWalletId,
                OrderType.BUY, price, qty, buyCorrelId);

        matchEngine.undoMatch(OrderType.BUY, buyOrderId, results,
                Map.of(askOrderId, price));
    }
}
