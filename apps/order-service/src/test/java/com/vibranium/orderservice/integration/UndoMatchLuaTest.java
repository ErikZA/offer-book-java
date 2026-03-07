package com.vibranium.orderservice.integration;

import com.vibranium.contracts.enums.OrderType;
import com.vibranium.orderservice.infrastructure.redis.RedisMatchEngineAdapter;
import com.vibranium.orderservice.infrastructure.redis.RedisMatchEngineAdapter.MatchResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AT-17: Testes de integração para o script Lua {@code undo_match.lua}.
 *
 * <p>Verifica que o undo_match.lua reverte atomicamente um match executado
 * pelo match_engine.lua, restaurando contrapartes consumidas ao Sorted Set
 * e removendo inserções residuais da ordem ingressante.</p>
 *
 * <p>Cenários cobertos:</p>
 * <ul>
 *   <li>FULL match (BUY vs ASK): ASK consumido → undo restaura ASK</li>
 *   <li>PARTIAL_ASK match (BUY vs ASK): ASK parcialmente consumido → undo restaura qty original</li>
 *   <li>PARTIAL match (livro esgotou): ingressante reinserido com residual → undo remove residual</li>
 *   <li>Idempotência: 2× undo = mesmo estado</li>
 *   <li>Multi-match: múltiplas contrapartes restauradas de uma vez</li>
 * </ul>
 */
@DisplayName("AT-17: UndoMatchLua — Compensação Redis")
class UndoMatchLuaTest extends AbstractIntegrationTest {

    @Value("${app.redis.keys.asks:{vibranium}:asks}")
    private String asksKey;

    @Value("${app.redis.keys.bids:{vibranium}:bids}")
    private String bidsKey;

    @Value("${app.redis.keys.order-index:{vibranium}:order_index}")
    private String orderIndexKey;

    @Autowired
    private RedisMatchEngineAdapter matchEngine;

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

    private static UUID extractOrderId(String value) {
        return UUID.fromString(value.split("\\|")[0]);
    }

    private static BigDecimal extractQty(String value) {
        return new BigDecimal(value.split("\\|")[3]);
    }

    // =========================================================================
    // TC-UNDO-1: FULL match BUY vs ASK → undo restaura ASK no livro
    // =========================================================================

