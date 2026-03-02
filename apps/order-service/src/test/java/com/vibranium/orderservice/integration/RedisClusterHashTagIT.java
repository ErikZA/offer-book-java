package com.vibranium.orderservice.integration;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.cluster.SlotHash;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * AT-11.1 — Validação de Hash Tags Redis para compatibilidade com Redis Cluster.
 *
 * <h2>Problema</h2>
 * <p>Redis Cluster exige que todos os {@code KEYS[]} passados a um script Lua
 * ({@code EVAL}/{@code EVALSHA}) estejam no mesmo <em>hash slot</em> (0–16383).
 * Sem hash tags, os slots de {@code vibranium:asks} e {@code vibranium:bids} diferem:</p>
 * <pre>
 *   CRC16("vibranium:asks") % 16384 → slot α
 *   CRC16("vibranium:bids") % 16384 → slot β   (α ≠ β)
 *   → Redis Cluster → CROSSSLOT Keys in request don't hash to the same slot
 * </pre>
 *
 * <h2>Solução — Hash Tags {@code {vibranium}}</h2>
 * <pre>
 *   O slot é calculado APENAS sobre o conteúdo entre { }:
 *   CRC16("{vibranium}:asks") → usa CRC16("vibranium") → slot γ
 *   CRC16("{vibranium}:bids") → usa CRC16("vibranium") → slot γ  (idêntico)
 *   → Mesma slot → script Lua executa sem CROSSSLOT em qualquer nó do cluster
 * </pre>
 *
 * <h2>Como o Redis calcula o hash slot</h2>
 * <ol>
 *   <li>Se a key contém {@code {tag}}, extrai a substring entre as chaves</li>
 *   <li>Aplica CRC16 (CCITT-XModem) sobre a substring extraída</li>
 *   <li>Resultado {@code % 16384} → slot (0–16383)</li>
 *   <li>Keys com a <strong>mesma tag</strong> → mesmo CRC16 → <strong>mesmo slot</strong></li>
 * </ol>
 *
 * <h2>Estrutura dos testes</h2>
 * <ol>
 *   <li>{@link SlotCalculationTests} — cálculo puro CRC16 (sem container, sem Spring)</li>
 *   <li>{@link RedisKeyFormatIT} — valida formato no contexto Spring (FASE RED → GREEN)</li>
 *   <li>{@link RedisClusterCrossSlotTest} — execução real no Redis Cluster (Testcontainers)</li>
 * </ol>
 *
 * @see RedisKeyFormatIT
 * @see <a href="https://redis.io/docs/reference/cluster-spec/#hash-tags">Redis Cluster: Hash Tags</a>
 */
class RedisClusterHashTagIT {

    // =========================================================================
    // 1. Cálculo de slot CRC16 — sem container, sem Spring
    //    Utiliza io.lettuce.core.cluster.SlotHash (incluído via spring-boot-starter-data-redis)
    // =========================================================================

    /**
     * Testes puramente aritméticos do algoritmo CRC16 do Redis.
     * Executáveis em qualquer ambiente, sem Docker ou Spring.
     */
    @Nested
    @DisplayName("1. Cálculo CRC16 / Hash Slot (sem container, sem Spring)")
    class SlotCalculationTests {

        /**
         * ⚠️ <strong>FASE RED</strong>: as keys SEM hash tag resultam em slots diferentes.
         *
         * <p>Documenta o bug que AT-11.1 corrige: com {@code vibranium:asks} e
         * {@code vibranium:bids}, o Redis Cluster retornaria {@code CROSSSLOT} ao
         * executar o {@code match_engine.lua}.</p>
         */
        @Test
        @DisplayName("FASE RED (prova do bug): vibranium:asks e vibranium:bids têm slots DIFERENTES")
        void keysWithoutHashTag_haveDifferentSlots() {
            int slotAsks = SlotHash.getSlot("vibranium:asks");
            int slotBids = SlotHash.getSlot("vibranium:bids");

            System.out.printf("[SlotCalc] vibranium:asks  → slot %d%n", slotAsks);
            System.out.printf("[SlotCalc] vibranium:bids  → slot %d%n", slotBids);
            System.out.printf("[SlotCalc] Mesmos slots?   → %b  (deve ser false — prova o bug)%n",
                    slotAsks == slotBids);

            assertThat(slotAsks)
                    .as("vibranium:asks e vibranium:bids DEVEM estar em slots diferentes — "
                            + "esta é a causa raiz do CROSSSLOT em Redis Cluster")
                    .isNotEqualTo(slotBids);
        }

