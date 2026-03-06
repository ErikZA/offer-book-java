package com.vibranium.orderservice.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AT-04 — Testa autenticação Redis com requirepass.
 *
 * <p>Container Redis dedicado COM requirepass habilitado.
 * Valida que:</p>
 * <ul>
 *   <li>Conexão COM senha correta funciona (PING → PONG)</li>
 *   <li>Conexão SEM senha falha com erro de autenticação (NOAUTH)</li>
 * </ul>
 *
 * <p>Usa container isolado (não o singleton de AbstractIntegrationTest)
 * para não interferir nos demais testes.</p>
 */
@Testcontainers
@DisplayName("AT-04: Redis Authentication — requirepass")
class RedisAuthenticationIntegrationTest {

    private static final String REDIS_TEST_PASSWORD = "testpass";

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS_AUTH =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379)
                    .withCommand("redis-server", "--requirepass", REDIS_TEST_PASSWORD);

    @Test
    @DisplayName("Conexão com senha correta retorna PONG")
    void connectionWithCorrectPassword_shouldSucceed() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration();
        config.setHostName(REDIS_AUTH.getHost());
        config.setPort(REDIS_AUTH.getMappedPort(6379));
        config.setPassword(REDIS_TEST_PASSWORD);

        LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();

        try {
            StringRedisTemplate template = new StringRedisTemplate(factory);
            String pong = template.getConnectionFactory().getConnection().ping();
            assertThat(pong).isEqualTo("PONG");
        } finally {
            factory.destroy();
        }
    }

    @Test
    @DisplayName("Conexão sem senha falha com AuthenticationException (NOAUTH)")
    void connectionWithoutPassword_shouldFailWithNoAuth() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration();
        config.setHostName(REDIS_AUTH.getHost());
        config.setPort(REDIS_AUTH.getMappedPort(6379));
        // Sem password configurado

        LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();

        try {
            StringRedisTemplate template = new StringRedisTemplate(factory);
            assertThatThrownBy(() -> template.getConnectionFactory().getConnection().ping())
                    .hasStackTraceContaining("NOAUTH");
        } finally {
            factory.destroy();
        }
    }

    @Test
    @DisplayName("Conexão com senha incorreta falha com erro de autenticação")
    void connectionWithWrongPassword_shouldFail() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration();
        config.setHostName(REDIS_AUTH.getHost());
        config.setPort(REDIS_AUTH.getMappedPort(6379));
        config.setPassword("wrong_password");

        LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();

        try {
            StringRedisTemplate template = new StringRedisTemplate(factory);
            assertThatThrownBy(() -> template.getConnectionFactory().getConnection().ping())
                    .hasStackTraceContaining("AUTH");
        } finally {
            factory.destroy();
        }
    }
}
