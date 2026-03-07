package com.vibranium.utils.secret;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * {@link EnvironmentPostProcessor} que injeta Docker Secrets como propriedades Spring.
 *
 * <p>Este processador é executado ANTES do contexto Spring ser criado, permitindo
 * que secrets de arquivo {@code /run/secrets/} sobrescrevam propriedades de
 * datasource, RabbitMQ, Redis, MongoDB e Keycloak.</p>
 *
 * <h2>Mapeamento Secret → Spring Property</h2>
 * <table>
 *   <tr><th>Arquivo Docker Secret</th><th>Propriedade Spring</th></tr>
 *   <tr><td>{@code /run/secrets/postgres_password}</td><td>{@code spring.datasource.password}</td></tr>
 *   <tr><td>{@code /run/secrets/rabbitmq_password}</td><td>{@code spring.rabbitmq.password}</td></tr>
 *   <tr><td>{@code /run/secrets/redis_password}</td><td>{@code spring.data.redis.password}</td></tr>
 *   <tr><td>{@code /run/secrets/mongo_root_password}</td><td>(injetado na URI)</td></tr>
 *   <tr><td>{@code /run/secrets/keycloak_admin_password}</td><td>(Keycloak env)</td></tr>
 *   <tr><td>{@code /run/secrets/keycloak_db_password}</td><td>(Keycloak DB)</td></tr>
 * </table>
 *
 * <h2>Fallback</h2>
 * <p>Se {@code /run/secrets/} não existir ou estiver vazio (ambiente dev local),
 * nenhuma propriedade é sobrescrita — as variáveis de ambiente e valores padrão
 * do {@code application.yaml} continuam valendo.</p>
 *
 * <h2>Ativação</h2>
 * <p>Registrado via {@code META-INF/spring.factories}:</p>
 * <pre>
 * org.springframework.boot.env.EnvironmentPostProcessor=\
 *     com.vibranium.utils.secret.DockerSecretEnvironmentPostProcessor
 * </pre>
 *
 * @see SecretFileReader
 */
public class DockerSecretEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final Logger logger = LoggerFactory.getLogger(DockerSecretEnvironmentPostProcessor.class);

    private static final String PROPERTY_SOURCE_NAME = "dockerSecrets";

    /**
     * Mapeamento: nome do arquivo em /run/secrets/ → propriedade Spring Boot.
     * Cada entrada define qual secret file mapeia para qual property key.
     */
    private static final Map<String, String> SECRET_TO_PROPERTY = Map.of(
            "postgres_password", "spring.datasource.password",
            "rabbitmq_password", "spring.rabbitmq.password",
            "redis_password", "spring.data.redis.password",
            "keycloak_db_password", "spring.datasource.password"
    );

    private final String secretsDir;

    /** Construtor padrão — usa o diretório padrão Docker. */
    public DockerSecretEnvironmentPostProcessor() {
        this(SecretFileReader.DEFAULT_SECRETS_DIR);
    }

    /** Construtor para testes — permite injetar diretório customizado. */
    DockerSecretEnvironmentPostProcessor(String secretsDir) {
        this.secretsDir = secretsDir;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path secretsDirPath = Path.of(secretsDir);

        if (!Files.exists(secretsDirPath) || !Files.isDirectory(secretsDirPath)) {
            logger.debug("Docker secrets directory not found: {} — using env vars/defaults (dev mode)", secretsDir);
            return;
        }

        Map<String, Object> secrets = new HashMap<>();

        SECRET_TO_PROPERTY.forEach((secretFile, propertyKey) -> {
            Path secretPath = secretsDirPath.resolve(secretFile);
            if (Files.exists(secretPath) && Files.isReadable(secretPath)) {
                try {
                    String value = SecretFileReader.readSecretFile(secretPath);
                    secrets.put(propertyKey, value);
                    // Log apenas o nome do secret e property — NUNCA o valor
                    logger.info("Docker secret '{}' → property '{}'", secretFile, propertyKey);
                } catch (SecretReadException e) {
                    logger.error("Failed to read Docker secret '{}': {}", secretFile, e.getMessage());
                }
            }
        });

        if (!secrets.isEmpty()) {
            // Adiciona no topo para ter prioridade sobre application.yaml e env vars
            environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, secrets));
            logger.info("Loaded {} Docker secret(s) into Spring environment", secrets.size());
        }
    }
}
