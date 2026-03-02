package com.vibranium.orderservice.integration;

import io.lettuce.core.cluster.SlotHash;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AT-11.1 — FASE RED → GREEN: valida que as keys Redis configuradas no contexto Spring
 * contêm a hash tag {@code {vibranium}} necessária para compatibilidade com Redis Cluster.
 *
 * <h2>Ciclo RED → GREEN</h2>
 * <ul>
 *   <li><strong>RED:</strong> enquanto {@code application-test.yml} tiver
 *       {@code asks: vibranium:asks} (sem chaves), os testes falham.</li>
 *   <li><strong>GREEN:</strong> após atualizar para {@code asks: "{vibranium}:asks"},
 *       todos os testes passam.</li>
 * </ul>
 *
 * <p>Estende {@link AbstractIntegrationTest} para obter o {@code ApplicationContext}
 * completo com os containers Testcontainers já iniciados e as propriedades injetadas
 * via {@code @DynamicPropertySource}.</p>
 */
@DisplayName("AT-11.1 — Formato das keys Redis (FASE RED → GREEN)")
class RedisKeyFormatIT extends AbstractIntegrationTest {

    /**
     * Key dos ASKs injetada do {@code application-test.yml}.
     * Valor esperado após AT-11.1: {@code {vibranium}:asks}
     */
    @Value("${app.redis.keys.asks}")
    private String asksKey;

    /**
     * Key dos BIDs injetada do {@code application-test.yml}.
     * Valor esperado após AT-11.1: {@code {vibranium}:bids}
     */
    @Value("${app.redis.keys.bids}")
    private String bidsKey;

    /**
     * Key do índice de ordens injetada do {@code application-test.yml}.
     * Valor esperado após AT-11.1: {@code {vibranium}:order_index}
     */
    @Value("${app.redis.keys.order-index}")
    private String orderIndexKey;

    // =========================================================================
    // Testes de formato — FASE RED antes da mudança no application-test.yml
    // =========================================================================

    /**
     * ⚠️ <strong>FASE RED:</strong> falha enquanto {@code application-test.yml} não
     * tiver a hash tag {@code {vibranium}} na key de asks.
     *
     * <p>Prova do impacto: sem hash tag, o CRC16 é calculado sobre a key inteira
     * ({@code vibranium:asks}), resultando em um slot diferente de
     * {@code vibranium:bids} → {@code CROSSSLOT} em Redis Cluster.</p>
     */
    @Test
    @DisplayName("FASE RED → GREEN: asks key deve conter hash tag {vibranium}")
    void asksKey_mustContainHashTag() {
        int slot = SlotHash.getSlot(asksKey);
        System.out.printf("[KeyFormat] app.redis.keys.asks = '%s' → slot %d%n", asksKey, slot);

        assertThat(asksKey)
                .as("asks key deve usar hash tag {vibranium} para compatibilidade com Redis Cluster.\n"
                        + "  Valor atual  : '%s'\n"
                        + "  Valor esperado: '{vibranium}:asks'\n"
                        + "  Corrija em: resources/application-test.yml → app.redis.keys.asks",
                        asksKey)
                .startsWith("{vibranium}");
    }

    /**
     * ⚠️ <strong>FASE RED:</strong> falha enquanto {@code application-test.yml} não
     * tiver a hash tag {@code {vibranium}} na key de bids.
     */
    @Test
    @DisplayName("FASE RED → GREEN: bids key deve conter hash tag {vibranium}")
    void bidsKey_mustContainHashTag() {
        int slot = SlotHash.getSlot(bidsKey);
        System.out.printf("[KeyFormat] app.redis.keys.bids = '%s' → slot %d%n", bidsKey, slot);

        assertThat(bidsKey)
                .as("bids key deve usar hash tag {vibranium} para compatibilidade com Redis Cluster.\n"
                        + "  Valor atual  : '%s'\n"
                        + "  Valor esperado: '{vibranium}:bids'\n"
                        + "  Corrija em: resources/application-test.yml → app.redis.keys.bids",
                        bidsKey)
                .startsWith("{vibranium}");
    }

    /**
     * ⚠️ <strong>FASE RED:</strong> falha enquanto {@code application-test.yml} não
     * tiver a property {@code order-index} com a hash tag.
     */
    @Test
    @DisplayName("FASE RED → GREEN: order-index key deve conter hash tag {vibranium}")
    void orderIndexKey_mustContainHashTag() {
        int slot = SlotHash.getSlot(orderIndexKey);
        System.out.printf("[KeyFormat] app.redis.keys.order-index = '%s' → slot %d%n",
                orderIndexKey, slot);

        assertThat(orderIndexKey)
                .as("order-index key deve usar hash tag {vibranium}.\n"
                        + "  Valor atual  : '%s'\n"
                        + "  Valor esperado: '{vibranium}:order_index'\n"
                        + "  Corrija em: resources/application-test.yml → app.redis.keys.order-index",
                        orderIndexKey)
                .startsWith("{vibranium}");
    }

    /**
     * Verifica que todas as três keys do Match Engine compartilham o mesmo slot CRC16.
     * Garante que não haverá {@code CROSSSLOT} para nenhuma combinação delas em Redis Cluster.
     */
    @Test
    @DisplayName("asks, bids e order-index devem ter o mesmo hash slot (zero CROSSSLOT em cluster)")
    void allMatchEngineKeys_mustHaveSameHashSlot() {
        int slotAsks  = SlotHash.getSlot(asksKey);
        int slotBids  = SlotHash.getSlot(bidsKey);
        int slotOrder = SlotHash.getSlot(orderIndexKey);

        System.out.printf("[SlotCheck] asks       = '%s' → slot %d%n", asksKey,       slotAsks);
        System.out.printf("[SlotCheck] bids       = '%s' → slot %d%n", bidsKey,       slotBids);
        System.out.printf("[SlotCheck] orderIndex = '%s' → slot %d%n", orderIndexKey, slotOrder);

        assertThat(slotAsks)
                .as("asks (slot=%d) e bids (slot=%d) devem estar no mesmo slot para que o "
                        + "script Lua execute sem CROSSSLOT em Redis Cluster", slotAsks, slotBids)
                .isEqualTo(slotBids);

        assertThat(slotOrder)
                .as("order-index (slot=%d) deve ter o mesmo slot de asks/bids (slot=%d)",
                        slotOrder, slotAsks)
                .isEqualTo(slotAsks);
    }
}
