package com.vibranium.walletservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibranium.walletservice.domain.repository.IdempotencyKeyRepository;
import com.vibranium.walletservice.domain.repository.OutboxMessageRepository;
import com.vibranium.walletservice.domain.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
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
//
// @DirtiesContext(AFTER_CLASS): fecha o ApplicationContext após cada classe de teste.
// Sem isso, Spring reutiliza o contexto entre classes via TestContextManager cache,
// mantendo @RabbitListener ativos nas filas. Quando uma classe usa @MockBean
// (criando um contexto diferente), os listeners do contexto anterior continuam
// consumindo mensagens em paralelo via round-robin do RabbitMQ, impedindo que o
// @MockBean receba 100% das mensagens — causando ConditionTimeout nos testes de DLQ.
// AFTER_CLASS garante que o contexto seja fechado (e listeners parados) depois de
// cada classe, sem afetar a reutilização dos containers Testcontainers (que são static).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@WithMockUser
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
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

    @Autowired
    protected WalletRepository walletRepository;

    @Autowired
    protected OutboxMessageRepository outboxMessageRepository;

    @Autowired
    protected IdempotencyKeyRepository idempotencyKeyRepository;

    /**
     * Limpa todo o estado mutável entre os testes para garantir isolamento total.
     *
     * <p>Executa <b>antes de cada teste</b> (via {@code @BeforeEach} do JUnit 5),
     * rodando antes dos métodos {@code @BeforeEach} das subclasses — que podem então
     * inserir dados de teste em uma base limpa.</p>
     *
     * <h3>Ordem de limpeza:</h3>
     * <ol>
     *   <li>Filas RabbitMQ — purga primeiro para interromper a entrega de mensagens
     *       residuais ao listener <b>antes</b> de remover os dados do banco.</li>
     *   <li>Chaves de idempotência — independente.</li>
     *   <li>Outbox messages — {@code aggregate_id} é VARCHAR, sem FK para wallet.</li>
     *   <li>Wallets — tabela raiz, sem dependentes restantes.</li>
     * </ol>
     */
    @BeforeEach
    void resetState() {
        // --- RabbitMQ: purga ANTES do DB para evitar que o listener processe
        //     mensagens residuais contra wallets recém-deletadas ---
        for (String queue : new String[]{
                "wallet.keycloak.events",
                "wallet.keycloak.events.dlq",
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

        // --- DB cleanup: garante base vazia para cada teste ---
        idempotencyKeyRepository.deleteAll();
        outboxMessageRepository.deleteAll();
        walletRepository.deleteAll();
    }
}