        /**
         * ✅ Verifica que as keys COM hash tag {@code {vibranium}} resultam no MESMO slot.
         * Valida matematicamente a correção aplicada no {@code application.yaml}.
         */
        @Test
        @DisplayName("keys com hash tag {vibranium} têm o MESMO slot CRC16")
        void keysWithHashTag_haveSameSlot() {
            int slotAsks      = SlotHash.getSlot("{vibranium}:asks");
            int slotBids      = SlotHash.getSlot("{vibranium}:bids");
            int slotOrderIdx  = SlotHash.getSlot("{vibranium}:order_index");
            int slotVibranium = SlotHash.getSlot("vibranium"); // tag base

            System.out.printf("[SlotCalc] {vibranium}:asks        → slot %d%n", slotAsks);
            System.out.printf("[SlotCalc] {vibranium}:bids        → slot %d%n", slotBids);
            System.out.printf("[SlotCalc] {vibranium}:order_index → slot %d%n", slotOrderIdx);
            System.out.printf("[SlotCalc] vibranium (tag base)    → slot %d%n", slotVibranium);

            assertThat(slotAsks)
                    .as("{vibranium}:asks e {vibranium}:bids devem ter o mesmo slot")
                    .isEqualTo(slotBids);

            assertThat(slotOrderIdx)
                    .as("{vibranium}:order_index deve compartilhar o slot da hash tag")
                    .isEqualTo(slotAsks);

            assertThat(slotAsks)
                    .as("Slot com hash tag deve coincidir com o slot da tag isolada 'vibranium'")
                    .isEqualTo(slotVibranium);
        }

        /**
         * Confirma a regra de extração: apenas o primeiro {@code { }} válido e não-vazio é usado.
         */
        @Test
        @DisplayName("hash tag usa apenas o primeiro { } válido e não-vazio na key")
        void firstValidHashTag_isUsedForSlotCalculation() {
            assertThat(SlotHash.getSlot("{vibranium}:asks"))
                    .as("Chave com hash tag deve ter slot baseado em 'vibranium'")
                    .isEqualTo(SlotHash.getSlot("vibranium"));

            // Múltiplos pares — só o primeiro válido conta
            assertThat(SlotHash.getSlot("{vibranium}:asks:{other}"))
                    .as("Apenas o primeiro { } é considerado pelo Redis")
                    .isEqualTo(SlotHash.getSlot("vibranium"));

            // Sem {} — a key inteira é usada no CRC16
            assertThat(SlotHash.getSlot("vibranium:asks"))
                    .as("Sem hash tag, o CRC16 usa a key inteira (slot difere da tag base)")
                    .isNotEqualTo(SlotHash.getSlot("vibranium"));
        }
    }

    // =========================================================================
    // 2. Validação do formato das keys no contexto Spring — ver RedisKeyFormatIT.java
    //    Separado para evitar conflito de contexto JUnit 5 + Spring em @Nested class.
    // =========================================================================

    // =========================================================================
    // 3. Teste de integração com Redis Cluster real (Testcontainers)
    //    Demonstra CROSSSLOT com keys sem hash tag e ausência após a mudança
    // =========================================================================

