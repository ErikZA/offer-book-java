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

/**
 * Base abstrata para todos os testes de integração do wallet-service.
 *
 * <p>Hospeda os containers compartilhados (PostgreSQL + RabbitMQ) em modo estático,
 * evitando o custo de start/stop a cada classe de teste.</p>
 *
 * <p>Usa {@link DynamicPropertySource} para sobrescrever as propriedades de datasource
 * e broker dinamicamente com as portas alocadas pelo Testcontainers.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
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

    /** PostgreSQL 15 com o banco de dados do wallet-service. */
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15-alpine")
                    .withDatabaseName("vibranium_wallet_test")
                    .withUsername("test")
                    .withPassword("test")
                    .withReuse(true);

    /** RabbitMQ 3.13 com Management UI. */
    static final RabbitMQContainer RABBIT =
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
     */
    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
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
        // Purga as filas de teste para garantir isolamento entre cenários
        try {
            rabbitAdmin.purgeQueue("wallet.keycloak.events", false);
            rabbitAdmin.purgeQueue("wallet.commands", false);
        } catch (Exception ignored) {
            // Filas ainda não existem — serão criadas pelo contexto Spring
        }
    }
}
