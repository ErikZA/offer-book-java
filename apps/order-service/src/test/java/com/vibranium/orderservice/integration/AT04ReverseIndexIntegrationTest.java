package com.vibranium.orderservice.integration;

import com.vibranium.contracts.enums.OrderType;
import com.vibranium.orderservice.adapter.redis.RedisMatchEngineAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AT-04.2 — Índice reverso Redis {@code orderId → sorted_set_key | value}.
 *
 * <h2>Problema resolvido</h2>
 * <p>Sem o índice reverso, {@code ZREM} exige o member completo do Sorted Set;
 * localizá-lo requer {@code ZSCAN} (O(n)), ineficiente para livros grandes.</p>
 *
 * <h2>Solução</h2>
 * <p>O script {@code match_engine.lua} popula atomicamente o hash auxiliar
 * {@code {vibranium}:order_index} em cada inserção:
 * <pre>{@code HSET {vibranium}:order_index <orderId> "<bookKey>|<score>|<member>"}</pre>
 * O script {@code remove_from_book.lua} usa {@code HGET + ZREM + HDEL} — tudo
 * atômico via Lua — eliminando qualquer necessidade de scan.</p>
 *
 * <h2>TDD — FASE RED</h2>
 * <p>Os testes deste arquivo foram criados <strong>antes</strong> da implementação
 * e falham enquanto:</p>
 * <ul>
 *   <li>{@code match_engine.lua} não tiver as chamadas {@code HSET}/{@code HDEL}.</li>
 *   <li>{@code removeFromBook} ainda usar ZSCAN em vez do script O(1).</li>
 * </ul>
 *
 * <h2>Critérios de aceite</h2>
 * <ul>
 *   <li>Hash populado corretamente após {@code tryMatch} (NO_MATCH e PARTIAL).</li>
 *   <li>Remoção atômica: {@code ZREM + HDEL} no mesmo script Lua.</li>
 *   <li>Nenhum {@code ZSCAN}.</li>
 *   <li>Compatível com Redis Cluster (hash tag {@code {vibranium}}).</li>
 *   <li>Remoção de 1 ordem dentre 1000 ocorre em ≤ 5 ms.</li>
 * </ul>
 */
@DisplayName("AT-04.2 — Índice reverso Redis: orderId → sorted_set_key + value")
class AT04ReverseIndexIntegrationTest extends AbstractIntegrationTest {

    @Value("${app.redis.keys.asks:{vibranium}:asks}")
    private String asksKey;

    @Value("${app.redis.keys.bids:{vibranium}:bids}")
    private String bidsKey;

    @Value("${app.redis.keys.order-index:{vibranium}:order_index}")
    private String orderIndexKey;

    @Autowired
    private RedisMatchEngineAdapter matchEngine;

    // -------------------------------------------------------------------------
    // Setup / Teardown
    // -------------------------------------------------------------------------

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
    // Cenário 1 — BID NO_MATCH: índice deve ser populado na inserção
    // =========================================================================

    /**
     * Quando uma ordem BUY não encontra ASK contraparte, o script insere o BID
     * no Sorted Set {@code {vibranium}:bids} <strong>E</strong> deve criar a entrada
     * no hash {@code {vibranium}:order_index}.
     *
     * <p><strong>FASE RED:</strong> falha enquanto {@code match_engine.lua} não tiver
     * a chamada {@code HSET} após o {@code ZADD} do BID.</p>
     */
    @Test
    @DisplayName("[RED] BID NO_MATCH: Sorted Set populado e índice reverso criado")
    void whenBidNoMatch_thenSortedSetAndIndexArePopulated() {
        UUID orderId = UUID.randomUUID();
        BigDecimal price = new BigDecimal("100.00");
        BigDecimal qty   = new BigDecimal("5.00000000");

        // BUY em preço baixo — sem ASK contraparte → NO_MATCH
        RedisMatchEngineAdapter.MatchResult result = matchEngine.tryMatch(
                orderId, "user-test", UUID.randomUUID(),
                OrderType.BUY, price, qty, UUID.randomUUID());

        assertThat(result.matched()).isFalse();

        // Sorted set deve conter a ordem
        assertThat(redisTemplate.opsForZSet().zCard(bidsKey))
                .as("BID deve estar inserido em {vibranium}:bids após NO_MATCH")
                .isEqualTo(1L);

        // ⚠️ FASE RED: falha até match_engine.lua ter o HSET
        String indexEntry = (String) redisTemplate.opsForHash()
                .get(orderIndexKey, orderId.toString());

        assertThat(indexEntry)
                .as("[RED] Hash {vibranium}:order_index deve conter entrada para o orderId")
                .isNotNull();

        // Valida formato: bookKey|score|member
        String[] parts = indexEntry.split("\\|", 3);
        assertThat(parts).hasSize(3);
        assertThat(parts[0])
                .as("bookKey deve ser {vibranium}:bids")
                .isEqualTo(bidsKey);
        assertThat(parts[1])
                .as("score deve ser o preço × 1_000_000 como inteiro")
                .isEqualTo("100000000");
        assertThat(parts[2])
                .as("member (3ª parte) deve começar com orderId")
                .startsWith(orderId.toString());
    }

