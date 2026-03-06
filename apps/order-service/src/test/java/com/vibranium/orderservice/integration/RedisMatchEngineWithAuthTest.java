package com.vibranium.orderservice.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AT-04 — Testa execução do Script Lua match_engine.lua em Redis com autenticação.
 *
 * <p>Valida que EVALSHA funciona normalmente após autenticação via requirepass.
 * Usa container Redis isolado com senha para não interferir nos demais testes.</p>
 *
 * <p>Cenários:</p>
 * <ul>
 *   <li>addToBook (inserção no Sorted Set) via script Lua pós-auth</li>
 *   <li>tryMatch (match de ordens) via script Lua pós-auth</li>
 * </ul>
 */
@Testcontainers
@DisplayName("AT-04: Match Engine Lua — Redis com autenticação")
class RedisMatchEngineWithAuthTest {

    private static final String REDIS_TEST_PASSWORD = "testpass";
    private static final String ASKS_KEY = "{vibranium}:asks";
    private static final String BIDS_KEY = "{vibranium}:bids";
    private static final String ORDER_INDEX_KEY = "{vibranium}:order_index";

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS_AUTH =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379)
                    .withCommand("redis-server", "--requirepass", REDIS_TEST_PASSWORD, "--appendonly", "yes");

    private StringRedisTemplate redisTemplate;

    @SuppressWarnings("rawtypes")
    private DefaultRedisScript<List> matchScript;

    @BeforeEach
    void setUp() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration();
        config.setHostName(REDIS_AUTH.getHost());
        config.setPort(REDIS_AUTH.getMappedPort(6379));
        config.setPassword(REDIS_TEST_PASSWORD);

        LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();

        redisTemplate = new StringRedisTemplate(factory);

        // Limpa dados de testes anteriores
        redisTemplate.delete(ASKS_KEY);
        redisTemplate.delete(BIDS_KEY);
        redisTemplate.delete(ORDER_INDEX_KEY);

        // Carrega o script Lua real do classpath
        matchScript = new DefaultRedisScript<>();
        matchScript.setScriptSource(
                new ResourceScriptSource(new ClassPathResource("lua/match_engine.lua")));
        matchScript.setResultType(List.class);
    }

    @Test
    @DisplayName("EVALSHA funciona pós-autenticação — addToBook ASK")
    @SuppressWarnings("unchecked")
    void evalsha_afterAuth_shouldAddToBook() {
        List<String> keys = Arrays.asList(ASKS_KEY, BIDS_KEY, ORDER_INDEX_KEY);

        // Simula addToBook para um ASK: type=SELL, price=50000 (score = 50000 * 100_000_000)
        String orderValue = "order-1|user-1|wallet-1|10.00000000|corr-1|1234567890";
        List<Object> result = redisTemplate.execute(
                matchScript,
                keys,
                "SELL",
                String.valueOf(50000L * 100_000_000L),
                orderValue,
                "10.00000000"
        );

        assertThat(result).isNotNull();
        // NO_MATCH indica que a ordem foi adicionada ao book (sem contraparte)
        assertThat(result.get(0).toString()).isEqualTo("NO_MATCH");
    }

    @Test
    @DisplayName("EVALSHA funciona pós-autenticação — match BID contra ASK existente")
    @SuppressWarnings("unchecked")
    void evalsha_afterAuth_shouldMatchOrders() {
        List<String> keys = Arrays.asList(ASKS_KEY, BIDS_KEY, ORDER_INDEX_KEY);

        long priceScore = 50000L * 100_000_000L;

        // Primeiro: adiciona um ASK ao book
        String askValue = "ask-1|user-a|wallet-a|5.00000000|corr-a|1234567890";
        redisTemplate.execute(matchScript, keys, "SELL", String.valueOf(priceScore), askValue, "5.00000000");

        // Depois: envia um BID com mesmo preço e mesma quantidade → match exato
        String bidValue = "bid-1|user-b|wallet-b|5.00000000|corr-b|1234567891";
        List<Object> result = redisTemplate.execute(
                matchScript,
                keys,
                "BUY",
                String.valueOf(priceScore),
                bidValue,
                "5.00000000"
        );

        assertThat(result).isNotNull();
        // Deve retornar MULTI_MATCH ou dados de match (não NO_MATCH)
        assertThat(result.get(0).toString()).isNotEqualTo("NO_MATCH");
    }
}
