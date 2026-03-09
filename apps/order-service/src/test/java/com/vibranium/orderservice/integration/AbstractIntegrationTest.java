package com.vibranium.orderservice.integration;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
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
 * Base class para testes de integração do Command Side (PostgreSQL + RabbitMQ + Redis).
 *
 * <p>Utiliza o padrão <strong>Singleton Containers</strong> do Testcontainers:
 * os containers são iniciados UMA VEZ (campo {@code static}) e compartilhados
 * por todas as subclasses. Isso evita o overhead de reinício para cada classe
 * de teste.</p>
 *
 * <p>Containers disponibilizados:</p>
 * <ul>
 *   <li>{@code POSTGRES} — PostgreSQL 16 com Flyway (tb_user_registry, tb_orders)</li>
 *   <li>{@code RABBITMQ} — RabbitMQ 3.13 com management UI (exchanges + queues)</li>
 *   <li>{@code REDIS}    — Redis 7 via GenericContainer (Motor de Match Lua)</li>
 * </ul>
 *
 * <p><strong>MongoDB excluído intencionalmente:</strong> testes do Command Side não precisam
 * de MongoDB. Excluir o 4º container elimina a contenção de recursos Docker que causava
 * p99 > 200ms no SLA test de concorrência. Testes que precisam do Read Model devem
 * estender {@link AbstractMongoIntegrationTest} em vez desta classe.</p>
 *
 * <p>O auto-configure do Spring MongoDB é desabilitado via {@code @SpringBootTest(properties)}
 * para evitar que o MongoClient tente se conectar a um MongoDB inexistente. Os beans
 * {@link com.vibranium.orderservice.application.query.consumer.OrderEventProjectionConsumer} e
 * {@link com.vibranium.orderservice.web.controller.OrderQueryController} só são criados
 * quando {@code MongoTemplate} está disponível no contexto ({@code @ConditionalOnBean}).</p>
 *
 * <p><strong>Isolamento de contexto na suíte completa:</strong> esta classe é anotada com
 * {@code @DirtiesContext(AFTER_CLASS)} para garantir que o {@code ApplicationContext} seja
 * destruído após cada classe de teste. Isso impede a coexistência simultânea de dois contextos
 * Spring (Command Side e Query Side) com listeners AMQP competindo pelas mesmas filas do
 * RabbitMQ singleton. Sem este mecanismo, mensagens publicadas em testes do Query Side
 * poderiam ser consumidas pelos listeners do contexto Command Side (já em cache), fazendo
 * o {@code await(10s)} expirar — falso positivo ao rodar a suíte completa.</p>
 *
 * <p>Os containers Testcontainers (campos {@code static}) <strong>não são afetados</strong>
 * por {@code @DirtiesContext} — eles persistem durante toda a JVM. Apenas os beans Spring
 * (pools de conexão, listeners AMQP, etc.) são destruídos e recriados a cada classe.</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // Exclui auto-configuração MongoDB para evitar tentativa de conexão quando não há
        // container de MongoDB rodando. Os beans query-side estão anotados com
        // @ConditionalOnProperty(app.mongodb.enabled) e não são criados quando esta flag
        // está em false (definida aqui via properties do @SpringBootTest).
        properties = {
            "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration," +
            "org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration," +
            "org.springframework.boot.autoconfigure.data.mongo.MongoRepositoriesAutoConfiguration",
            // Desabilita beans do Query Side (OrderEventProjectionConsumer, OrderQueryController,
            // MongoIndexConfig.mongoOrdersIndexInitializer) para evitar falha de injecão.
            "app.mongodb.enabled=false",
            // Sobrescreve o readiness group para remover 'mongo', que não existe
            // quando MongoAutoConfiguration é excluído — impede NoSuchHealthContributorException.
            "management.endpoint.health.group.readiness.include=db,redis,rabbit"
        }
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
// Garante que cada classe de teste destrua o ApplicationContext após sua execução.
// Evita que contextos Command Side e Query Side co-existam em cache simultaneamente,
// o que geraria listeners AMQP competindo pelas mesmas filas — causa raiz dos false
// positives (ConditionTimeoutException) ao executar a suíte completa.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
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
     *
     * <p>requirepass habilitado para simular ambiente de produção com autenticação.
     * A senha "testpass" é injetada via {@code @DynamicPropertySource} e espelha
     * o valor configurado em application-test.yml.</p>
     */
    @SuppressWarnings("resource")   // fechamento gerenciado pelo Testcontainers runtime
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379)
                    .withCommand("redis-server", "--appendonly", "no", "--requirepass", "testpass")
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
        registry.add("spring.data.redis.password", () -> "testpass");
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
