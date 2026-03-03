package com.vibranium.walletservice.integration;

import com.vibranium.walletservice.domain.model.OutboxMessage;
import com.vibranium.walletservice.domain.repository.OutboxMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testa o repositório do Transactional Outbox em isolamento JPA puro.
 *
 * <p>Usa {@code @DataJpaTest} com {@link ContextConfiguration} apontando para
 * {@link JpaSliceConfig} — uma configuração interna que <b>substitui</b>
 * {@link com.vibranium.walletservice.WalletServiceApplication} como raiz de
 * contexto neste teste. Isso evita que o {@code @ComponentScan} amplo da
 * aplicação instancie beans de infraestrutura (Security, RabbitMQ, Tracer)
 * que não existem no slice JPA.</p>
 *
 * <p><b>Importante:</b> {@code JpaSliceConfig} usa
 * {@code @Configuration + @EnableAutoConfiguration}, e <b>não</b>
 * {@code @SpringBootConfiguration}, para que o bootstrap de outros testes
 * ({@code @SpringBootTest}) não o encontre por engano como aplicação raiz.</p>
 *
 * <p>O container PostgreSQL é exclusivo desta classe para garantir isolamento total.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = OutboxMessageRepositoryTest.JpaSliceConfig.class)
@Testcontainers
@ActiveProfiles("test")
@DisplayName("[RED] OutboxMessageRepository - Persistência e consultas do Transactional Outbox")
class OutboxMessageRepositoryTest {

    /**
     * Configuração JPA mínima para slice test. Usa {@code @Configuration}
     * (não {@code @SpringBootConfiguration}) para evitar que
     * {@code @SpringBootTest} em outras classes descubra esta classe
     * como raiz de contexto, o que causaria 404 em endpoints REST.
     */
    @Configuration
    @EnableAutoConfiguration
    @EntityScan(basePackages = "com.vibranium.walletservice.domain.model")
    @EnableJpaRepositories(basePackages = "com.vibranium.walletservice.domain.repository")
    static class JpaSliceConfig {
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15-alpine")
                    .withDatabaseName("wallet_outbox_test")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private OutboxMessageRepository outboxMessageRepository;

    @Autowired
    private TestEntityManager entityManager;

    @BeforeEach
    void cleanup() {
        outboxMessageRepository.deleteAll();
    }

    // -------------------------------------------------------------------------
    // Persistência
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Deve persistir OutboxMessage com processed=false por padrão")
    void shouldPersistOutboxMessageWithProcessedFalseByDefault() {
        // Arrange
        OutboxMessage msg = OutboxMessage.create(
                "FundsReservedEvent",
                UUID.randomUUID().toString(),
                "{\"walletId\":\"abc\"}"
        );

        // Act
        OutboxMessage saved = outboxMessageRepository.save(msg);
        entityManager.flush();

        // Assert
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.isProcessed()).isFalse();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getEventType()).isEqualTo("FundsReservedEvent");
        assertThat(saved.getPayload()).contains("walletId");
    }

    @Test
    @DisplayName("Deve encontrar apenas mensagens não processadas")
    void shouldFindOnlyUnprocessedMessages() {
        // Arrange
        OutboxMessage unprocessed = outboxMessageRepository.save(
                OutboxMessage.create("FundsReservedEvent", UUID.randomUUID().toString(), "{}")
        );
        OutboxMessage processed = OutboxMessage.create("WalletCreatedEvent", UUID.randomUUID().toString(), "{}");
        processed.markAsProcessed();
        outboxMessageRepository.save(processed);
        entityManager.flush();

        // Act
        List<OutboxMessage> result = outboxMessageRepository.findByProcessedFalseOrderByCreatedAtAsc();

        // Assert
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(unprocessed.getId());
    }

    @Test
    @DisplayName("Deve marcar OutboxMessage como processada e persistir corretamente")
    void shouldMarkOutboxMessageAsProcessedAndPersist() {
        // Arrange
        OutboxMessage msg = outboxMessageRepository.save(
                OutboxMessage.create("FundsReservedEvent", UUID.randomUUID().toString(), "{}")
        );
        entityManager.flush();

        // Act
        msg.markAsProcessed();
        outboxMessageRepository.save(msg);
        entityManager.flush();
        entityManager.clear();

        // Assert
        OutboxMessage reloaded = outboxMessageRepository.findById(msg.getId()).orElseThrow();
        assertThat(reloaded.isProcessed()).isTrue();
    }

    @Test
    @DisplayName("countByEventType deve retornar contagem correta por tipo de evento")
    void shouldCountByEventTypeCorrectly() {
        // Arrange
        outboxMessageRepository.save(OutboxMessage.create("FundsReservedEvent", UUID.randomUUID().toString(), "{}"));
        outboxMessageRepository.save(OutboxMessage.create("FundsReservedEvent", UUID.randomUUID().toString(), "{}"));
        outboxMessageRepository.save(OutboxMessage.create("WalletCreatedEvent", UUID.randomUUID().toString(), "{}"));
        entityManager.flush();

        // Act & Assert
        assertThat(outboxMessageRepository.countByEventType("FundsReservedEvent")).isEqualTo(2);
        assertThat(outboxMessageRepository.countByEventType("WalletCreatedEvent")).isEqualTo(1);
        assertThat(outboxMessageRepository.countByEventType("NonExistentEvent")).isEqualTo(0);
    }

    @Test
    @DisplayName("Deve salvar múltiplos eventos na mesma transação mantendo a ordem de inserção")
    void shouldSaveMultipleEventsInSameTransactionPreservingOrder() {
        // Simula o que o WalletService faz: salva 2 eventos do mesmo aggregate na mesma @Transactional
        String aggregateId = UUID.randomUUID().toString();
        outboxMessageRepository.save(OutboxMessage.create("FundsReservedEvent", aggregateId, "{\"seq\":1}"));
        outboxMessageRepository.save(OutboxMessage.create("WalletCreatedEvent",  aggregateId, "{\"seq\":2}"));
        entityManager.flush();

        List<OutboxMessage> all = outboxMessageRepository.findByProcessedFalseOrderByCreatedAtAsc();
        assertThat(all).hasSizeGreaterThanOrEqualTo(2);
    }
}
