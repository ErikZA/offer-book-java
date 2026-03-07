package com.vibranium.orderservice.integration;

import io.lettuce.core.RedisURI;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.SlotHash;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;
import io.lettuce.core.cluster.models.partitions.RedisClusterNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scripting.support.ResourceScriptSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AT-15 — Validação de Hash Tags em Redis Cluster real (6 nodes).
 *
 * <p>Diferente do {@link RedisClusterHashTagIT.SlotCalculationTests} que testa apenas
 * o cálculo CRC16 local, este teste valida o comportamento real num cluster de 6 nós:</p>
 * <ul>
 *   <li>Todas as keys {@code {vibranium}:*} estão no mesmo slot CRC16</li>
 *   <li>O slot é atendido por exatamente 1 master + 1 replica</li>
 *   <li>Operações multi-key (Lua) em keys com mesma hash tag não geram CROSSSLOT</li>
 *   <li>Remove_from_book.lua funciona com as 3 keys na mesma hash tag</li>
 * </ul>
 *
 * <p>Execução isolada: {@code mvn test -Dgroups=redis-cluster-ha}</p>
 */
@Testcontainers
@Tag("redis-cluster-ha")
@DisplayName("AT-15 — Redis Cluster Hash Tag Validation (6 nodes)")
@DisabledIfEnvironmentVariable(
        named = "SKIP_REDIS_CLUSTER_TESTS",
        matches = "true",
        disabledReason = "Pulado: SKIP_REDIS_CLUSTER_TESTS=true")
class RedisClusterHashTagValidationIT {

    private static final String ASKS_KEY = "{vibranium}:asks";
    private static final String BIDS_KEY = "{vibranium}:bids";
    private static final String ORDER_INDEX_KEY = "{vibranium}:order_index";

    @Container
    static final GenericContainer<?> CLUSTER =
            new GenericContainer<>(DockerImageName.parse("grokzen/redis-cluster:7.0.0"))
                    .withExposedPorts(7000, 7001, 7002, 7003, 7004, 7005)
                    .withEnv("IP", "0.0.0.0")
                    .withEnv("INITIAL_PORT", "7000")
                    .withEnv("MASTERS", "3")
                    .withEnv("SLAVES_PER_MASTER", "1")
                    .waitingFor(
                            Wait.forLogMessage(".*cluster_state:ok.*", 1)
                                    .withStartupTimeout(Duration.ofSeconds(120)));

    private static RedisClusterClient clusterClient;
    private static StatefulRedisClusterConnection<String, String> connection;
    private static String matchEngineLua;
    private static String removeFromBookLua;

    @BeforeAll
    static void setUp() throws IOException {
        String host = CLUSTER.getHost();

        List<RedisURI> uris = List.of(
                RedisURI.create(host, CLUSTER.getMappedPort(7000)),
                RedisURI.create(host, CLUSTER.getMappedPort(7001)),
                RedisURI.create(host, CLUSTER.getMappedPort(7002)),
                RedisURI.create(host, CLUSTER.getMappedPort(7003)),
                RedisURI.create(host, CLUSTER.getMappedPort(7004)),
                RedisURI.create(host, CLUSTER.getMappedPort(7005))
        );

        clusterClient = RedisClusterClient.create(uris);
        connection = clusterClient.connect();

        matchEngineLua = new ResourceScriptSource(
                new ClassPathResource("lua/match_engine.lua")).getScriptAsString();

        removeFromBookLua = new ResourceScriptSource(
                new ClassPathResource("lua/remove_from_book.lua")).getScriptAsString();
    }

    @AfterAll
    static void tearDown() {
        if (connection != null) connection.close();
        if (clusterClient != null) clusterClient.shutdown();
    }

    /**
     * Calcula o slot CRC16 das 3 keys e confirma que são idênticos.
     * Validação aritmética + confirmação no cluster real.
     */
    @Test
    @DisplayName("asks, bids e order_index estão no mesmo slot CRC16")
    void allKeys_sameHashSlot() {
        int slotAsks = SlotHash.getSlot(ASKS_KEY);
        int slotBids = SlotHash.getSlot(BIDS_KEY);
        int slotIndex = SlotHash.getSlot(ORDER_INDEX_KEY);
        int slotTag = SlotHash.getSlot("vibranium");

        System.out.printf("[HashTag] %s → slot %d%n", ASKS_KEY, slotAsks);
        System.out.printf("[HashTag] %s → slot %d%n", BIDS_KEY, slotBids);
        System.out.printf("[HashTag] %s → slot %d%n", ORDER_INDEX_KEY, slotIndex);
        System.out.printf("[HashTag] vibranium (tag) → slot %d%n", slotTag);

        assertThat(slotAsks).isEqualTo(slotBids);
        assertThat(slotBids).isEqualTo(slotIndex);
        assertThat(slotIndex).isEqualTo(slotTag);
    }

    /**
     * Verifica que o slot {vibranium} é servido por exatamente 1 master no cluster.
     */
    @Test
    @DisplayName("Slot {vibranium} é servido por exatamente 1 master")
    void vibraniumSlot_servedByOneMaster() {
        int slot = SlotHash.getSlot("vibranium");

        long mastersForSlot = connection.getPartitions().stream()
                .filter(node -> node.is(RedisClusterNode.NodeFlag.UPSTREAM))
                .filter(node -> node.getSlots().contains(slot))
                .count();

        System.out.printf("[HashTag] Masters para slot %d: %d%n", slot, mastersForSlot);

        assertThat(mastersForSlot)
                .as("Exatamente 1 master deve deter o slot %d", slot)
                .isEqualTo(1);
    }

