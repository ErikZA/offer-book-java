package com.vibranium.orderservice.integration;

import io.lettuce.core.RedisURI;
import io.lettuce.core.cluster.RedisClusterClient;
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
 * AT-15 — Teste de Conectividade do Redis Cluster.
 *
 * <p>Valida que um cluster de 6 nós (3 masters + 3 replicas) está healthy,
 * todos os 16384 slots estão cobertos, e o script Lua {@code match_engine.lua}
 * executa sem erros em modo cluster.</p>
 *
 * <p>Usa a imagem {@code grokzen/redis-cluster:7.0.0} que provê 6 nós
 * automaticamente nas portas 7000–7005.</p>
 *
 * <p>Execução isolada: {@code mvn test -Dgroups=redis-cluster-ha}</p>
 */
@Testcontainers
@Tag("redis-cluster-ha")
@DisplayName("AT-15 — Redis Cluster Connectivity (6 nodes)")
@DisabledIfEnvironmentVariable(
        named = "SKIP_REDIS_CLUSTER_TESTS",
        matches = "true",
        disabledReason = "Pulado: SKIP_REDIS_CLUSTER_TESTS=true")
class RedisClusterConnectivityIT {

    /**
     * Redis Cluster: 3 masters + 3 replicas (portas 7000–7005).
     * IP=0.0.0.0 permite acesso de qualquer interface.
     */
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

    @BeforeAll
    static void setUp() throws IOException {
        String host = CLUSTER.getHost();

        // Constrói URIs para todos os 6 nós mapeados
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

        System.out.printf("[ClusterConnectivity] Conectado ao cluster com %d nós%n",
                connection.getPartitions().size());
    }

    @AfterAll
    static void tearDown() {
        if (connection != null) connection.close();
        if (clusterClient != null) clusterClient.shutdown();
    }

    /**
     * Verifica que o cluster tem exatamente 6 nós visíveis via CLUSTER NODES.
     */
    @Test
    @DisplayName("Cluster deve ter 6 nós (3 masters + 3 replicas)")
    void cluster_shouldHave6Nodes() {
        long totalNodes = connection.getPartitions().stream().count();

        System.out.printf("[ClusterConnectivity] Total nós: %d%n", totalNodes);

        assertThat(totalNodes)
                .as("Cluster deve ter 6 nós (3 masters + 3 replicas)")
                .isEqualTo(6);
    }

    /**
     * Verifica que existem exatamente 3 masters no cluster.
     */
    @Test
    @DisplayName("Cluster deve ter exatamente 3 masters")
    void cluster_shouldHave3Masters() {
        long masterCount = connection.getPartitions().stream()
                .filter(node -> node.is(RedisClusterNode.NodeFlag.UPSTREAM))
                .count();

        System.out.printf("[ClusterConnectivity] Masters: %d%n", masterCount);

        assertThat(masterCount)
                .as("Cluster deve ter 3 masters para quorum de failover")
                .isEqualTo(3);
    }

    /**
     * Verifica que existem exatamente 3 replicas no cluster.
     */
    @Test
    @DisplayName("Cluster deve ter exatamente 3 replicas")
    void cluster_shouldHave3Replicas() {
        long replicaCount = connection.getPartitions().stream()
                .filter(node -> node.is(RedisClusterNode.NodeFlag.REPLICA))
                .count();

        System.out.printf("[ClusterConnectivity] Replicas: %d%n", replicaCount);

        assertThat(replicaCount)
                .as("Cluster deve ter 3 replicas (1 por master)")
                .isEqualTo(3);
    }

    /**
     * Verifica que o cluster está em estado "ok" (todos os 16384 slots cobertos).
     */
    @Test
    @DisplayName("Cluster state deve ser 'ok' (16384 slots cobertos)")
    void cluster_stateShouldBeOk() {
        RedisAdvancedClusterCommands<String, String> commands = connection.sync();
        String clusterInfo = commands.clusterInfo();

        System.out.printf("[ClusterConnectivity] cluster_info:%n%s%n", clusterInfo);

        assertThat(clusterInfo)
                .as("Estado do cluster deve ser 'ok'")
                .contains("cluster_state:ok");

        assertThat(clusterInfo)
                .as("Todos os 16384 slots devem estar cobertos")
                .contains("cluster_slots_ok:16384");
    }