    /**
     * Teste de integração com Redis Cluster real via Testcontainers.
     *
     * <p>Usa a imagem {@code grokzen/redis-cluster:7.0.0} que inicia automaticamente
     * 3 masters + 3 replicas nas portas 7000–7005.</p>
     *
     * <p><strong>Estratégia de conexão:</strong> usa {@code RedisClient} (modo standalone)
     * apontando para o nó 0 (porta 7000 mapeada). A validação CROSSSLOT ocorre no servidor
     * Redis, não no cliente — qualquer nó rejeita scripts com keys em slots diferentes.</p>
     *
     * <p>Defina {@code SKIP_REDIS_CLUSTER_TESTS=true} para pular em ambientes sem Docker.</p>
     * <p>Execução isolada: {@code mvn test -Dgroups=redis-cluster}</p>
     */
    @Nested
    @DisplayName("3. Redis Cluster (Testcontainers) — CROSSSLOT antes, sem erro após hash tags")
    @Tag("redis-cluster")
    @Testcontainers
    @DisabledIfEnvironmentVariable(
            named   = "SKIP_REDIS_CLUSTER_TESTS",
            matches = "true",
            disabledReason = "Pulado: SKIP_REDIS_CLUSTER_TESTS=true")
    class RedisClusterCrossSlotTest {

        /**
         * Redis Cluster: 3 masters + 3 replicas, portas 7000–7005.
         * IP=0.0.0.0 faz os nós aceitarem conexões de qualquer interface.
         */
        @Container
        static final GenericContainer<?> CLUSTER_CONTAINER =
                new GenericContainer<>(DockerImageName.parse("grokzen/redis-cluster:7.0.0"))
                        .withExposedPorts(7000, 7001, 7002, 7003, 7004, 7005)
                        .withEnv("IP", "0.0.0.0")
                        .withEnv("INITIAL_PORT", "7000")
                        .withEnv("MASTERS", "3")
                        .withEnv("SLAVES_PER_MASTER", "1")
                        .waitingFor(
                                Wait.forLogMessage(".*cluster_state:ok.*", 1)
                                        .withStartupTimeout(Duration.ofSeconds(120)));

        private static RedisClient redisClient;
        private static StatefulRedisConnection<String, String> connection;
        private static String luaScript;

        @BeforeAll
        static void setUp() throws IOException {
            String host = CLUSTER_CONTAINER.getHost();
            int port    = CLUSTER_CONTAINER.getMappedPort(7000);

            System.out.printf("[ClusterTest] Conectando ao nó 0 → %s:%d%n", host, port);

            // Modo standalone: suficiente para demonstrar CROSSSLOT (erro é do servidor)
            redisClient = RedisClient.create(
                    RedisURI.builder()
                            .withHost(host)
                            .withPort(port)
                            .withTimeout(Duration.ofSeconds(5))
                            .build());

            connection = redisClient.connect();

            luaScript = new ResourceScriptSource(
                    new ClassPathResource("lua/match_engine.lua")).getScriptAsString();

            System.out.printf("[ClusterTest] Script Lua carregado: %d bytes%n", luaScript.length());
        }

        @AfterAll
        static void tearDown() {
            if (connection != null) connection.close();
            if (redisClient != null) redisClient.shutdown();
        }

        /**
         * ⚠️ <strong>FASE RED</strong>: keys sem hash tag em Redis Cluster causam CROSSSLOT.
         *
         * <p>Conecta ao nó 0 do cluster, executa {@code match_engine.lua} com
         * {@code vibranium:asks} e {@code vibranium:bids}, e captura o erro
         * {@code CROSSSLOT Keys in request don't hash to the same slot}.</p>
         */
        @Test
        @DisplayName("FASE RED: keys sem hash tag → CROSSSLOT no Redis Cluster")
        void withoutHashTag_luaScript_throwsCrossSlot() {
            int slotAsks = SlotHash.getSlot("vibranium:asks");
            int slotBids = SlotHash.getSlot("vibranium:bids");

            System.out.printf("[CrossSlot] vibranium:asks → slot %d%n", slotAsks);
            System.out.printf("[CrossSlot] vibranium:bids → slot %d%n", slotBids);

            // Caso extremamente raro: slots coincidentes por acidente matemático
            assumeTrue(slotAsks != slotBids,
                    String.format("Slots iguais (%d/%d) — CROSSSLOT não se manifesta aqui",
                            slotAsks, slotBids));

            connection.sync().del("vibranium:asks", "vibranium:bids");

            assertThatThrownBy(() ->
                    connection.sync().eval(
                            luaScript,
                            ScriptOutputType.MULTI,
                            new String[]{"vibranium:asks", "vibranium:bids"},
                            "BUY", "500000000",
                            "ord-001|usr-001|wal-001|10.00000000|cor-001|1700000000000",
                            "10.00000000"))
                    .as("Redis Cluster deve retornar CROSSSLOT para keys em slots %d e %d",
                            slotAsks, slotBids)
                    .isInstanceOf(RedisCommandExecutionException.class)
                    .hasMessageContaining("CROSSSLOT");
        }

