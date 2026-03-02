package com.vibranium.walletservice.infrastructure.outbox;

import com.vibranium.walletservice.config.OutboxProperties;
import com.vibranium.walletservice.domain.model.OutboxMessage;
import com.vibranium.walletservice.domain.repository.OutboxMessageRepository;
import io.debezium.embedded.Connect;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Debezium Embedded Engine que captura INSERTs na tabela {@code outbox_message}
 * via WAL do PostgreSQL (replicação lógica) e delega a publicação ao
 * {@link OutboxPublisherService}.
 *
 * <h2>Por que Debezium Embedded?</h2>
 * <ul>
 *   <li><b>Zero polling:</b> reage ao evento de escrita no banco em &lt; 100ms
 *       sem I/O periódico desnecessário.</li>
 *   <li><b>Sem 2-phase commit:</b> o WAL é escrito atomicamente com a transação
 *       de negócio — a posição do WAL é o próprio offset de durabilidade.</li>
 *   <li><b>Fault tolerance (AT-08.1):</b> o offset ({@code JdbcOffsetBackingStore} em produção)
 *       persiste o LSN do WAL na tabela {@code wallet_outbox_offset} do PostgreSQL,
 *       garantindo que nenhum evento seja perdido ou duplicado no restart do container.</li>
 * </ul>
 *
 * <h2>Concorrência multi-instância</h2>
 * <p>Cada instância usa um {@code slot.name} único, fazendo com que TODAS recebam
 * os mesmos eventos do WAL (fan-out). O claim atômico em
 * {@link OutboxPublisherService#claimAndPublish} garante que apenas UMA publique
 * cada evento no RabbitMQ.</p>
 *
 * <h2>Pré-requisitos do PostgreSQL</h2>
 * <pre>
 *   wal_level = logical
 *   max_replication_slots >= número_de_instâncias
 *   max_wal_senders >= número_de_instâncias
 * </pre>
 *
 * <p>Ativado apenas quando {@code app.outbox.debezium.enabled=true}.</p>
 */
@Component
@ConditionalOnProperty(name = "app.outbox.debezium.enabled", havingValue = "true",
                       matchIfMissing = false)
public class DebeziumOutboxEngine implements SmartLifecycle {

    private static final Logger logger = LoggerFactory.getLogger(DebeziumOutboxEngine.class);

    // Fase alta garante que o engine inicie APÓS todos os beans de infraestrutura
    // (DataSource, RabbitMQ) estarem disponíveis e funcionando.
    private static final int LIFECYCLE_PHASE = Integer.MAX_VALUE - 100;

    // -------------------------------------------------------------------------
    // Dependências (constructor injection)
    // -------------------------------------------------------------------------

    private final OutboxPublisherService  publisher;
    private final OutboxProperties        properties;
    private final OutboxMessageRepository outboxRepository;
    private final DataSource              dataSource;

    public DebeziumOutboxEngine(
            OutboxPublisherService  publisher,
            OutboxProperties        properties,
            OutboxMessageRepository outboxRepository,
            DataSource              dataSource) {
        this.publisher        = publisher;
        this.properties       = properties;
        this.outboxRepository = outboxRepository;
        this.dataSource       = dataSource;
    }

    // -------------------------------------------------------------------------
    // Estado do ciclo de vida
    // -------------------------------------------------------------------------

    private volatile boolean                              running  = false;
    private          DebeziumEngine<ChangeEvent<SourceRecord, SourceRecord>> engine;
    private          ExecutorService                      executor;

    // -------------------------------------------------------------------------
    // SmartLifecycle
    // -------------------------------------------------------------------------

    @Override
    public void start() {
        logger.info("Iniciando Debezium Embedded Engine para Outbox CDC...");

        // 1. Em modo de teste (drop-slot-on-stop=true), dropa o slot pré-existente
        //    para garantir estado limpo — necessário com Testcontainers --withReuse(true).
        if (properties.debezium().dropSlotOnStop()) {
            dropReplicationSlot(properties.debezium().slotName());
        }

        // 2. Constrói e submete o engine no VirtualThread.
        //    O Debezium criará o slot de replicação de forma assíncrona durante
        //    a inicialização interna do conector PostgreSQL.
        Properties debeziumProps = buildDebeziumProperties();

        engine = DebeziumEngine.create(Connect.class)
                .using(debeziumProps)
                .using((success, message, error) -> {
                    // Callback ao parar o engine (normal ou por erro)
                    if (!success) {
                        logger.error("Debezium engine encerrou com falha: {} ", message, error);
                    } else {
                        logger.info("Debezium engine encerrado normalmente.");
                    }
                })
                .notifying(this::handleBatch)
                .build();

        // Thread única dedicada ao engine — usa VirtualThread para melhor throughput em I/O
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = Thread.ofVirtual().name("debezium-outbox-engine").unstarted(r);
            t.setDaemon(true);
            return t;
        });
        executor.execute(engine);

        // 3. Aguarda o slot de replicação ficar ATIVO antes de prosseguir.
        //    CRÍTICO: sem este await, o primeiro INSERT de teste pode chegar no WAL
        //    antes do Debezium estar pronto para capturá-lo, causando timeouts flaky.
        awaitSlotActive(properties.debezium().slotName());

        // 4. Processa mensagens pendentes que existiam ANTES do engine ser habilitado.
        //    Feito APÓS o slot ficar ativo para que qualquer INSERT concorrente seja
        //    igualmente capturado pelo WAL e tratado pelo claim atômico.
        processExistingPendingMessages();

        running = true;
        logger.info("Debezium Embedded Engine iniciado. Slot: {} | Offset: {}",
                properties.debezium().slotName(), properties.debezium().offsetStorage());
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        logger.info("Parando Debezium Embedded Engine...");

        if (engine != null) {
            try {
                engine.close();
            } catch (IOException e) {
                logger.warn("Erro ao fechar Debezium engine: {}", e.getMessage(), e);
            }
        }

        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                    logger.warn("Debezium engine não encerrou em 30s — forçando shutdown.");
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
        }

        // Em ambientes de teste, dropa o slot para não acumular slots inativos
        if (properties.debezium().dropSlotOnStop()) {
            dropReplicationSlot(properties.debezium().slotName());
        }

        logger.info("Debezium Embedded Engine parado.");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Inicia após todos os beans de infraestrutura estarem prontos. */
    @Override
    public int getPhase() {
        return LIFECYCLE_PHASE;
    }

    // -------------------------------------------------------------------------
    // Handler de batch CDC
    // -------------------------------------------------------------------------

    /**
     * Processa um lote de eventos CDC entregue pelo Debezium.
     *
     * <p>Filtra apenas operações de INSERT ({@code op = "c"}) e registros ainda
     * não processados ({@code processed = false}) para evitar reprocessamento
     * de snapshots ou registros históricos já publicados.</p>
     *
     * @param records    Lote de eventos capturados do WAL.
     * @param committer  Usado para confirmar o processamento de cada evento ao Debezium.
     */
    private void handleBatch(
            List<ChangeEvent<SourceRecord, SourceRecord>> records,
            DebeziumEngine.RecordCommitter<ChangeEvent<SourceRecord, SourceRecord>> committer) {

        for (ChangeEvent<SourceRecord, SourceRecord> event : records) {
            try {
                SourceRecord record = event.value();
                if (record != null) {
                    processRecord(record);
                }
                committer.markProcessed(event);
            } catch (Exception e) {
                // Loga mas não propaga para não parar o engine inteiro.
                // O record não é marcado como processado: Debezium reprocessará no próximo lote.
                logger.error("Erro ao processar evento CDC outbox: {}", e.getMessage(), e);
            }
        }

        try {
            committer.markBatchFinished();
        } catch (Exception e) {
            logger.warn("Erro ao confirmar batch Debezium: {}", e.getMessage(), e);
        }
    }

    /**
     * Extrai os campos da linha {@code outbox_message} do evento CDC e delega
     * ao {@link OutboxPublisherService#claimAndPublish} para publicação no RabbitMQ.
     *
     * <p>A coluna {@code processed} é verificada aqui como proteção adicional
     * contra replays de snapshot ({@code op = "r"}) que podem incluir registros
     * já processados anteriormente.</p>
     *
     * @param record {@link SourceRecord} do Debezium com os dados da linha inserida.
     */
    private void processRecord(SourceRecord record) {
        if (record.value() == null) {
            return;
        }

        Struct value = (Struct) record.value();
        String op    = value.getString("op");

        // Processa apenas INSERTs ("c" = create) e snapshots ("r" = read)
        if (!"c".equals(op) && !"r".equals(op)) {
            return;
        }

        Struct after = value.getStruct("after");
        if (after == null) {
            return;
        }

        // Extrai campos da linha — nomes em snake_case conforme DDL PostgreSQL
        String  rawId      = after.getString("id");
        String  eventType  = after.getString("event_type");
        String  aggregateId = after.getString("aggregate_id");
        String  payload    = after.getString("payload");
        Boolean processed  = after.getBoolean("processed");

        // Ignora registros já marcados como processados (possível durante snapshot)
        if (Boolean.TRUE.equals(processed)) {
            logger.debug("Outbox event {} já processado — ignorando snapshot replay.", rawId);
            return;
        }

        UUID eventId = UUID.fromString(rawId);
        publisher.claimAndPublish(eventId, eventType, aggregateId, payload);
    }

    // -------------------------------------------------------------------------
    // Startup: processa mensagens pendentes pré-existentes
    // -------------------------------------------------------------------------

    /**
     * Publica registros {@code processed=false} que existiam ANTES do engine
     * ser iniciado pela primeira vez (ex: criados com {@code debezium.enabled=false}
     * ou durante uma janela de downtime do engine).
     *
     * <p>Usa o overload sem paginação por ser chamado apenas UMA vez na inicialização.
     * Em produção, com {@code MemoryOffsetBackingStore}, cada restart replaya
     * os eventos do WAL, tornando este método secundário. Com {@code JdbcOffsetBackingStore},
     * o offset persiste no banco — este método cobre a janela de registros inseridos
     * antes do engine ser habilitado pela primeira vez (ex: com debezium.enabled=false).</p>
     */
    private void processExistingPendingMessages() {
        List<OutboxMessage> pending = outboxRepository.findByProcessedFalseOrderByCreatedAtAsc();
        if (pending.isEmpty()) {
            return;
        }

        logger.info("Processando {} mensagens Outbox pendentes pré-existentes na inicialização.",
                pending.size());

        for (OutboxMessage msg : pending) {
            try {
                publisher.claimAndPublish(
                        msg.getId(), msg.getEventType(),
                        msg.getAggregateId(), msg.getPayload());
            } catch (Exception e) {
                logger.warn("Falha ao processar mensagem pré-existente id={}: {}",
                        msg.getId(), e.getMessage(), e);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Gerenciamento do slot de replicação
    // -------------------------------------------------------------------------

    /**
     * Aguarda até que o slot de replicação Debezium apareça como {@code active=true}
     * na view {@code pg_replication_slots}.
     *
     * <p>Este método DEVE ser chamado após {@code executor.execute(engine)} para
     * garantir que o Debezium terminou de criar o slot e está pronto para capturar
     * eventos do WAL antes de qualquer INSERT de teste ou de negócio chegar.</p>
     *
     * <p>Sem este await, o engine inicia de forma assíncrona e há uma janela de
     * ~1-3 s durante a qual INSERTs passam pelo WAL sem serem detectados — causando
     * timeouts flaky nos testes de integração.</p>
     *
     * @param slotName Nome do slot configurado em {@code app.outbox.debezium.slot-name}.
     */
    private void awaitSlotActive(String slotName) {
        final int maxAttempts = 60;          // 60 × 500 ms = 30 s
        final String sql = "SELECT active FROM pg_replication_slots WHERE slot_name = ?";

        logger.info("Aguardando slot de replicação '{}' ficar ativo (máx 30s)...", slotName);

        for (int i = 0; i < maxAttempts; i++) {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, slotName);
                var rs = ps.executeQuery();
                if (rs.next() && rs.getBoolean("active")) {
                    logger.info("Slot '{}' ativo após ~{}ms — Debezium pronto para capturar WAL.",
                            slotName, i * 500L);
                    return;
                }
            } catch (Exception e) {
                logger.trace("Aguardando slot '{}' (tentativa {}): {}", slotName, i + 1, e.getMessage());
            }

            try {
                Thread.sleep(500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                logger.warn("awaitSlotActive interrompido — prosseguindo sem garantia.");
                return;
            }
        }

        logger.warn("Slot '{}' não ficou ativo em 30s — Debezium pode estar indisponível.", slotName);
    }

    /**
     * Dropa o slot de replicação PostgreSQL se estiver inativo.
     * Usado nas finalizações de teste para evitar acúmulo de slots inativos
     * que consumiriam {@code max_replication_slots}.
     *
     * @param slotName Nome do slot a ser dropado.
     */
    private void dropReplicationSlot(String slotName) {
        String sql = """
                SELECT pg_drop_replication_slot(slot_name)
                FROM   pg_replication_slots
                WHERE  slot_name = ? AND active = false
                """;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, slotName);
            ps.execute();
            logger.info("Slot de replicação '{}' removido.", slotName);
        } catch (Exception e) {
            logger.warn("Não foi possível remover slot de replicação '{}': {}",
                    slotName, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Builder das propriedades Debezium
    // -------------------------------------------------------------------------

    /**
     * Constrói as propriedades de configuração do Debezium PostgreSQL Connector.
     *
     * <p>Configurações notáveis:</p>
     * <ul>
     *   <li>{@code plugin.name = pgoutput}: decoder nativo do PostgreSQL 10+,
     *       dispensa plugins externos como {@code wal2json} ou {@code decoderbufs}.</li>
     *   <li>{@code snapshot.mode = never}: não replaya dados históricos na primeira
     *       conexão; apenas captura mudanças novas. Mensagens pré-existentes são
     *       tratadas por {@link #processExistingPendingMessages()}.</li>
     *   <li>{@code table.include.list}: limita o CDC APENAS à tabela {@code outbox_message},
     *       minimizando o volume de eventos Debezium processados.</li>
     *   <li>{@code decimal.handling.mode = string}: evita problemas de precisão
     *       ao serializar NUMERIC/DECIMAL do PostgreSQL.</li>
     *   <li><b>AT-08.1</b> {@code offset.storage = JdbcOffsetBackingStore}: persiste
     *       o LSN do WAL na tabela {@code wallet_outbox_offset} (migration V5).
     *       Garante continuidade exata após restart do container sem duplicatas.
     *       Para testes, usa {@code MemoryOffsetBackingStore} via {@code application-test.yaml}.</li>
     * </ul>
     *
     * @return {@link Properties} prontas para uso pelo {@link DebeziumEngine}.
     */
    private Properties buildDebeziumProperties() {
        OutboxProperties.DebeziumProperties cfg = properties.debezium();
        Properties props = new Properties();

        // --- Conector ---
        props.setProperty("connector.class",
                "io.debezium.connector.postgresql.PostgresConnector");

        // --- Conexão com PostgreSQL ---
        props.setProperty("database.hostname", cfg.dbHost());
        props.setProperty("database.port",     String.valueOf(cfg.dbPort()));
        props.setProperty("database.dbname",   cfg.dbName());
        props.setProperty("database.user",     cfg.dbUser());
        props.setProperty("database.password", cfg.dbPassword());

        // --- Identificação do conector ---
        // O 'name' deve ser único por engine instance (usado internamente pelo Debezium)
        props.setProperty("name", "wallet-outbox-connector");

        // --- WAL e replicação ---
        // pgoutput: plugin nativo PG 10+, sem dependência externa
        props.setProperty("plugin.name",  "pgoutput");
        props.setProperty("slot.name",    cfg.slotName());
        // Prefixo dos tópicos Debezium internos (não usados, mas obrigatório)
        props.setProperty("topic.prefix", "wallet");

        // --- Escopo: somente outbox_message (reduz I/O do WAL) ---
        props.setProperty("table.include.list", "public.outbox_message");

        // --- Snapshot: nunca replaya histórico; mensagens pré-existentes
        //     são tratadas pelo método processExistingPendingMessages() ---
        props.setProperty("snapshot.mode", "never");

        // --- Offset Storage (AT-08.1) ---
        // Produção: JdbcOffsetBackingStore — persiste LSN do WAL na tabela
        //           wallet_outbox_offset (migration V5). Sobrevive ao restart do container,
        //           eliminando risco de duplicatas por perda do offset em /tmp.
        // Testes:   MemoryOffsetBackingStore — descarta offset ao parar (sem persistência).
        //           Combinado com snapshot.mode=never, cada restart começa do LSN atual.
        boolean isMemoryStorage = cfg.offsetStorage().contains("MemoryOffsetBackingStore");
        boolean isJdbcStorage   = cfg.offsetStorage().contains("JdbcOffsetBackingStore");

        props.setProperty("offset.storage", cfg.offsetStorage());
        props.setProperty("offset.flush.interval.ms", "1000");

        // Propriedades específicas do JdbcOffsetBackingStore —
        // mapeadas para os campos de configuração internos do Debezium 2.7.x:
        //   offset.storage.jdbc.url      → URL de conexão JDBC
        //   offset.storage.jdbc.user     → usuário do banco
        //   offset.storage.jdbc.password → senha do banco
        //   offset.storage.jdbc.offset.table.name → tabela criada pela migration V5
        if (isJdbcStorage) {
            props.setProperty("offset.storage.jdbc.url",
                    cfg.offsetStorageJdbcUrl());
            props.setProperty("offset.storage.jdbc.user",
                    cfg.offsetStorageJdbcUsername());
            props.setProperty("offset.storage.jdbc.password",
                    cfg.offsetStorageJdbcPassword());
            props.setProperty("offset.storage.jdbc.offset.table.name",
                    cfg.offsetStorageJdbcTableName());
        }

        // --- Serialização ---
        props.setProperty("decimal.handling.mode", "string");

        // --- Schema history ---
        // Para PostgreSQL + pgoutput, o schema pode ser mantido em memória mesmo
        // em produção pois é reconstruído do WAL no restart (diferente do MySQL).
        // Isso resolve o problema secundário de FileSchemaHistory também armazenar
        // em /tmp — com JdbcOffsetBackingStore, usar MemorySchemaHistory é seguro.
        //
        // MemoryOffsetBackingStore (testes):  sempre usa MemorySchemaHistory.
        // JdbcOffsetBackingStore (produção):  usa MemorySchemaHistory — schema
        //   reconstruído do WAL no startup; nenhuma dependência de arquivo em /tmp.
        if (isMemoryStorage || isJdbcStorage) {
            props.setProperty("schema.history.internal",
                    "io.debezium.relational.history.MemorySchemaHistory");
        } else {
            // Fallback para implementações customizadas que não sejam JDBC ou Memory.
            // Em uso normal não deve ser atingido após AT-08.1.
            props.setProperty("schema.history.internal",
                    "io.debezium.relational.history.MemorySchemaHistory");
        }

        return props;
    }
}