    @Test
    @DisplayName("TC-UNDO-1: FULL match BUY consume ASK → undoMatch restaura ASK no sorted set")
    void whenFullMatchBuyVsAsk_thenUndoRestoresAsk() {
        BigDecimal price = new BigDecimal("500.00");
        BigDecimal qty   = new BigDecimal("10.00000000");

        // Inserir ASK no livro
        UUID askOrderId = UUID.randomUUID();
        UUID askUserId  = UUID.randomUUID();
        UUID askWalletId = UUID.randomUUID();
        UUID askCorrelId = UUID.randomUUID();
        String askValue = buildRedisValue(askOrderId, askUserId, askWalletId, qty, askCorrelId);
        redisTemplate.opsForZSet().add(asksKey, askValue, priceToScore(price));
        redisTemplate.opsForHash().put(orderIndexKey, askOrderId.toString(),
                asksKey + "|" + (long) priceToScore(price) + "|" + askValue);

        // Verificar que ASK existe
        assertThat(redisTemplate.opsForZSet().zCard(asksKey)).isEqualTo(1L);

        // Executar BUY que consome o ASK (FULL match)
        UUID buyOrderId  = UUID.randomUUID();
        UUID buyWalletId = UUID.randomUUID();
        UUID buyCorrelId = UUID.randomUUID();
        String buyUserId = UUID.randomUUID().toString();

        List<MatchResult> results = matchEngine.tryMatch(
                buyOrderId, buyUserId, buyWalletId,
                OrderType.BUY, price, qty, buyCorrelId);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).fillType()).isEqualTo("FULL");

        // ASK foi consumido
        assertThat(redisTemplate.opsForZSet().zCard(asksKey)).isZero();

        // Executar undo (passa preço da contraparte para cálculo do score)
        int restored = matchEngine.undoMatch(OrderType.BUY, buyOrderId, results,
                Map.of(askOrderId, price));

        assertThat(restored).isEqualTo(1);

        // ASK deve estar de volta no livro
        assertThat(redisTemplate.opsForZSet().zCard(asksKey)).isEqualTo(1L);

        // Verificar que o orderId da contraparte está de volta no order_index
        Object indexEntry = redisTemplate.opsForHash().get(orderIndexKey, askOrderId.toString());
        assertThat(indexEntry).isNotNull();

        // Verificar qty restaurada
        Set<String> members = redisTemplate.opsForZSet().range(asksKey, 0, -1);
        assertThat(members).isNotNull().hasSize(1);
        String restoredMember = members.iterator().next();
        assertThat(extractQty(restoredMember)).isEqualByComparingTo(qty);
    }

    // =========================================================================
    // TC-UNDO-2: PARTIAL_ASK — ASK parcialmente consumido → undo restaura qty original
    // =========================================================================

    @Test
    @DisplayName("TC-UNDO-2: PARTIAL_ASK match → undoMatch restaura qty original do ASK")
    void whenPartialAskMatch_thenUndoRestoresOriginalAskQty() {
        BigDecimal price  = new BigDecimal("500.00");
        BigDecimal askQty = new BigDecimal("100.00000000");
        BigDecimal buyQty = new BigDecimal("40.00000000");

        // Inserir ASK com qty=100
        UUID askOrderId  = UUID.randomUUID();
        UUID askUserId   = UUID.randomUUID();
        UUID askWalletId = UUID.randomUUID();
        UUID askCorrelId = UUID.randomUUID();
        String askValue = buildRedisValue(askOrderId, askUserId, askWalletId, askQty, askCorrelId);
        redisTemplate.opsForZSet().add(asksKey, askValue, priceToScore(price));
        redisTemplate.opsForHash().put(orderIndexKey, askOrderId.toString(),
                asksKey + "|" + (long) priceToScore(price) + "|" + askValue);

        // BUY com qty=40 → PARTIAL_ASK (ASK fica com 60)
        UUID buyOrderId  = UUID.randomUUID();
        UUID buyWalletId = UUID.randomUUID();
        UUID buyCorrelId = UUID.randomUUID();
        String buyUserId = UUID.randomUUID().toString();

        List<MatchResult> results = matchEngine.tryMatch(
                buyOrderId, buyUserId, buyWalletId,
                OrderType.BUY, price, buyQty, buyCorrelId);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).fillType()).isEqualTo("PARTIAL_ASK");

        // ASK está no livro com qty residual (60)
        Set<String> membersBeforeUndo = redisTemplate.opsForZSet().range(asksKey, 0, -1);
        assertThat(membersBeforeUndo).isNotNull().hasSize(1);
        assertThat(extractQty(membersBeforeUndo.iterator().next()))
                .isEqualByComparingTo(new BigDecimal("60.00000000"));

        // Executar undo (para PARTIAL, score vem do order_index — price map pode ser vazio)
        int restored = matchEngine.undoMatch(OrderType.BUY, buyOrderId, results,
                Map.of(askOrderId, price));

        assertThat(restored).isEqualTo(1);

        // ASK deve estar com qty ORIGINAL (100)
        Set<String> membersAfterUndo = redisTemplate.opsForZSet().range(asksKey, 0, -1);
        assertThat(membersAfterUndo).isNotNull().hasSize(1);
        assertThat(extractQty(membersAfterUndo.iterator().next()))
                .isEqualByComparingTo(askQty);
    }

    // =========================================================================
    // TC-UNDO-3: PARTIAL (livro esgotou) → undo remove ingressante residual do book
    // =========================================================================

    @Test
    @DisplayName("TC-UNDO-3: PARTIAL match (livro esgotou) → undoMatch remove ingressante residual + restaura contrapartes")
    void whenPartialMatchBookExhausted_thenUndoRemovesIncomingResidualAndRestoresCounterparts() {
        BigDecimal price   = new BigDecimal("500.00");
        BigDecimal askQty  = new BigDecimal("30.00000000");
        BigDecimal buyQty  = new BigDecimal("100.00000000");

        // Inserir ASK com qty=30 (livro vai esgotar antes de preencher BUY de 100)
        UUID askOrderId  = UUID.randomUUID();
        UUID askUserId   = UUID.randomUUID();
        UUID askWalletId = UUID.randomUUID();
        UUID askCorrelId = UUID.randomUUID();
        String askValue = buildRedisValue(askOrderId, askUserId, askWalletId, askQty, askCorrelId);
        redisTemplate.opsForZSet().add(asksKey, askValue, priceToScore(price));
        redisTemplate.opsForHash().put(orderIndexKey, askOrderId.toString(),
                asksKey + "|" + (long) priceToScore(price) + "|" + askValue);

        // BUY com qty=100 → consome ASK(30), fica com 70 residual reinserido no book
        UUID buyOrderId  = UUID.randomUUID();
        UUID buyWalletId = UUID.randomUUID();
        UUID buyCorrelId = UUID.randomUUID();
        String buyUserId = UUID.randomUUID().toString();

        List<MatchResult> results = matchEngine.tryMatch(
                buyOrderId, buyUserId, buyWalletId,
                OrderType.BUY, price, buyQty, buyCorrelId);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).fillType()).isEqualTo("FULL");

        // Asks esgotado, BID com residual 70 inserido no book
        assertThat(redisTemplate.opsForZSet().zCard(asksKey)).isZero();
        assertThat(redisTemplate.opsForZSet().zCard(bidsKey)).isEqualTo(1L);

        // BUY residual no order_index
        Object buyIndex = redisTemplate.opsForHash().get(orderIndexKey, buyOrderId.toString());
        assertThat(buyIndex).isNotNull();

        // Executar undo
        int restored = matchEngine.undoMatch(OrderType.BUY, buyOrderId, results,
                Map.of(askOrderId, price));

        assertThat(restored).isEqualTo(1);

        // ASK deve estar de volta
        assertThat(redisTemplate.opsForZSet().zCard(asksKey)).isEqualTo(1L);

        // BUY residual deve ter sido removido do book
        assertThat(redisTemplate.opsForZSet().zCard(bidsKey)).isZero();

        // BUY deve ter sido removido do order_index
        Object buyIndexAfter = redisTemplate.opsForHash().get(orderIndexKey, buyOrderId.toString());
        assertThat(buyIndexAfter).isNull();
    }

    // =========================================================================
    // TC-UNDO-4: Idempotência — executar undo 2× = mesmo estado
    // =========================================================================

    @Test
    @DisplayName("TC-UNDO-4: undoMatch é idempotente — 2× undo produz o mesmo estado")
    void whenUndoExecutedTwice_thenStateIsIdentical() {
        BigDecimal price = new BigDecimal("500.00");
        BigDecimal qty   = new BigDecimal("10.00000000");

        // Inserir ASK
        UUID askOrderId  = UUID.randomUUID();
        UUID askUserId   = UUID.randomUUID();
        UUID askWalletId = UUID.randomUUID();
        UUID askCorrelId = UUID.randomUUID();
        String askValue = buildRedisValue(askOrderId, askUserId, askWalletId, qty, askCorrelId);
        redisTemplate.opsForZSet().add(asksKey, askValue, priceToScore(price));
        redisTemplate.opsForHash().put(orderIndexKey, askOrderId.toString(),
                asksKey + "|" + (long) priceToScore(price) + "|" + askValue);

        // FULL match
        UUID buyOrderId  = UUID.randomUUID();
        UUID buyWalletId = UUID.randomUUID();
        UUID buyCorrelId = UUID.randomUUID();
        String buyUserId = UUID.randomUUID().toString();

        List<MatchResult> results = matchEngine.tryMatch(
                buyOrderId, buyUserId, buyWalletId,
                OrderType.BUY, price, qty, buyCorrelId);

        assertThat(results).hasSize(1);

        // 1º undo
        matchEngine.undoMatch(OrderType.BUY, buyOrderId, results,
                Map.of(askOrderId, price));

        Long askCardAfterFirst = redisTemplate.opsForZSet().zCard(asksKey);
        Set<String> membersAfterFirst = redisTemplate.opsForZSet().range(asksKey, 0, -1);
        Object indexAfterFirst = redisTemplate.opsForHash().get(orderIndexKey, askOrderId.toString());

        // 2º undo (deve ser no-op)
        matchEngine.undoMatch(OrderType.BUY, buyOrderId, results,
                Map.of(askOrderId, price));

        Long askCardAfterSecond = redisTemplate.opsForZSet().zCard(asksKey);
        Set<String> membersAfterSecond = redisTemplate.opsForZSet().range(asksKey, 0, -1);
        Object indexAfterSecond = redisTemplate.opsForHash().get(orderIndexKey, askOrderId.toString());

        assertThat(askCardAfterSecond).isEqualTo(askCardAfterFirst);
        assertThat(membersAfterSecond).isEqualTo(membersAfterFirst);
        assertThat(indexAfterSecond).isEqualTo(indexAfterFirst);
    }

    // =========================================================================
    // TC-UNDO-5: Multi-match — múltiplas contrapartes restauradas
    // =========================================================================

    @Test
    @DisplayName("TC-UNDO-5: Multi-match com 3 ASKs consumidos → undoMatch restaura todos")
    void whenMultiMatchConsumedThreeAsks_thenUndoRestoresAll() {
        BigDecimal price = new BigDecimal("500.00");
        BigDecimal askQty = new BigDecimal("10.00000000");

        // Inserir 3 ASKs
        Map<UUID, BigDecimal> askPrices = new HashMap<>();
        for (int i = 0; i < 3; i++) {
            UUID askOrderId  = UUID.randomUUID();
            UUID askUserId   = UUID.randomUUID();
            UUID askWalletId = UUID.randomUUID();
            UUID askCorrelId = UUID.randomUUID();
            String askValue = buildRedisValue(askOrderId, askUserId, askWalletId, askQty, askCorrelId);
            redisTemplate.opsForZSet().add(asksKey, askValue, priceToScore(price));
            redisTemplate.opsForHash().put(orderIndexKey, askOrderId.toString(),
                    asksKey + "|" + (long) priceToScore(price) + "|" + askValue);
            askPrices.put(askOrderId, price);
        }

        assertThat(redisTemplate.opsForZSet().zCard(asksKey)).isEqualTo(3L);

        // BUY com qty=30 → consome todos os 3 ASKs (MULTI_MATCH)
        UUID buyOrderId  = UUID.randomUUID();
        UUID buyWalletId = UUID.randomUUID();
        UUID buyCorrelId = UUID.randomUUID();
        String buyUserId = UUID.randomUUID().toString();

        List<MatchResult> results = matchEngine.tryMatch(
                buyOrderId, buyUserId, buyWalletId,
                OrderType.BUY, price, new BigDecimal("30.00000000"), buyCorrelId);

        assertThat(results).hasSize(3);

        // Todos os ASKs consumidos
        assertThat(redisTemplate.opsForZSet().zCard(asksKey)).isZero();

        // Executar undo
        int restored = matchEngine.undoMatch(OrderType.BUY, buyOrderId, results, askPrices);

        assertThat(restored).isEqualTo(3);

        // Todos os 3 ASKs restaurados
        assertThat(redisTemplate.opsForZSet().zCard(asksKey)).isEqualTo(3L);
    }

    // =========================================================================
    // TC-UNDO-6: SELL match — undo restaura BID consumido
    // =========================================================================

    @Test
    @DisplayName("TC-UNDO-6: FULL match SELL consume BID → undoMatch restaura BID no sorted set")
    void whenFullMatchSellVsBid_thenUndoRestoresBid() {
        BigDecimal price = new BigDecimal("500.00");
        BigDecimal qty   = new BigDecimal("10.00000000");

        // Inserir BID no livro
        UUID bidOrderId  = UUID.randomUUID();
        UUID bidUserId   = UUID.randomUUID();
        UUID bidWalletId = UUID.randomUUID();
        UUID bidCorrelId = UUID.randomUUID();
        String bidValue = buildRedisValue(bidOrderId, bidUserId, bidWalletId, qty, bidCorrelId);
        redisTemplate.opsForZSet().add(bidsKey, bidValue, priceToScore(price));
        redisTemplate.opsForHash().put(orderIndexKey, bidOrderId.toString(),
                bidsKey + "|" + (long) priceToScore(price) + "|" + bidValue);

        assertThat(redisTemplate.opsForZSet().zCard(bidsKey)).isEqualTo(1L);

        // SELL que consome o BID
        UUID sellOrderId  = UUID.randomUUID();
        UUID sellWalletId = UUID.randomUUID();
        UUID sellCorrelId = UUID.randomUUID();
        String sellUserId = UUID.randomUUID().toString();

        List<MatchResult> results = matchEngine.tryMatch(
                sellOrderId, sellUserId, sellWalletId,
                OrderType.SELL, price, qty, sellCorrelId);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).fillType()).isEqualTo("FULL");

        // BID consumido
        assertThat(redisTemplate.opsForZSet().zCard(bidsKey)).isZero();

        // Executar undo
        int restored = matchEngine.undoMatch(OrderType.SELL, sellOrderId, results,
                Map.of(bidOrderId, price));

        assertThat(restored).isEqualTo(1);

        // BID restaurado
        assertThat(redisTemplate.opsForZSet().zCard(bidsKey)).isEqualTo(1L);

        Set<String> members = redisTemplate.opsForZSet().range(bidsKey, 0, -1);
        assertThat(members).isNotNull().hasSize(1);
        assertThat(extractQty(members.iterator().next())).isEqualByComparingTo(qty);
    }
}