        /**
         * ✅ <strong>GREEN</strong>: keys com {@code {vibranium}} executam sem CROSSSLOT
         * no Redis Cluster.
         *
         * <p>Confirma que a mudança do {@code application.yaml} (AT-11.1) resolve o problema.</p>
         */
        @Test
        @DisplayName("GREEN: keys com {vibranium} → Lua executa sem CROSSSLOT no cluster")
        void withHashTag_luaScript_executesWithoutCrossSlot() {
            int slotAsks = SlotHash.getSlot("{vibranium}:asks");
            int slotBids = SlotHash.getSlot("{vibranium}:bids");

            System.out.printf("[HashTag] {vibranium}:asks → slot %d%n", slotAsks);
            System.out.printf("[HashTag] {vibranium}:bids → slot %d%n", slotBids);

            assertThat(slotAsks).isEqualTo(slotBids);

            connection.sync().del("{vibranium}:asks", "{vibranium}:bids");

            // BUY sem contraparte → NO_MATCH, BID inserido em {vibranium}:bids
            List<Object> result = connection.sync().eval(
                    luaScript,
                    ScriptOutputType.MULTI,
                    new String[]{"{vibranium}:asks", "{vibranium}:bids"},
                    "BUY", "500000000",
                    "ord-ht-001|usr-ht-001|wal-ht-001|10.00000000|cor-ht-001|1700000000000",
                    "10.00000000");

            System.out.printf("[HashTag] Resultado Lua: %s%n", result);

            assertThat(result).isNotEmpty();
            assertThat(result.get(0).toString())
                    .as("Sem ASK disponível → NO_MATCH e BID inserido no livro")
                    .isEqualTo("NO_MATCH");

            assertThat(connection.sync().zcard("{vibranium}:bids"))
                    .as("BID deve ter sido inserido em {vibranium}:bids pelo Lua")
                    .isEqualTo(1L);

            connection.sync().del("{vibranium}:asks", "{vibranium}:bids");
        }

        /**
         * ✅ <strong>GREEN</strong>: match completo (BID = ASK) em Redis Cluster com hash tags.
         * Confirma atomicidade do Lua: ZREM + ZADD na mesma slot → sem CROSSSLOT.
         */
        @Test
        @DisplayName("GREEN: match FULL BID=ASK executa atomicamente no cluster com hash tags")
        void fullMatch_withHashTagKeys_executesAtomically() {
            connection.sync().del("{vibranium}:asks", "{vibranium}:bids");

            final long priceScore = 500_000_000L;
            connection.sync().zadd(
                    "{vibranium}:asks", (double) priceScore,
                    "ask-ht-001|usr-ht-001|wal-ht-001|10.00000000|cor-ht-001|1700000000000");

            List<Object> result = connection.sync().eval(
                    luaScript,
                    ScriptOutputType.MULTI,
                    new String[]{"{vibranium}:asks", "{vibranium}:bids"},
                    "BUY", String.valueOf(priceScore),
                    "bid-ht-001|bid-usr-001|bid-wal-001|10.00000000|bid-cor-001|1700000000001",
                    "10.00000000");

            System.out.printf("[MatchTest] Resultado: %s%n", result);

            assertThat(result.get(0).toString()).as("Deve retornar MATCH").isEqualTo("MATCH");
            assertThat(result.get(3).toString()).as("fillType deve ser FULL").isEqualTo("FULL");
            assertThat(connection.sync().zcard("{vibranium}:asks"))
                    .as("ASK deve ter sido consumido atomicamente pelo Lua")
                    .isZero();

            connection.sync().del("{vibranium}:asks", "{vibranium}:bids");
        }
    }
}
