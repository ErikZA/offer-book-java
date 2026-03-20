package com.vibranium.orderservice.integration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.wait.strategy.Wait;

import java.time.Duration;

/**
 * Base class para testes de integração do Query Side (PostgreSQL + RabbitMQ + Redis + MongoDB).
 *
 * <p>Estende {@link AbstractIntegrationTest} adicionando o container MongoDB 7, necessário para
 * testes do Read Model (US-003 — Histórico de Ordens via MongoDB).</p>
 *
 * <p>A anotação {@code @SpringBootTest} nesta classe <strong>substitui</strong> a herdada de
 * {@link AbstractIntegrationTest}, removendo a exclusão dos auto-configures de MongoDB.
 * Com isso, o contexto Spring desta subclasse cria {@code MongoTemplate},
 * {@code OrderHistoryRepository} e ativa {@code OrderEventProjectionConsumer} e
 * {@code OrderQueryController} (anotados com {@code @ConditionalOnBean(MongoTemplate.class)}).</p>
 *
 * <p><strong>Singleton Container:</strong> o {@code MONGODB} é um campo {@code static}
 * independente do container de {@link AbstractIntegrationTest}. O padrão Singleton garante
 * que o container é iniciado uma única vez por JVM e compartilhado entre todos os testes
 * que estendem esta classe.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// Nota: @AutoConfigureMockMvc, @ActiveProfiles("test") e @Testcontainers são herdados de
// AbstractIntegrationTest e se aplicam automaticamente a esta subclasse.
// A @SpringBootTest aqui (sem properties de exclusão) reativa os auto-configures de MongoDB
// que AbstractIntegrationTest exclui para testes do Command Side.
public abstract class AbstractMongoIntegrationTest extends AbstractIntegrationTest {

    // =========================================================================
    // Singleton Container — iniciado uma vez, compartilhado entre test classes query-side
    // =========================================================================
    private static final String MONGO_REPLICA_SET = "rs0";

    /**
     * MongoDB 7 — Read Model do Query Side (US-003).
     * Armazena {@link com.vibranium.orderservice.application.query.model.OrderDocument} com
     * histórico de eventos para consultas de auditoria.
     *
     * <p>Separado dos containers de {@link AbstractIntegrationTest} para garantir que
     * testes do Command Side não carregam o 4º container, evitando contenção de recursos
     * Docker que quebra o SLA test de concorrência (p99 ≤ 200ms).</p>
     */
    @SuppressWarnings("resource")
    static final GenericContainer<?> MONGODB =
            new GenericContainer<>(DockerImageName.parse("mongo:7.0"))
                    .withExposedPorts(27017)
                    .withCommand("mongod", "--replSet", MONGO_REPLICA_SET, "--bind_ip_all")
                    .waitingFor(Wait.forLogMessage("(?s).*Waiting for connections.*", 1))
                    .withStartupTimeout(Duration.ofMinutes(3));

    static {
        // Inicia o container MongoDB antes do contexto Spring desta hierarquia ser carregado.
        // O static block de AbstractIntegrationTest já iniciou POSTGRES, RABBITMQ, REDIS.
        MONGODB.start();
        initializeReplicaSet();
    }

    // =========================================================================
    // Dynamic Properties — registra URI do MongoDB no contexto Spring
    // =========================================================================

    /**
     * Injeta a URI do MongoDB gerada dinamicamente pelo container.
     * Complementa as propriedades de {@link AbstractIntegrationTest#registerContainerProperties}.
     */
    @DynamicPropertySource
    static void registerMongoProperties(DynamicPropertyRegistry registry) {
        // URI explícita com directConnection=true evita problemas de resolução DNS
        // do hostname interno do container no host Windows.
        registry.add("spring.data.mongodb.uri", () ->
                "mongodb://%s:%d/vibranium_orders_test?replicaSet=%s&directConnection=true"
                        .formatted(MONGODB.getHost(), MONGODB.getMappedPort(27017), MONGO_REPLICA_SET));
    }

    // =========================================================================
    // Beans injetados — disponíveis para subclasses do Query Side
    // =========================================================================

    /**
     * MongoTemplate: usado para operações MongoDB nos testes (setup, teardown, asserções).
     *
     * <p>Disponível apenas nesta hierarquia (query tests), não em testes do Command Side
     * que estendem {@link AbstractIntegrationTest} diretamente.</p>
     */
    @Autowired
    protected MongoTemplate mongoTemplate;

    private static void initializeReplicaSet() {
        try {
            String initiateScript = """
                    try {
                      rs.initiate({
                        _id: '%s',
                        members: [{ _id: 0, host: 'localhost:27017' }]
                      });
                    } catch (e) {
                      if (!String(e).includes('already initialized')) {
                        throw e;
                      }
                    }
                    """.formatted(MONGO_REPLICA_SET);

            ExecResult initResult = MONGODB.execInContainer(
                    "mongosh", "--quiet", "--eval", initiateScript
            );

            if (initResult.getExitCode() != 0) {
                throw new IllegalStateException(
                        "Falha ao iniciar replica set MongoDB: " + initResult.getStderr()
                );
            }

            waitForPrimary();
        } catch (Exception exception) {
            throw new IllegalStateException("Não foi possível inicializar o replica set MongoDB para testes", exception);
        }
    }

    private static void waitForPrimary() throws Exception {
        for (int attempt = 1; attempt <= 90; attempt++) {
            ExecResult status = MONGODB.execInContainer(
                    "mongosh", "--quiet", "--eval", "db.hello().isWritablePrimary"
            );

            if (status.getExitCode() == 0 && "true".equalsIgnoreCase(status.getStdout().trim())) {
                return;
            }
            Thread.sleep(1_000);
        }
        throw new IllegalStateException("MongoDB replica set não ficou PRIMARY dentro do timeout");
    }
}
