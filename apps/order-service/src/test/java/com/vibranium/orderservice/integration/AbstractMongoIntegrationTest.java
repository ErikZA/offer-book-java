package com.vibranium.orderservice.integration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

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

    /**
     * MongoDB 7 — Read Model do Query Side (US-003).
     * Armazena {@link com.vibranium.orderservice.application.query.model.OrderDocument} com
     * histórico de eventos para consultas de auditoria.
     *
     * <p>Separado dos containers de {@link AbstractIntegrationTest} para garantir que
     * testes do Command Side não carregam o 4º container, evitando contenção de recursos
     * Docker que quebra o SLA test de concorrência (p99 ≤ 200ms).</p>
     */
    static final MongoDBContainer MONGODB =
            new MongoDBContainer(DockerImageName.parse("mongo:7.0"))
                    .withReuse(true);

    static {
        // Inicia o container MongoDB antes do contexto Spring desta hierarquia ser carregado.
        // O static block de AbstractIntegrationTest já iniciou POSTGRES, RABBITMQ, REDIS.
        MONGODB.start();
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
        // getReplicaSetUrl() retorna a URI no formato mongodb://host:port/test?replicaSet=...
        // Sobrescreve o placeholder em application-test.yml com a URL real do container.
        registry.add("spring.data.mongodb.uri", MONGODB::getReplicaSetUrl);
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
}