    // =========================================================================
    // Cenário 2 — ASK NO_MATCH: índice deve ser populado na inserção
    // =========================================================================

    /**
     * Quando uma ordem SELL não encontra BID contraparte, o script insere o ASK
     * em {@code {vibranium}:asks} <strong>E</strong> deve criar a entrada no índice.
     *
     * <p><strong>FASE RED:</strong> falha enquanto {@code match_engine.lua} não tiver
     * a chamada {@code HSET} após o {@code ZADD} do ASK.</p>
     */
    @Test
    @DisplayName("[RED] ASK NO_MATCH: Sorted Set populado e índice reverso criado")
    void whenAskNoMatch_thenSortedSetAndIndexArePopulated() {
        UUID orderId = UUID.randomUUID();
        BigDecimal price = new BigDecimal("999.00");
        BigDecimal qty   = new BigDecimal("3.00000000");

        // SELL em preço alto — sem BID contraparte → NO_MATCH
        RedisMatchEngineAdapter.MatchResult result = matchEngine.tryMatch(
                orderId, "user-test", UUID.randomUUID(),
                OrderType.SELL, price, qty, UUID.randomUUID());

        assertThat(result.matched()).isFalse();

        assertThat(redisTemplate.opsForZSet().zCard(asksKey))
                .as("ASK deve estar inserido em {vibranium}:asks após NO_MATCH")
                .isEqualTo(1L);

        // ⚠️ FASE RED
        String indexEntry = (String) redisTemplate.opsForHash()
                .get(orderIndexKey, orderId.toString());

        assertThat(indexEntry)
                .as("[RED] Hash {vibranium}:order_index deve conter entrada para o orderId")
                .isNotNull();

        String[] parts = indexEntry.split("\\|", 3);
        assertThat(parts).hasSize(3);
        assertThat(parts[0])
                .as("bookKey deve ser {vibranium}:asks")
                .isEqualTo(asksKey);
        assertThat(parts[2]).startsWith(orderId.toString());
    }

    // =========================================================================
    // Cenário 3 — Remoção O(1): ZREM + HDEL atômico via índice
    // =========================================================================

    /**
     * Dado que uma ordem foi inserida via {@code tryMatch} (populando o índice):
     * <ol>
     *   <li>Sorted Set contém a ordem.</li>
     *   <li>Hash contém o mapping.</li>
     *   <li>Após {@code removeFromBook}: Sorted Set vazio, Hash sem a entrada.</li>
     * </ol>
     *
     * <p><strong>FASE RED:</strong> falha até {@code removeFromBook} usar o Lua
     * script O(1) em vez de ZSCAN.</p>
     */
    @Test
    @DisplayName("[RED] removeFromBook: ZREM + HDEL atômico — Sorted Set e índice limpos")
    void whenRemoveFromBook_thenBothSortedSetAndIndexAreCleared() {
        UUID orderId = UUID.randomUUID();

        // Insere via tryMatch (NO_MATCH → idx populado)
        matchEngine.tryMatch(orderId, "user-atómic", UUID.randomUUID(),
                OrderType.BUY, new BigDecimal("200.00"), new BigDecimal("1.00000000"),
                UUID.randomUUID());

        // Pré-condição: sorted set e índice devem ter a entrada
        assertThat(redisTemplate.opsForZSet().zCard(bidsKey)).isEqualTo(1L);
        assertThat(redisTemplate.opsForHash().get(orderIndexKey, orderId.toString()))
                .isNotNull();

        // Remove — deve ser O(1) via índice
        matchEngine.removeFromBook(orderId, OrderType.BUY);

        // ⚠️ FASE RED: falha até removeFromBook usar Lua O(1)
        assertThat(redisTemplate.opsForZSet().zCard(bidsKey))
                .as("Sorted Set deve estar vazio após removeFromBook")
                .isZero();

        assertThat(redisTemplate.opsForHash().get(orderIndexKey, orderId.toString()))
                .as("[RED] Entrada no índice deve ser removida junto com ZREM (HDEL atômico)")
                .isNull();
    }

