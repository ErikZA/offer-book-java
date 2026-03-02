package com.vibranium.walletservice.integration;

import com.vibranium.walletservice.AbstractIntegrationTest;
import com.vibranium.walletservice.infrastructure.outbox.DebeziumOutboxEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * [AT-08.1 — FASE RED] Valida idempotência do relay Outbox após restart do engine Debezium
 * quando configurado com {@code JdbcOffsetBackingStore}.
 *
 * <h2>Por que este teste é RED antes da implementação?</h2>
 * <ol>
 *   <li><b>Dependência ausente:</b> {@code io.debezium.storage.jdbc.offset.JdbcOffsetBackingStore}
 *       requer o artefato {@code io.debezium:debezium-storage-jdbc}, que ainda não está no
 *       {@code pom.xml}. O contexto Spring falha ao tentar instanciar o engine Debezium com
 *       {@code ClassNotFoundException}.</li>
 *   <li><b>Tabela ausente:</b> mesmo com a dependência, sem a migration V5 a tabela
 *       {@code wallet_outbox_offset} não existe. O {@code JdbcOffsetBackingStore} lança
 *       {@code SQLSyntaxErrorException} ao tentar criar/acessar a tabela.</li>
 * </ol>
 *
 * <h2>Cenário testado (estado GREEN, após implementação)</h2>
 * <pre>
 *   1. Inicia Debezium com JdbcOffsetBackingStore
 *   2. Insere Evento-A no Outbox
 *   3. Confirma publicação de Evento-A no RabbitMQ (processed=true no banco)
 *   4. Para e reinicia o DebeziumOutboxEngine (simulando restart do container)
 *   5. Insere Evento-B no Outbox
 *   6. Confirma publicação do Evento-B
 *   7. Verifica: Evento-A NÃO é republicado (offset sobreviveu no banco)
 * </pre>
 *
 * <h2>Relação com FileOffsetBackingStore</h2>
 * <p>Com {@code FileOffsetBackingStore} e {@code /tmp}, após o restart do container o arquivo
 * de offset não existe mais. O Debezium reconecta ao slot de replicação sem saber qual LSN
 * já foi processado, podendo reler eventos que já foram publicados no RabbitMQ — causando
 * duplicatas e quebra de exactly-once delivery.</p>
 *
 * <p>Com {@code JdbcOffsetBackingStore}, o offset é persistido no banco (mesma instância
 * PostgreSQL que sobrevive ao restart), garantindo continuidade exata do WAL.</p>
 */
@TestPropertySource(properties = {
        // Ativa o engine Debezium neste contexto de teste
        "app.outbox.debezium.enabled=true",
        // Slot exclusivo para não colidir com OutboxPublisherIntegrationTest
        "app.outbox.debezium.slot-name=wallet_outbox_jdbc_restart_test",
        // Dropa o slot ao parar para não acumular slots inativos
        "app.outbox.debezium.drop-slot-on-stop=true",
        // *** PONTO DE FALHA RED ***
        // Esta classe não está no classpath sem debezium-storage-jdbc no pom.xml.
        // O contexto Spring falha ao iniciar o DebeziumOutboxEngine.
        "app.outbox.debezium.offset-storage=io.debezium.storage.jdbc.offset.JdbcOffsetBackingStore",
        // Tabela criada pela migration V5 (também falha se V5 não existir)
        "app.outbox.debezium.offset-storage-jdbc-table-name=wallet_outbox_offset"
})
@DisplayName("[AT-08.1 RED→GREEN] Debezium JdbcOffsetBackingStore — idempotência pós-restart")
class DebeziumRestartIdempotencyTest extends AbstractIntegrationTest {

    // Exchange e filas de teste isoladas para este cenário
    private static final String EVENTS_EXCHANGE  = "vibranium.events";
    private static final String QUEUE_TEST_RESTART = "test.outbox.restart-idempotency";
    private static final String ROUTING_KEY       = "wallet.events.funds-reserved";

    // -------------------------------------------------------------------------
    // DynamicPropertySource — sobrescreve propriedades JDBC com valores do container
    // -------------------------------------------------------------------------