    /**
     * Executa o script Lua match_engine.lua no cluster com hash tags {vibranium}.
     * Valida que o script funciona sem CROSSSLOT em modo cluster.
     */
    @Test
    @DisplayName("match_engine.lua executa sem erro no cluster com hash tags {vibranium}")
    void matchEngineLua_executesInCluster() {
        RedisAdvancedClusterCommands<String, String> commands = connection.sync();

        // Limpa keys antes do teste
        commands.del("{vibranium}:asks", "{vibranium}:bids", "{vibranium}:order_index");

        // Executa BUY sem contraparte → NO_MATCH
        List<Object> result = commands.eval(
                matchEngineLua,
                io.lettuce.core.ScriptOutputType.MULTI,
                new String[]{"{vibranium}:asks", "{vibranium}:bids", "{vibranium}:order_index"},
                "BUY", "50000000000",
                "ord-conn-001|usr-001|wal-001|10.00000000|cor-001|1700000000000",
                "10.00000000");

        System.out.printf("[ClusterConnectivity] Lua result: %s%n", result);

        assertThat(result).isNotEmpty();
        assertThat(result.get(0).toString())
                .as("Sem ASK disponível → NO_MATCH")
                .isEqualTo("NO_MATCH");

        // Verifica que o BID foi inserido no livro
        assertThat(commands.zcard("{vibranium}:bids"))
                .as("BID deve ter sido inserido em {vibranium}:bids")
                .isEqualTo(1L);

        // Cleanup
        commands.del("{vibranium}:asks", "{vibranium}:bids", "{vibranium}:order_index");
    }

    /**
     * Executa match FULL no cluster: insere ASK, depois BUY com mesmo preço.
     * Valida atomicidade do script Lua em ambiente cluster.
     */
    @Test
    @DisplayName("match FULL (BUY = ASK) funciona atomicamente no cluster")
    void fullMatch_worksInCluster() {
        RedisAdvancedClusterCommands<String, String> commands = connection.sync();

        commands.del("{vibranium}:asks", "{vibranium}:bids", "{vibranium}:order_index");

        // Insere ASK diretamente
        final long priceScore = 50_000_000_000L;
        commands.zadd("{vibranium}:asks", (double) priceScore,
                "ask-conn-001|usr-001|wal-001|5.00000000|cor-001|1700000000000");
        commands.hset("{vibranium}:order_index", "ask-conn-001",
                "{vibranium}:asks|" + priceScore + "|ask-conn-001|usr-001|wal-001|5.00000000|cor-001|1700000000000");

        // BUY com qty=5 e price=500 → match FULL
        List<Object> result = commands.eval(
                matchEngineLua,
                io.lettuce.core.ScriptOutputType.MULTI,
                new String[]{"{vibranium}:asks", "{vibranium}:bids", "{vibranium}:order_index"},
                "BUY", String.valueOf(priceScore),
                "bid-conn-001|usr-002|wal-002|5.00000000|cor-002|1700000000001",
                "5.00000000");

        System.out.printf("[ClusterConnectivity] Full match result: %s%n", result);

        assertThat(result).isNotEmpty();
        assertThat(result.get(0).toString())
                .as("Match deve ser MULTI_MATCH (full fill)")
                .isEqualTo("MULTI_MATCH");

        // ASK totalmente consumido
        assertThat(commands.zcard("{vibranium}:asks"))
                .as("ASK deve ter sido removido após match FULL")
                .isZero();

        // Cleanup
        commands.del("{vibranium}:asks", "{vibranium}:bids", "{vibranium}:order_index");
    }
}
