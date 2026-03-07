package com.vibranium.orderservice.integration;

import io.lettuce.core.RedisURI;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;
import io.lettuce.core.cluster.models.partitions.RedisClusterNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * AT-15 — Teste de Failover Automático do Redis Cluster.
 *
 * <p>Cenário de validação:</p>
 * <ol>
 *   <li>Inserir ordens no Order Book via script Lua</li>
 *   <li>Identificar o master que detém o slot da hash tag {@code {vibranium}}</li>
 *   <li>Simular falha do master via {@code CLUSTER FAILOVER} na replica</li>
 *   <li>Verificar que a replica é promovida a master</li>
 *   <li>Confirmar que as ordens ainda estão no Order Book</li>
 *   <li>Executar match_engine.lua pós-failover → funciona</li>
 * </ol>
 *
 * <p><strong>Estratégia de failover:</strong> usa {@code CLUSTER FAILOVER} na replica
 * em vez de matar o container do master. Isso é mais confiável em Testcontainers
 * (imagem grokzen conta com IP fixo 0.0.0.0 e portas internas), e exercita o mesmo
 * mecanismo de promoção que o cluster usa em falha real (epoch increment + vote).</p>
 *
 * <p>Execução isolada: {@code mvn test -Dgroups=redis-cluster-ha}</p>
 */
@Testcontainers
@Tag("redis-cluster-ha")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("AT-15 — Redis Cluster Failover (master down → replica promoted)")
@DisabledIfEnvironmentVariable(
        named = "SKIP_REDIS_CLUSTER_TESTS",
        matches = "true",
        disabledReason = "Pulado: SKIP_REDIS_CLUSTER_TESTS=true")
class RedisClusterFailoverIT {

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
        // Habilita refresh de topologia para detectar failover
        clusterClient.setOptions(
                io.lettuce.core.cluster.ClusterClientOptions.builder()
                        .topologyRefreshOptions(
                                io.lettuce.core.cluster.ClusterTopologyRefreshOptions.builder()
                                        .enableAllAdaptiveRefreshTriggers()
                                        .enablePeriodicRefresh(Duration.ofSeconds(5))
                                        .build())
                        .build());

        connection = clusterClient.connect();

        matchEngineLua = new ResourceScriptSource(
                new ClassPathResource("lua/match_engine.lua")).getScriptAsString();