    /**
     * Fornece a URL, usuário e senha JDBC do container PostgreSQL de teste ao
     * {@code JdbcOffsetBackingStore}.
     *
     * <p>Deve ser definido aqui (não apenas em {@link AbstractIntegrationTest})
     * porque as propriedades {@code offset-storage-jdbc-*} são específicas desta
     * configuração de teste; a classe base não as registra.</p>
     */
    @DynamicPropertySource
    static void overrideJdbcOffsetProperties(DynamicPropertyRegistry registry) {
        // Aponta o JdbcOffsetBackingStore para o mesmo PostgreSQL container do teste
        registry.add("app.outbox.debezium.offset-storage-jdbc-url",      POSTGRES::getJdbcUrl);
        registry.add("app.outbox.debezium.offset-storage-jdbc-username", POSTGRES::getUsername);
        registry.add("app.outbox.debezium.offset-storage-jdbc-password", POSTGRES::getPassword);
    }

    // -------------------------------------------------------------------------
    // Dependências
    // -------------------------------------------------------------------------

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DebeziumOutboxEngine debeziumEngine;

    // -------------------------------------------------------------------------
    // Setup
    // -------------------------------------------------------------------------

    @BeforeEach
    void setupTestQueue() {
        TopicExchange exchange = new TopicExchange(EVENTS_EXCHANGE, true, false);
        rabbitAdmin.declareExchange(exchange);

        Queue queue = new Queue(QUEUE_TEST_RESTART, true, false, false);
        rabbitAdmin.declareQueue(queue);
        rabbitAdmin.declareBinding(
                BindingBuilder.bind(queue).to(exchange).with(ROUTING_KEY));

        // Purga para garantir isolamento com outros testes
        rabbitAdmin.purgeQueue(QUEUE_TEST_RESTART, false);
    }

    // -------------------------------------------------------------------------
    // Teste principal: idempotência pós-restart com JdbcOffsetBackingStore
    // -------------------------------------------------------------------------

