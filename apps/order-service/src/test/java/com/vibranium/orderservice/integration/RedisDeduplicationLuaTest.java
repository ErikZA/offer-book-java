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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes de integração para deduplicação de ordens no Lua script (AT-16).
 *
 * <p>Verifica que o script {@code match_engine.lua} rejeita inserções duplicadas
 * no book via {@code HEXISTS} no {@code order_index} hash, retornando
 * {@code ALREADY_IN_BOOK} em vez de inserir a mesma ordem duas vezes.</p>
 *
 * <p>Este mecanismo funciona como defesa em profundidade: mesmo que a idempotência
 * via {@code ProcessedEvent} no PostgreSQL falhe em detectar a duplicata (ex: race
 * condition entre threads concorrentes), o Redis Lua garante que o sorted set nunca
 * conterá a mesma {@code orderId} duas vezes.</p>
 */
@DisplayName("AT-16: Deduplicação de Ordens no Redis Lua (HEXISTS guard)")
class RedisDeduplicationLuaTest extends AbstractIntegrationTest {

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
    // TC-DEDUP-1: BUY sem match → inserido no book → mesma orderId → ALREADY_IN_BOOK
    // =========================================================================

    @Test
    @DisplayName("TC-DEDUP-1: BUY inserido no book, mesma orderId duplicada → ALREADY_IN_BOOK, ZCARD=1")
    void whenSameOrderIdInsertedTwice_thenSecondCallReturnsAlreadyInBook() {
        BigDecimal price = new BigDecimal("500.00");
        BigDecimal qty   = new BigDecimal("10.00000000");

        UUID orderId    = UUID.randomUUID();
        UUID walletId   = UUID.randomUUID();
        UUID correlId   = UUID.randomUUID();
        String userId   = UUID.randomUUID().toString();

        // 1ª inserção: livro vazio, sem match → BID inserido no book
        List<MatchResult> firstResult = matchEngine.tryMatch(
                orderId, userId, walletId,
                OrderType.BUY, price, qty, correlId);

        assertThat(firstResult)
                .as("1ª chamada: livro vazio → NO_MATCH (lista vazia)")
                .isEmpty();

        assertThat(redisTemplate.opsForZSet().zCard(bidsKey))
                .as("BID deve estar no livro após 1ª inserção")
                .isEqualTo(1L);

        // 2ª inserção: mesma orderId → deve retornar ALREADY_IN_BOOK
        List<MatchResult> secondResult = matchEngine.tryMatch(
                orderId, userId, walletId,
                OrderType.BUY, price, qty, correlId);

        assertThat(secondResult)
                .as("2ª chamada com mesma orderId deve retornar exatamente 1 resultado ALREADY_IN_BOOK")
                .hasSize(1);

        MatchResult dedup = secondResult.get(0);
        assertThat(dedup.matched()).isFalse();
        assertThat(dedup.fillType()).isEqualTo("ALREADY_IN_BOOK");

        // ZCARD deve permanecer 1 — sem duplicata
        assertThat(redisTemplate.opsForZSet().zCard(bidsKey))
                .as("Sorted set NÃO deve conter duplicata — ZCARD permanece 1")
                .isEqualTo(1L);
    }

    // =========================================================================
    // TC-DEDUP-2: SELL sem match → inserido no book → mesma orderId → ALREADY_IN_BOOK
    // =========================================================================

    @Test
    @DisplayName("TC-DEDUP-2: SELL inserido no book, mesma orderId duplicada → ALREADY_IN_BOOK, ZCARD=1")
    void whenSameSellOrderIdInsertedTwice_thenSecondCallReturnsAlreadyInBook() {
        BigDecimal price = new BigDecimal("500.00");
        BigDecimal qty   = new BigDecimal("10.00000000");

        UUID orderId    = UUID.randomUUID();
        UUID walletId   = UUID.randomUUID();
        UUID correlId   = UUID.randomUUID();
        String userId   = UUID.randomUUID().toString();

        // 1ª inserção: livro vazio → ASK inserido no book
        List<MatchResult> firstResult = matchEngine.tryMatch(
                orderId, userId, walletId,
                OrderType.SELL, price, qty, correlId);

        assertThat(firstResult).isEmpty();
        assertThat(redisTemplate.opsForZSet().zCard(asksKey)).isEqualTo(1L);

        // 2ª inserção: mesma orderId → ALREADY_IN_BOOK
        List<MatchResult> secondResult = matchEngine.tryMatch(
                orderId, userId, walletId,
                OrderType.SELL, price, qty, correlId);

        assertThat(secondResult).hasSize(1);
        assertThat(secondResult.get(0).fillType()).isEqualTo("ALREADY_IN_BOOK");
        assertThat(secondResult.get(0).matched()).isFalse();

        assertThat(redisTemplate.opsForZSet().zCard(asksKey))
                .as("ASK sorted set permanece com 1 entrada")
                .isEqualTo(1L);
    }

