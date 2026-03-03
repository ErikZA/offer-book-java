package com.vibranium.walletservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
// AT-10.1: @WithMockUser — bypass de autenticação JWT nos testes de integração
import org.springframework.security.test.context.support.WithMockUser;

/**
 * Base abstrata para todos os testes de integração do wallet-service.
 *
 * <p>Hospeda os containers compartilhados (PostgreSQL + RabbitMQ) em modo estático,
 * evitando o custo de start/stop a cada classe de teste.</p>
 *
 * <p>Usa {@link DynamicPropertySource} para sobrescrever as propriedades de datasource
 * e broker dinamicamente com as portas alocadas pelo Testcontainers.</p>
 */
// AT-10.1: @WithMockUser garante que todos os testes de integração continuem passando
// após a adição do SecurityConfig + oauth2ResourceServer.
// Pre-popula o SecurityContext com um usuário autenticado (UsernamePasswordAuthenticationToken),
// bypassando a validação JWT do BearerTokenAuthenticationFilter.
// Sem isto, todas as requisições dos testes retornariam 401 (nenhum Bearer Token presente).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@WithMockUser
public abstract class AbstractIntegrationTest {

    // ---------------------------------------------------------------------------
    // Singleton Containers — iniciados UMA VEZ por JVM, compartilhados entre classes
    // ---------------------------------------------------------------------------
    // Padrão Singleton Container: campos static sem @Container + bloco static com .start().
    // Isso garante que o mesmo container (mesma porta) seja reutilizado pelo
    // ApplicationContext cacheado do Spring TestContext, evitando ShutdownSignalException
    // causada por reconexão a um broker que foi parado/reiniciado entre classes de teste.
    //
    // .withReuse(true) evita restart de containers entre runs locais (requer
    // ~/.testcontainers.properties com testcontainers.reuse.enable=true).

    /** PostgreSQL 15 com o banco de dados do wallet-service.
     *
     * <p>{@code withCommand} habilita {@code wal_level=logical}, necessário para
     * o Debezium CDC via replicação lógica (requerido por {@link
     * com.vibranium.walletservice.infrastructure.outbox.DebeziumOutboxEngine}).
     * Configura também {@code max_replication_slots} e {@code max_wal_senders}
     * para suportar múltiplos engines em testes paralelos.</p> */
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15-alpine")
                    .withDatabaseName("vibranium_wallet_test")
                    .withUsername("test")
                    .withPassword("test")
                    .withCommand("postgres",
                                 "-c", "wal_level=logical",
                                 "-c", "max_replication_slots=10",
                                 "-c", "max_wal_senders=10")
                    .withReuse(true);

    /** RabbitMQ 3.13 com Management UI.
     *
     * <p>A imagem {@code management-alpine} habilita o plugin {@code rabbitmq_management},
     * expondo a HTTP Management API em porta 15672. Isso permite que testes inspecionem
     * metadados de filas (argumentos, bindings) via {@code GET /api/queues/%2F/{name}}.</p> */
    protected static final RabbitMQContainer RABBIT =
            new RabbitMQContainer("rabbitmq:3.13-management-alpine")
                    .withReuse(true);

    // Inicia os containers antes de qualquer subclasse ser carregada
    static {
        POSTGRES.start();
        RABBIT.start();
    }

    // ---------------------------------------------------------------------------
    // Injeção de propriedades dinâmicas no contexto Spring
    // ---------------------------------------------------------------------------

    /**
     * Registra as URLs dos containers no {@link DynamicPropertyRegistry}
     * antes da criação do ApplicationContext.
     *
     * <p>Além das propriedades de datasource e RabbitMQ, registra as
     * propriedades do Debezium para que o {@link
     * com.vibranium.walletservice.infrastructure.outbox.DebeziumOutboxEngine}
     * consiga se conectar ao container PostgreSQL de teste.</p>
     */
    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        registry.add("spring.rabbitmq.host",     RABBIT::getHost);
        registry.add("spring.rabbitmq.port",     RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);

        // Propriedades Debezium — usado quando app.outbox.debezium.enabled=true
        // (ativado individualmente em OutboxPublisherIntegrationTest via @TestPropertySource)
        registry.add("app.outbox.debezium.db-host", POSTGRES::getHost);
        registry.add("app.outbox.debezium.db-port",
                () -> String.valueOf(POSTGRES.getMappedPort(5432)));
        registry.add("app.outbox.debezium.db-name", POSTGRES::getDatabaseName);
        registry.add("app.outbox.debezium.db-user", POSTGRES::getUsername);
        registry.add("app.outbox.debezium.db-password", POSTGRES::getPassword);
    }

    // ---------------------------------------------------------------------------
    // Dependências de suporte disponíveis às subclasses
    // ---------------------------------------------------------------------------

    @Autowired
    protected RabbitTemplate rabbitTemplate;

    @Autowired
    protected RabbitAdmin rabbitAdmin;

    @Autowired
    protected ObjectMapper objectMapper;

    /**
     * Limpa a tabela de idempotência entre os testes para evitar
     * interferência entre cenários de duplicidade.
     *
     * <p>Subclasses podem sobrescrever e chamar {@code super.resetDb()} para
     * adicionar limpezas extras de suas próprias entidades.</p>
     */
    @BeforeEach
    void resetRabbitQueues() {
        // Purga todas as filas entre cenários para garantir isolamento total.
        // Inclui a fila dedicada de reserve-funds e sua DLQ (AT-07.1).
        for (String queue : new String[]{
                "wallet.keycloak.events",
                "wallet.commands",
                "wallet.commands.reserve-funds",
                "wallet.commands.reserve-funds.dlq",
                "wallet.commands.release-funds",
                "wallet.commands.release-funds.dlq"
        }) {
            try {
                rabbitAdmin.purgeQueue(queue, false);
            } catch (Exception ignored) {
                // Fila ainda não existe — será declarada automaticamente pelo contexto Spring
            }
        }
    }
}