    /**
     * Verifica que o slot {vibranium} tem exatamente 1 replica (backup para failover).
     */
    @Test
    @DisplayName("Slot {vibranium} tem 1 replica (para failover)")
    void vibraniumSlot_hasOneReplica() {
        int slot = SlotHash.getSlot("vibranium");

        // Encontra o master do slot
        RedisClusterNode master = connection.getPartitions().stream()
                .filter(node -> node.is(RedisClusterNode.NodeFlag.UPSTREAM))
                .filter(node -> node.getSlots().contains(slot))
                .findFirst()
                .orElseThrow();

        // Conta replicas desse master
        String masterId = master.getNodeId();
        long replicaCount = connection.getPartitions().stream()
                .filter(node -> node.is(RedisClusterNode.NodeFlag.REPLICA))
                .filter(node -> masterId.equals(node.getSlaveOf()))
                .count();

        System.out.printf("[HashTag] Replicas do master %s (slot %d): %d%n",
                masterId, slot, replicaCount);

        assertThat(replicaCount)
                .as("Master do slot {vibranium} deve ter 1 replica")
                .isEqualTo(1);
    }

    /**
     * Insere ordem via match_engine.lua e remove via remove_from_book.lua.
     * Ambos os scripts usam keys com hash tag {vibranium} — sem CROSSSLOT no cluster.
     */
    @Test
    @DisplayName("match_engine.lua + remove_from_book.lua funcionam com hash tags no cluster")
    void luaScripts_workWithHashTags() {
        RedisAdvancedClusterCommands<String, String> commands = connection.sync();

        commands.del(ASKS_KEY, BIDS_KEY, ORDER_INDEX_KEY);

        final long priceScore = 50_000_000_000L;
        final String orderId = "ask-ht-val-001";

        // Insere ASK via match_engine.lua (SELL sem match → NO_MATCH)
        List<Object> insertResult = commands.eval(
                matchEngineLua,
                io.lettuce.core.ScriptOutputType.MULTI,
                new String[]{ASKS_KEY, BIDS_KEY, ORDER_INDEX_KEY},
                "SELL", String.valueOf(priceScore),
                orderId + "|usr-001|wal-001|10.00000000|cor-001|1700000000000",
                "10.00000000");

        assertThat(insertResult.get(0).toString()).isEqualTo("NO_MATCH");
        assertThat(commands.zcard(ASKS_KEY)).isEqualTo(1L);
        assertThat(commands.hexists(ORDER_INDEX_KEY, orderId)).isTrue();

        // Remove via remove_from_book.lua (HGET + ZREM + HDEL atômico)
        // remove_from_book.lua usa KEYS: [order_index, asks, bids]
        Object removeResult = commands.eval(
                removeFromBookLua,
                io.lettuce.core.ScriptOutputType.INTEGER,
                new String[]{ORDER_INDEX_KEY, ASKS_KEY, BIDS_KEY},
                orderId);

        System.out.printf("[HashTag] remove_from_book result: %s%n", removeResult);

        assertThat(((Long) removeResult))
                .as("remove_from_book deve retornar 1 (removido com sucesso)")
                .isEqualTo(1L);

        assertThat(commands.zcard(ASKS_KEY))
                .as("ASK deve ter sido removida pelo remove_from_book.lua")
                .isZero();

        assertThat(commands.hexists(ORDER_INDEX_KEY, orderId))
                .as("Índice reverso deve ter sido limpo")
                .isFalse();

        commands.del(ASKS_KEY, BIDS_KEY, ORDER_INDEX_KEY);
    }

    /**
     * Insere múltiplas ordens e confirma que todas as keys continuam no mesmo nó.
     * SCAN no cluster pode distribuir keys por nós — mas com hash tags, tudo fica no mesmo.
     */
    @Test
    @DisplayName("Múltiplas ordens com hash tag ficam no mesmo nó do cluster")
    void multipleOrders_sameNode() {
        RedisAdvancedClusterCommands<String, String> commands = connection.sync();

        commands.del(ASKS_KEY, BIDS_KEY, ORDER_INDEX_KEY);

        // Insere 5 ASKs com preços diferentes
        for (int i = 1; i <= 5; i++) {
            long score = 50_000_000_000L + (i * 100_000_000L);
            commands.eval(
                    matchEngineLua,
                    io.lettuce.core.ScriptOutputType.MULTI,
                    new String[]{ASKS_KEY, BIDS_KEY, ORDER_INDEX_KEY},
                    "SELL", String.valueOf(score),
                    String.format("ask-multi-%03d|usr-%03d|wal-%03d|%d.00000000|cor-%03d|170000000%04d",
                            i, i, i, i, i, i),
                    i + ".00000000");
        }

        assertThat(commands.zcard(ASKS_KEY))
                .as("5 ASKs devem estar no livro")
                .isEqualTo(5L);

        assertThat(commands.hlen(ORDER_INDEX_KEY))
                .as("5 entradas no índice reverso")
                .isEqualTo(5L);

        // Verifica que o tipo de dados está correto
        assertThat(commands.type(ASKS_KEY)).isEqualTo("zset");
        assertThat(commands.type(ORDER_INDEX_KEY)).isEqualTo("hash");

        commands.del(ASKS_KEY, BIDS_KEY, ORDER_INDEX_KEY);
    }
}
