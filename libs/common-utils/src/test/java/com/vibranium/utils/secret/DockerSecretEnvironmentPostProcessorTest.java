package com.vibranium.utils.secret;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/**
 * Testes unitários do {@link DockerSecretEnvironmentPostProcessor}.
 *
 * <p>Valida que o processador injeta corretamente os Docker Secrets como
 * propriedades Spring, com prioridade sobre properties padrão.</p>
 */
@DisplayName("DockerSecretEnvironmentPostProcessor")
class DockerSecretEnvironmentPostProcessorTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("deve injetar postgres_password como spring.datasource.password")
    void shouldInjectPostgresPassword() throws IOException {
        // Arrange
        Files.writeString(tempDir.resolve("postgres_password"), "secretDbPass123\n");
        var processor = new DockerSecretEnvironmentPostProcessor(tempDir.toString());
        ConfigurableEnvironment env = new StandardEnvironment();
        SpringApplication app = mock(SpringApplication.class);

        // Act
        processor.postProcessEnvironment(env, app);

        // Assert
        assertEquals("secretDbPass123", env.getProperty("spring.datasource.password"));
    }

    @Test
    @DisplayName("deve injetar rabbitmq_password como spring.rabbitmq.password")
    void shouldInjectRabbitmqPassword() throws IOException {
        Files.writeString(tempDir.resolve("rabbitmq_password"), "rabbitSecret456\n");
        var processor = new DockerSecretEnvironmentPostProcessor(tempDir.toString());
        ConfigurableEnvironment env = new StandardEnvironment();

        processor.postProcessEnvironment(env, mock(SpringApplication.class));

        assertEquals("rabbitSecret456", env.getProperty("spring.rabbitmq.password"));
    }

    @Test
    @DisplayName("deve injetar redis_password como spring.data.redis.password")
    void shouldInjectRedisPassword() throws IOException {
        Files.writeString(tempDir.resolve("redis_password"), "redisSecret789\n");
        var processor = new DockerSecretEnvironmentPostProcessor(tempDir.toString());
        ConfigurableEnvironment env = new StandardEnvironment();

        processor.postProcessEnvironment(env, mock(SpringApplication.class));

        assertEquals("redisSecret789", env.getProperty("spring.data.redis.password"));
    }

    @Test
    @DisplayName("deve injetar múltiplos secrets simultaneamente")
    void shouldInjectMultipleSecrets() throws IOException {
        Files.writeString(tempDir.resolve("postgres_password"), "pgPass");
        Files.writeString(tempDir.resolve("rabbitmq_password"), "rmqPass");
        Files.writeString(tempDir.resolve("redis_password"), "redisPass");
        var processor = new DockerSecretEnvironmentPostProcessor(tempDir.toString());
        ConfigurableEnvironment env = new StandardEnvironment();

        processor.postProcessEnvironment(env, mock(SpringApplication.class));

        assertEquals("pgPass", env.getProperty("spring.datasource.password"));
        assertEquals("rmqPass", env.getProperty("spring.rabbitmq.password"));
        assertEquals("redisPass", env.getProperty("spring.data.redis.password"));
    }

    @Test
    @DisplayName("não deve falhar quando diretório de secrets não existe (dev mode)")
    void shouldNotFailWhenSecretsDirDoesNotExist() {
        var processor = new DockerSecretEnvironmentPostProcessor("/nonexistent/secrets");
        ConfigurableEnvironment env = new StandardEnvironment();

        // Act — não deve lançar exceção
        processor.postProcessEnvironment(env, mock(SpringApplication.class));

        // Assert — nenhuma propriedade injetada
        assertNull(env.getProperty("spring.datasource.password"));
    }

    @Test
    @DisplayName("não deve injetar propriedades quando diretório existe mas está vazio")
    void shouldNotInjectWhenDirExistsButEmpty() {
        // tempDir existe mas está vazio
        var processor = new DockerSecretEnvironmentPostProcessor(tempDir.toString());
        ConfigurableEnvironment env = new StandardEnvironment();

        processor.postProcessEnvironment(env, mock(SpringApplication.class));

        assertNull(env.getProperty("spring.datasource.password"));
    }

    @Test
    @DisplayName("deve ignorar arquivos de secret desconhecidos (não mapeados)")
    void shouldIgnoreUnmappedSecretFiles() throws IOException {
        Files.writeString(tempDir.resolve("unknown_secret"), "value");
        var processor = new DockerSecretEnvironmentPostProcessor(tempDir.toString());
        ConfigurableEnvironment env = new StandardEnvironment();

        processor.postProcessEnvironment(env, mock(SpringApplication.class));

        // Nenhuma propriedade mapeada injetada
        assertNull(env.getProperty("spring.datasource.password"));
    }

    @Test
    @DisplayName("Docker Secret deve ter prioridade sobre propriedade existente")
    void shouldOverrideExistingProperty() throws IOException {
        Files.writeString(tempDir.resolve("postgres_password"), "dockerSecretValue");
        var processor = new DockerSecretEnvironmentPostProcessor(tempDir.toString());
        ConfigurableEnvironment env = new StandardEnvironment();

        // Set propriedade existente (simula application.yaml)
        System.setProperty("spring.datasource.password", "yamlValue");
        try {
            processor.postProcessEnvironment(env, mock(SpringApplication.class));

            // Docker Secret tem prioridade (addFirst no PropertySources)
            assertEquals("dockerSecretValue", env.getProperty("spring.datasource.password"));
        } finally {
            System.clearProperty("spring.datasource.password");
        }
    }
}