    // =========================================================================
    // TC-DEDUP-3: Ordens DIFERENTES com orderIds distintos → ambas inseridas
    // =========================================================================

    @Test
    @DisplayName("TC-DEDUP-3: Ordens distintas com orderIds diferentes → ambas inseridas normalmente")
    void whenDifferentOrderIds_thenBothInsertedNormally() {
        BigDecimal price = new BigDecimal("500.00");
        BigDecimal qty   = new BigDecimal("10.00000000");

        UUID orderId1 = UUID.randomUUID();
        UUID orderId2 = UUID.randomUUID();

        // Ordem 1
        List<MatchResult> r1 = matchEngine.tryMatch(
                orderId1, UUID.randomUUID().toString(), UUID.randomUUID(),
                OrderType.BUY, price, qty, UUID.randomUUID());
        assertThat(r1).isEmpty();

        // Ordem 2 (orderId diferente)
        List<MatchResult> r2 = matchEngine.tryMatch(
                orderId2, UUID.randomUUID().toString(), UUID.randomUUID(),
                OrderType.BUY, price, qty, UUID.randomUUID());
        assertThat(r2).isEmpty();

        assertThat(redisTemplate.opsForZSet().zCard(bidsKey))
                .as("Duas ordens distintas devem coexistir no livro")
                .isEqualTo(2L);
    }

    // =========================================================================
    // TC-DEDUP-4: Ordem consumida por match → orderId removida do index →
    //             re-inserção com mesma orderId é permitida
    // =========================================================================

    @Test
    @DisplayName("TC-DEDUP-4: Ordem consumida por match, depois re-inserção com mesma orderId é permitida")
    void whenOrderConsumedByMatch_thenSameOrderIdCanBeReinserted() {
        BigDecimal price = new BigDecimal("500.00");
        BigDecimal qty   = new BigDecimal("10.00000000");

        UUID askOrderId = UUID.randomUUID();

        // Insere ASK
        List<MatchResult> askResult = matchEngine.tryMatch(
                askOrderId, UUID.randomUUID().toString(), UUID.randomUUID(),
                OrderType.SELL, price, qty, UUID.randomUUID());
        assertThat(askResult).isEmpty();

        // BID consome o ASK (match completo) → ASK removido do index
        UUID bidOrderId = UUID.randomUUID();
        List<MatchResult> bidResult = matchEngine.tryMatch(
                bidOrderId, UUID.randomUUID().toString(), UUID.randomUUID(),
                OrderType.BUY, price, qty, UUID.randomUUID());
        assertThat(bidResult).hasSize(1);
        assertThat(bidResult.get(0).counterpartId()).isEqualTo(askOrderId);

        // ASK foi consumido e removido do order_index via HDEL no Lua
        assertThat(redisTemplate.opsForHash().hasKey(orderIndexKey, askOrderId.toString()))
                .as("ASK consumido deve ter sido removido do order_index")
                .isFalse();

        // Re-inserção com o mesmo askOrderId deve funcionar normalmente
        List<MatchResult> reinsertResult = matchEngine.tryMatch(
                askOrderId, UUID.randomUUID().toString(), UUID.randomUUID(),
                OrderType.SELL, price, qty, UUID.randomUUID());
        assertThat(reinsertResult)
                .as("Re-inserção após consumo deve funcionar (não ALREADY_IN_BOOK)")
                .isEmpty();

        assertThat(redisTemplate.opsForZSet().zCard(asksKey))
                .as("ASK re-inserido deve estar no livro")
                .isEqualTo(1L);
    }
}