    // =========================================================================
    // Cenário 4 — Remoção idempotente: segunda chamada não lança exceção
    // =========================================================================

    /**
     * Garante que uma segunda chamada a {@code removeFromBook} para o mesmo orderId
     * (já removido) não lança exceção. Redis Cluster + Lua retorna 0 silenciosamente.
     */
    @Test
    @DisplayName("[RED] removeFromBook idempotente: segunda chamada não lança exceção")
    void whenRemoveFromBookCalledTwice_thenNoException() {
        UUID orderId = UUID.randomUUID();
        matchEngine.tryMatch(orderId, "user-idem", UUID.randomUUID(),
                OrderType.BUY, new BigDecimal("150.00"), new BigDecimal("2.00000000"),
                UUID.randomUUID());

        // 1ª remoção
        matchEngine.removeFromBook(orderId, OrderType.BUY);

        // 2ª remoção — não deve lançar exceção (idempotência)
        org.assertj.core.api.Assertions.assertThatCode(
                () -> matchEngine.removeFromBook(orderId, OrderType.BUY))
                .doesNotThrowAnyException();
    }

    // =========================================================================
    // Cenário 5 — PARTIAL_ASK: residual do ASK atualiza índice
    // =========================================================================

    /**
     * BID qty=3 vs ASK qty=10 → PARTIAL_ASK.
     * O residual do ASK (qty=7) é reinserido no livro. O índice deve ser atualizado
     * com o novo member (contendo qty=7 em vez de qty=10).
     *
     * <p><strong>FASE RED:</strong> falha até match_engine.lua ter HSET após ZADD do residual.</p>
     */
    @Test
    @DisplayName("[RED] PARTIAL_ASK: índice atualizado com member residual do ASK")
    void whenPartialAsk_thenIndexUpdatedWithResidualMember() {
        UUID askOrderId = UUID.randomUUID();
        BigDecimal price  = new BigDecimal("500.00");
        BigDecimal askQty = new BigDecimal("10.00000000");
        BigDecimal bidQty = new BigDecimal("3.00000000");

        // Insere ASK no livro via tryMatch (SELL NO_MATCH)
        matchEngine.tryMatch(askOrderId, "seller-x", UUID.randomUUID(),
                OrderType.SELL, price, askQty, UUID.randomUUID());

        // Pré-condição: ASK indexado
        assertThat(redisTemplate.opsForHash().get(orderIndexKey, askOrderId.toString()))
                .isNotNull();

        // BID bate parcialmente no ASK → PARTIAL_ASK
        UUID bidOrderId = UUID.randomUUID();
        matchEngine.tryMatch(bidOrderId, "buyer-y", UUID.randomUUID(),
                OrderType.BUY, price, bidQty, UUID.randomUUID());

        // ⚠️ FASE RED: falha até match_engine.lua ter HSET no ramo PARTIAL_ASK
        String updatedEntry = (String) redisTemplate.opsForHash()
                .get(orderIndexKey, askOrderId.toString());

        assertThat(updatedEntry)
                .as("[RED] Índice deve ainda ter entrada para o ASK residual (qty=7)")
                .isNotNull();

        // Member residual deve ter qty=7 na posição [4] (0-based) do pipe-delimited
        String member = updatedEntry.split("\\|", 3)[2];
        String residualQty = member.split("\\|")[3];
        assertThat(new BigDecimal(residualQty))
                .as("Member indexado deve refletir qty residual = 7")
                .isEqualByComparingTo(new BigDecimal("7.00000000"));
    }

    // =========================================================================
    // Cenário 6 — FULL MATCH: índice do ASK removido (contraparte consumida)
    // =========================================================================