        System.out.printf("[Failover] Cluster conectado com %d nós%n",
                connection.getPartitions().size());
    }

    @AfterAll
    static void tearDown() {
        if (connection != null) connection.close();
        if (clusterClient != null) clusterClient.shutdown();
    }

    /**
     * Fase 1: Insere ordens no Order Book e verifica que estão no livro.
     */
    @Test
    @Order(1)
    @DisplayName("1. Insere ordens no book via match_engine.lua (pre-failover)")
    void insertOrders_preFailover() {
        RedisAdvancedClusterCommands<String, String> commands = connection.sync();

        // Limpa estado anterior
        commands.del(ASKS_KEY, BIDS_KEY, ORDER_INDEX_KEY);

        final long priceScore = 50_000_000_000L;

        // Insere 3 ASKs a preços diferentes
        for (int i = 1; i <= 3; i++) {
            long score = priceScore + (i * 100_000_000L);
            List<Object> result = commands.eval(
                    matchEngineLua,
                    io.lettuce.core.ScriptOutputType.MULTI,
                    new String[]{ASKS_KEY, BIDS_KEY, ORDER_INDEX_KEY},
                    "SELL", String.valueOf(score),
                    String.format("ask-%03d|usr-%03d|wal-%03d|10.00000000|cor-%03d|170000000%04d",
                            i, i, i, i, i),
                    "10.00000000");

            assertThat(result.get(0).toString())
                    .as("ASK %d deve ser inserida no livro (NO_MATCH)", i)
                    .isEqualTo("NO_MATCH");
        }

        // Insere 2 BIDs a preços baixos (sem match com ASKs)
        for (int i = 1; i <= 2; i++) {
            long score = priceScore - (i * 100_000_000L);
            List<Object> result = commands.eval(
                    matchEngineLua,
                    io.lettuce.core.ScriptOutputType.MULTI,
                    new String[]{ASKS_KEY, BIDS_KEY, ORDER_INDEX_KEY},
                    "BUY", String.valueOf(score),
                    String.format("bid-%03d|usr-b%03d|wal-b%03d|5.00000000|cor-b%03d|170000000%04d",
                            i, i, i, i, i),
                    "5.00000000");

            assertThat(result.get(0).toString())
                    .as("BID %d deve ser inserido no livro (NO_MATCH)", i)
                    .isEqualTo("NO_MATCH");
        }

        // Verifica quantidades
        assertThat(commands.zcard(ASKS_KEY))
                .as("Deve ter 3 ASKs no livro")
                .isEqualTo(3L);

        assertThat(commands.zcard(BIDS_KEY))
                .as("Deve ter 2 BIDs no livro")
                .isEqualTo(2L);

        System.out.printf("[Failover] Pre-failover: %d ASKs, %d BIDs no book%n",
                commands.zcard(ASKS_KEY), commands.zcard(BIDS_KEY));
    }

    /**
     * Fase 2: Força failover via CLUSTER FAILOVER e verifica promoção da replica.
     *
     * <p>Identificamos o master que detém o slot da hash tag {vibranium} e
     * forçamos sua replica a assumir via CLUSTER FAILOVER.</p>
     */
    @Test
    @Order(2)
    @DisplayName("2. Replica promovida após CLUSTER FAILOVER do master do slot {vibranium}")
    void replicaPromoted_afterFailover() throws Exception {
        RedisAdvancedClusterCommands<String, String> commands = connection.sync();

        // Calcula o slot da hash tag {vibranium}
        int vibraniumSlot = io.lettuce.core.cluster.SlotHash.getSlot("vibranium");
        System.out.printf("[Failover] Slot de {vibranium}: %d%n", vibraniumSlot);

        // Encontra o master que detém o slot {vibranium}
        RedisClusterNode masterNode = connection.getPartitions().stream()
                .filter(node -> node.is(RedisClusterNode.NodeFlag.UPSTREAM))
                .filter(node -> node.getSlots().contains(vibraniumSlot))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Nenhum master encontrado para o slot " + vibraniumSlot));

        System.out.printf("[Failover] Master do slot %d: %s (%s:%d)%n",
                vibraniumSlot, masterNode.getNodeId(),
                masterNode.getUri().getHost(), masterNode.getUri().getPort());

        // Encontra a replica desse master
        String masterNodeId = masterNode.getNodeId();
        RedisClusterNode replicaNode = connection.getPartitions().stream()
                .filter(node -> node.is(RedisClusterNode.NodeFlag.REPLICA))
                .filter(node -> masterNodeId.equals(node.getSlaveOf()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Nenhuma replica encontrada para master " + masterNodeId));

        System.out.printf("[Failover] Replica a ser promovida: %s (%s:%d)%n",
                replicaNode.getNodeId(),
                replicaNode.getUri().getHost(), replicaNode.getUri().getPort());

        // Conecta diretamente na replica e força failover
        io.lettuce.core.RedisClient replicaClient = io.lettuce.core.RedisClient.create(
                RedisURI.create(CLUSTER.getHost(),
                        CLUSTER.getMappedPort(replicaNode.getUri().getPort())));

        try (var replicaConn = replicaClient.connect()) {
            String failoverResult = replicaConn.sync().clusterFailover(true);
            System.out.printf("[Failover] CLUSTER FAILOVER result: %s%n", failoverResult);
        } finally {
            replicaClient.shutdown();
        }

        // Aguarda que a topologia seja atualizada e a replica vire master
        await().atMost(15, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    // Força refresh da topologia
                    clusterClient.refreshPartitions();

                    String replicaId = replicaNode.getNodeId();
                    RedisClusterNode updatedNode = connection.getPartitions().stream()
                            .filter(n -> n.getNodeId().equals(replicaId))
                            .findFirst()
                            .orElseThrow();

                    System.out.printf("[Failover] Estado atual da replica: flags=%s%n",
                            updatedNode.getFlags());

                    assertThat(updatedNode.is(RedisClusterNode.NodeFlag.UPSTREAM))
                            .as("Replica %s deve ter sido promovida a master", replicaId)
                            .isTrue();
                });

        System.out.println("[Failover] Replica promovida com sucesso!");
    }

    /**
     * Fase 3: Verifica que as ordens ainda estão no Order Book após failover.
     */
    @Test
    @Order(3)
    @DisplayName("3. Ordens preservadas no book após failover")
    void ordersPreserved_afterFailover() {
        RedisAdvancedClusterCommands<String, String> commands = connection.sync();

        // Aguarda estabilização com retry
        await().atMost(10, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    Long asksCount = commands.zcard(ASKS_KEY);
                    Long bidsCount = commands.zcard(BIDS_KEY);

                    System.out.printf("[Failover] Post-failover: %d ASKs, %d BIDs%n",
                            asksCount, bidsCount);

                    assertThat(asksCount)
                            .as("3 ASKs devem estar preservadas após failover")
                            .isEqualTo(3L);

                    assertThat(bidsCount)
                            .as("2 BIDs devem estar preservados após failover")
                            .isEqualTo(2L);
                });

        // Verifica que o índice reverso também está preservado
        Long indexSize = commands.hlen(ORDER_INDEX_KEY);
        System.out.printf("[Failover] order_index entries: %d%n", indexSize);

        assertThat(indexSize)
                .as("Índice reverso deve ter 5 entradas (3 ASKs + 2 BIDs)")
                .isEqualTo(5L);
    }

    /**
     * Fase 4: Executa match_engine.lua após failover — deve funcionar normalmente.
     */
    @Test
    @Order(4)
    @DisplayName("4. match_engine.lua funciona após failover (match ASK existente)")
    void matchEngineLua_worksAfterFailover() {
        RedisAdvancedClusterCommands<String, String> commands = connection.sync();

        // BUY com preço alto o suficiente para fazer match com a ASK mais barata
        // ASK mais barataN: score = 50_000_000_000 + 100_000_000 = 50_100_000_000
        long buyPrice = 50_100_000_000L;

        List<Object> result = commands.eval(
                matchEngineLua,
                io.lettuce.core.ScriptOutputType.MULTI,
                new String[]{ASKS_KEY, BIDS_KEY, ORDER_INDEX_KEY},
                "BUY", String.valueOf(buyPrice),
                "bid-post-001|usr-post-001|wal-post-001|10.00000000|cor-post-001|1700000000100",
                "10.00000000");

        System.out.printf("[Failover] Post-failover match result: %s%n", result);

        assertThat(result).isNotEmpty();
        assertThat(result.get(0).toString())
                .as("Deve haver match com ASK existente após failover")
                .isEqualTo("MULTI_MATCH");

        // Uma ASK deve ter sido consumida
        assertThat(commands.zcard(ASKS_KEY))
                .as("Uma ASK deve ter sido consumida pelo match")
                .isEqualTo(2L);

        // Cleanup
        commands.del(ASKS_KEY, BIDS_KEY, ORDER_INDEX_KEY);
    }

    /**
     * Classe interna para erros de asserção com nome descritivo.
     */
    private static class AssertionError extends RuntimeException {
        AssertionError(String message) {
            super(message);
        }
    }
}