    /**
     * Hipótese: com {@code JdbcOffsetBackingStore}, o offset sobrevive ao restart do engine.
     * Após reiniciar, apenas novos eventos devem ser publicados; eventos anteriores
     * já marcados como {@code processed=true} NÃO devem ser republicados.
     *
     * <p><b>Fase RED:</b> falha com {@code ClassNotFoundException} (dependência ausente)
     * ou {@code BeanCreationException} (tabela ausente). Nenhum assert chega a ser executado.</p>
     *
     * <p><b>Fase GREEN:</b> com a dependência adicionada + migration V5 + application.yaml
     * atualizado, o contexto sobe corretamente e o teste valida a idempotência.</p>
     */
    @Test
    @DisplayName("Offset persistido no banco deve prevenir republicação após restart controlado")
    void shouldNotRepublishAlreadyProcessedEventAfterRestart() throws InterruptedException {
        // ------------------------------------------------------------------
        // Step 1: Insere Evento-A e confirma publicação inicial
        // ------------------------------------------------------------------
        UUID eventA  = UUID.randomUUID();
        UUID walletA = UUID.randomUUID();
        insertOutboxMessage(eventA, "FundsReservedEvent", walletA,
                buildPayload(walletA, "100.00"));

        // Aguarda publicação no RabbitMQ
        await("Evento-A deve ser publicado antes do restart")
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    Message msg = rabbitTemplate.receive(QUEUE_TEST_RESTART, 100);
                    assertThat(msg)
                            .as("Evento-A deve chegar na fila antes do restart")
                            .isNotNull();
                    assertThat(msg.getMessageProperties().getMessageId())
                            .isEqualTo(eventA.toString());
                });

        // Confirma processed=true antes do restart
        Boolean processedBeforeRestart = jdbcTemplate.queryForObject(
                "SELECT processed FROM outbox_message WHERE id = ?",
                Boolean.class, eventA);
        assertThat(processedBeforeRestart)
                .as("Evento-A deve estar marcado processed=true antes do restart")
                .isTrue();

        // ------------------------------------------------------------------
        // Step 2: Simula restart do engine (stop + start)
        //         Equivale ao restart do container em produção.
        //         Com JdbcOffsetBackingStore, o offset no banco é preservado.
        // ------------------------------------------------------------------
        debeziumEngine.stop();

        // Pausa breve para garantir que o slot foi liberado
        Thread.sleep(1_000);

        debeziumEngine.start();

        // ------------------------------------------------------------------
        // Step 3: Insere Evento-B após o restart
        // ------------------------------------------------------------------
        UUID eventB  = UUID.randomUUID();
        UUID walletB = UUID.randomUUID();
        insertOutboxMessage(eventB, "FundsReservedEvent", walletB,
                buildPayload(walletB, "200.00"));

        // ------------------------------------------------------------------
        // Step 4: Evento-B deve ser publicado (novo evento após restart)
        // ------------------------------------------------------------------
        await("Evento-B deve ser publicado após restart")
                .atMost(15, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    Message msg = rabbitTemplate.receive(QUEUE_TEST_RESTART, 100);
                    assertThat(msg)
                            .as("Evento-B deve ser publicado após o restart do engine")
                            .isNotNull();
                    assertThat(msg.getMessageProperties().getMessageId())
                            .as("Mensagem publicada após restart deve ser Evento-B, não Evento-A")
                            .isEqualTo(eventB.toString());
                });

        // ------------------------------------------------------------------
        // Step 5: Garante que Evento-A NÃO foi republicado
        //         A fila deve estar vazia — nenhuma outra mensagem deve existir
        // ------------------------------------------------------------------
        await("Não deve haver mensagem residual de Evento-A na fila após restart")
                .during(2, TimeUnit.SECONDS)
                .atMost(3, TimeUnit.SECONDS)
                .untilAsserted(() ->
                        assertThat(rabbitTemplate.receive(QUEUE_TEST_RESTART, 50))
                                .as("Evento-A NÃO deve ser republicado após restart com JdbcOffsetBackingStore. " +
                                    "Se esta assertion falhar, significa que o offset não foi preservado " +
                                    "e o evento foi reprocessado — confirmando a necessidade da migração.")
                                .isNull()
                );
    }

    // -------------------------------------------------------------------------
    // Teste adicional: tabela de offset deve conter entrada após publicação
    // -------------------------------------------------------------------------

    /**
     * Verifica que o {@code JdbcOffsetBackingStore} de fato persistiu o offset
     * na tabela {@code wallet_outbox_offset} após a publicação de um evento.
     *
     * <p>Se a tabela estiver vazia após a publicação, o Debezium está usando
     * a memória como fallback (não a persistência JDBC) — erro de configuração.</p>
     *
     * <p><b>Fase RED:</b> falha porque a tabela não existe (migration ausente)
     * ou porque a classe {@code JdbcOffsetBackingStore} não está no classpath.</p>
     */
    @Test
    @DisplayName("JdbcOffsetBackingStore deve persistir offset na tabela wallet_outbox_offset")
    void offsetMustBePersistedInDatabaseAfterEventPublication() throws InterruptedException {
        // Aguarda que o engine tenha tido ao menos um flush de offset (1s por config)
        Thread.sleep(2_000);

        // Insere um evento para forçar o Debezium a processar e gravar o offset
        UUID eventId = UUID.randomUUID();
        insertOutboxMessage(eventId, "FundsReservedEvent", UUID.randomUUID(),
                buildPayload(UUID.randomUUID(), "50.00"));

        // Aguarda publicação (garante que o Debezium processou e flushou o offset)
        await().atMost(10, TimeUnit.SECONDS)
               .pollInterval(200, TimeUnit.MILLISECONDS)
               .untilAsserted(() ->
                       assertThat(rabbitTemplate.receive(QUEUE_TEST_RESTART, 100))
                               .isNotNull()
               );

        // Aguarda flush do offset (offset.flush.interval.ms=1000)
        Thread.sleep(1_500);

        // Verifica que o offset foi persistido na tabela
        Integer offsetRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wallet_outbox_offset",
                Integer.class);

        assertThat(offsetRows)
                .as("JdbcOffsetBackingStore deve ter persistido ao menos 1 entrada em " +
                    "wallet_outbox_offset. Se 0, o offset não está sendo salvo no banco.")
                .isGreaterThan(0);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void insertOutboxMessage(UUID id, String eventType, UUID aggregateId, String payload) {
        jdbcTemplate.update(
                """
                INSERT INTO outbox_message (id, event_type, aggregate_id, payload, created_at, processed)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                id, eventType, aggregateId.toString(), payload,
                Timestamp.from(Instant.now()), false);
    }

    private String buildPayload(UUID walletId, String amount) {
        return """
               {"walletId":"%s","amount":%s}
               """.formatted(walletId, amount).strip();
    }
}