    /**
     * BID qty=10 vs ASK qty=10 → FULL MATCH.
     * ASK é completamente consumido: deve ser removido do Sorted Set <strong>e</strong>
     * do índice.
     *
     * <p><strong>FASE RED:</strong> falha até match_engine.lua ter HDEL no ramo FULL.</p>
     */
    @Test
    @DisplayName("[RED] FULL MATCH: entrada do ASK removida do índice")
    void whenFullMatch_thenAskRemovedFromIndex() {
        UUID askOrderId = UUID.randomUUID();
        BigDecimal price = new BigDecimal("500.00");
        BigDecimal qty   = new BigDecimal("10.00000000");

        // ASK no livro
        matchEngine.tryMatch(askOrderId, "seller-full", UUID.randomUUID(),
                OrderType.SELL, price, qty, UUID.randomUUID());

        assertThat(redisTemplate.opsForHash().get(orderIndexKey, askOrderId.toString()))
                .as("Pré-condição: índice deve ter o ASK antes do match")
                .isNotNull();

        // BID completo — FULL MATCH
        matchEngine.tryMatch(UUID.randomUUID(), "buyer-full", UUID.randomUUID(),
                OrderType.BUY, price, qty, UUID.randomUUID());

        // ⚠️ FASE RED: falha até match_engine.lua ter HDEL no ramo FULL
        assertThat(redisTemplate.opsForZSet().zCard(asksKey))
                .as("ASK deve ter sido consumido do Sorted Set")
                .isZero();

        assertThat(redisTemplate.opsForHash().get(orderIndexKey, askOrderId.toString()))
                .as("[RED] ASK totalmente consumido deve ser removido do índice")
                .isNull();
    }

    // =========================================================================
    // Cenário 7 — Performance O(1): remover 1 ordem dentre 1000 em ≤ 5 ms
    // =========================================================================

    /**
     * Prova que a remoção via índice é O(1) mesmo com 1000 ordens no livro.
     *
     * <p>Setup: insere 1000 BIDs via {@code tryMatch} (populando o índice).
     * Medição: remove a ordem na posição 500 e valida que o tempo ≤ 5 ms.</p>
     *
     * <p><strong>Por que isso prova O(1):</strong> se fosse ZSCAN (O(n)), com
     * 1000 elementos e count=100, a iteração levaria em média 10 roundtrips ao Redis.
     * Com índice reverso, é exatamente 1 HGET + 1 ZREM + 1 HDEL = 3 comandos.</p>
     *
     * <p><strong>FASE RED:</strong> vai passar em tempo (mas não verifica índice limpo
     * ainda — o ZSCAN também é rápido para amostras pequenas). A verdadeira validação
     * é o {@code assertThat(indexEntry).isNull()}: falha até HDEL estar implementado.</p>
     */
    @Test
    @DisplayName("[RED] Performance O(1): 1000 ordens no livro, remoção ≤ 5 ms e índice limpo")
    void whenBulk1000Orders_removeOneSpecific_thenCompletesUnder5ms() {
        UUID targetOrderId = null;

        // Insere 1000 BIDs com preços distintos (sem cross, pois BUYs não se cruzam entre si)
        for (int i = 1; i <= 1000; i++) {
            UUID oid = UUID.randomUUID();
            if (i == 500) {
                targetOrderId = oid;
            }
            BigDecimal price = new BigDecimal(i); // 1..1000 BRL — sem ASK contraparte
            matchEngine.tryMatch(oid, "bulk-user-" + i, UUID.randomUUID(),
                    OrderType.BUY, price, new BigDecimal("1.00000000"), UUID.randomUUID());
        }

        assertThat(redisTemplate.opsForZSet().zCard(bidsKey))
                .as("1000 BIDs devem estar no livro")
                .isEqualTo(1000L);

        final UUID finalTargetOrderId = targetOrderId;

        // Mede apenas o removeFromBook (O(1) via índice)
        long startNanos = System.nanoTime();
        matchEngine.removeFromBook(finalTargetOrderId, OrderType.BUY);
        long elapsedNanos = System.nanoTime() - startNanos;

        long elapsedMs = elapsedNanos / 1_000_000;

        assertThat(elapsedMs)
                .as("Remoção O(1) deve completar em ≤ 5 ms (elapsed: %d ms)".formatted(elapsedMs))
                .isLessThanOrEqualTo(5L);

        assertThat(redisTemplate.opsForZSet().zCard(bidsKey))
                .as("1 ordem removida: devem restar 999 no Sorted Set")
                .isEqualTo(999L);

        // ⚠️ FASE RED: falha até HDEL implementado no Lua de remoção
        String indexEntry = (String) redisTemplate.opsForHash()
                .get(orderIndexKey, finalTargetOrderId.toString());
        assertThat(indexEntry)
                .as("[RED] Entrada do índice deve ser removida junto com ZREM (HDEL atômico)")
                .isNull();
    }
}
