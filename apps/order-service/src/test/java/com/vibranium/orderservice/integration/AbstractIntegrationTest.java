package com.vibranium.orderservice.integration;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class para todos os testes de integração do order-service.
 *
 * <p>Utiliza o padrão <strong>Singleton Containers</strong> do Testcontainers:
 * os containers são iniciados UMA VEZ (campo {@code static}) e compartilhados
 * por todas as subclasses. Isso evita o overhead de reinício para cada classe
 * de teste, reduzindo o tempo total da suite de ~90s para ~15s.</p>
 *
 * <p>Containers disponibilizados:</p>
 * <ul>
 *   <li>{@code postgres} — PostgreSQL 16 com Flyway (tb_user_registry, tb_orders)</li>
 *   <li>{@code rabbitmq} — RabbitMQ 3.13 com management UI (exchanges + queues)</li>
 *   <li>{@code redis}   — Redis 7 via GenericContainer (Motor de Match Lua)</li>
 * </ul>
 *
 * <p>As propriedades de conexão são injetadas dinamicamente via
 * {@link DynamicPropertySource}, sobrescrevendo o {@code application-test.yml}.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
public abstract class AbstractIntegrationTest {

    // =========================================================================
    // Singleton Containers — iniciados uma vez, compartilhados entre test classes
    // =========================================================================

    /**
     * PostgreSQL 16 Alpine: banco de dados do Command Side.
     * Flyway aplica V1 (tb_user_registry) e V2 (tb_orders) no startup.
     */
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("vibranium_test")
                    .withUsername("test")
                    .withPassword("test")
                    .withReuse(true);   // reutiliza container entre runs (Testcontainers Cloud / local)

    /**
     * RabbitMQ 3.13 com management plugin.
     * Exchanges e filas são declaradas pelo RabbitMQConfig.java ao iniciar o contexto.
     */
    static final RabbitMQContainer RABBITMQ =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management-alpine"))
                    .withReuse(true);

    /**
     * Redis 7 Alpine via GenericContainer.
     * Testcontainers não tem módulo dedicado para Redis — GenericContainer é suficiente.
     * O Motor de Match usa {@code StringRedisTemplate.execute(RedisScript, ...)} para
     * execução atômica do Script Lua no Sorted Set.
     */
    @SuppressWarnings("resource")   // fechamento gerenciado pelo Testcontainers runtime
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379)
                    .withCommand("redis-server", "--appendonly", "no")  // AOF desabilitado em teste
                    .withReuse(true);

    // Inicia os containers antes de qualquer subclasse ser carregada
    static {
        POSTGRES.start();
        RABBITMQ.start();
        REDIS.start();
    }

    // =========================================================================
    // Dynamic Properties — sobrescreve application-test.yml com portas dos containers
    // =========================================================================

    /**
     * Injeta os endereços dos containers no contexto Spring dinamicamente.
     * Executado antes da criação do ApplicationContext.
     */
    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        // PostgreSQL
        registry.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        // RabbitMQ
        registry.add("spring.rabbitmq.host",     RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port",     RABBITMQ::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);

        // Redis (GenericContainer usa getMappedPort para a porta randomizada)
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    // =========================================================================
    // Beans injetados — disponíveis para todas as subclasses
    // =========================================================================

    /** MockMvc: simula requisições HTTP sem servidor real (configurado por @AutoConfigureMockMvc) */
    @Autowired
    protected MockMvc mockMvc;

    /** RabbitTemplate: publica mensagens de teste diretamente nas exchanges/filas */
    @Autowired
    protected RabbitTemplate rabbitTemplate;

    /** StringRedisTemplate: manipula o Sorted Set Redis diretamente nos testes do Script Lua */
    @Autowired
    protected StringRedisTemplate redisTemplate;
}
