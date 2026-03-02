package com.vibranium.walletservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configurações do módulo Outbox Publisher, mapeadas a partir do prefixo
 * {@code app.outbox} no {@code application.yaml}.
 *
 * <p>Uso de {@link ConfigurationProperties} com records garante imutabilidade,
 * tipagem segura e validação automática pelo Spring Boot Binder.
 * Ativado via {@code @EnableConfigurationProperties} na classe de configuração
 * ou via {@code spring.factories}.</p>
 *
 * <p><b>Exemplo de configuração (AT-08.1 — JdbcOffsetBackingStore):</b></p>
 * <pre>
 * app:
 *   outbox:
 *     batch-size: 100
 *     debezium:
 *       enabled: true
 *       db-host: localhost
 *       db-port: 5432
 *       db-name: vibranium_wallet
 *       db-user: postgres
 *       db-password: postgres
 *       slot-name: wallet_outbox_slot
 *       offset-storage: io.debezium.storage.jdbc.offset.JdbcOffsetBackingStore
 *       offset-storage-jdbc-url: ${SPRING_DATASOURCE_URL}
 *       offset-storage-jdbc-username: ${SPRING_DATASOURCE_USERNAME}
 *       offset-storage-jdbc-password: ${SPRING_DATASOURCE_PASSWORD}
 *       offset-storage-jdbc-table-name: wallet_outbox_offset
 *       drop-slot-on-stop: false
 * </pre>
 */
@ConfigurationProperties(prefix = "app.outbox")
public record OutboxProperties(

        /**
         * Tamanho máximo do lote de mensagens processadas por ciclo quando o
         * fallback de Pageable é utilizado. Default: 100.
         */
        @DefaultValue("100") int batchSize,

        /** Configurações específicas do Debezium CDC. */
        DebeziumProperties debezium

) {

    /**
     * Propriedades do Debezium Embedded Engine.
     *
     * <p>{@code enabled = false} por padrão garante que o engine NÃO seja
     * iniciado em ambientes que não configurem a propriedade explicitamente,
     * evitando falhas de conexão com o PostgreSQL em contextos sem WAL logical.</p>
     */
    public record DebeziumProperties(

            /**
             * Habilita o Debezium Embedded Engine via
             * {@link com.vibranium.walletservice.infrastructure.outbox.DebeziumOutboxEngine}.
             * Default: {@code false}.
             */
            @DefaultValue("false") boolean enabled,

            /** Host do PostgreSQL. Sobrescrito dinamicamente nos testes via Testcontainers. */
            @DefaultValue("localhost") String dbHost,

            /** Porta do PostgreSQL. Default padrão PostgreSQL. */
            @DefaultValue("5432") int dbPort,

            /** Nome do banco de dados de destino. */
            @DefaultValue("vibranium_wallet") String dbName,

            /** Usuário com permissão de replicação (REPLICATION ou SUPERUSER). */
            @DefaultValue("postgres") String dbUser,

            /** Senha do usuário. Deve ser externalizada via variável de ambiente em produção. */
            @DefaultValue("postgres") String dbPassword,

            /**
             * Nome do slot de replicação lógica no PostgreSQL.
             * Deve ser único por consumer. No mesmo host, cada instância do serviço
             * deve usar um slot diferente para receber todos os eventos (fan-out).
             *
             * <p>Cada slot consome WAL independentemente; o claim atômico em
             * {@link com.vibranium.walletservice.infrastructure.outbox.OutboxPublisherService}
             * garante que apenas UM publique cada evento no RabbitMQ.</p>
             */
            @DefaultValue("wallet_outbox_slot") String slotName,

            /**
             * Classe de storage de offsets do Debezium.
             *
             * <ul>
             *   <li><b>Produção (AT-08.1):</b>
             *       {@code io.debezium.storage.jdbc.offset.JdbcOffsetBackingStore} —
             *       persiste o LSN do WAL na tabela {@code wallet_outbox_offset} do PostgreSQL.
             *       Garante sobrevivência ao restart do container e continuidade exata do WAL.</li>
             *   <li><b>Testes:</b>
             *       {@code org.apache.kafka.connect.storage.MemoryOffsetBackingStore} —
             *       sem persistência entre testes; cada contexto começa do LSN atual.</li>
             * </ul>
             *
             * <p>A antiga opção {@code FileOffsetBackingStore} foi removida em AT-08.1
             * por armazenar offsets em {@code /tmp} (efêmero em containers Docker),
             * causando potencial duplicação de eventos após restart.</p>
             */
            @DefaultValue("io.debezium.storage.jdbc.offset.JdbcOffsetBackingStore") String offsetStorage,

            // ----------------------------------------------------------------
            // Propriedades exclusivas do JdbcOffsetBackingStore (AT-08.1)
            // ----------------------------------------------------------------

            /**
             * URL JDBC para conexão do {@code JdbcOffsetBackingStore} com o banco de dados.
             * Deve apontar para o mesmo banco da datasource principal do serviço.
             *
             * <p>Em produção, referenciar {@code ${SPRING_DATASOURCE_URL}} para reuso
             * das credenciais já configuradas. Em testes, sobrescrito via
             * {@code @DynamicPropertySource} com a URL do container Testcontainers.</p>
             *
             * <p>Ignorado quando {@code offsetStorage} for {@code MemoryOffsetBackingStore}.</p>
             */
            @DefaultValue("jdbc:postgresql://localhost:5432/vibranium_wallet") String offsetStorageJdbcUrl,

            /**
             * Usuário para a conexão JDBC do {@code JdbcOffsetBackingStore}.
             * Deve ser o mesmo da datasource principal para evitar configuração duplicada.
             *
             * <p>Ignorado quando {@code offsetStorage} for {@code MemoryOffsetBackingStore}.</p>
             */
            @DefaultValue("postgres") String offsetStorageJdbcUsername,

            /**
             * Senha para a conexão JDBC do {@code JdbcOffsetBackingStore}.
             * Deve ser externalizada via variável de ambiente em produção.
             *
             * <p>Ignorado quando {@code offsetStorage} for {@code MemoryOffsetBackingStore}.</p>
             */
            @DefaultValue("postgres") String offsetStorageJdbcPassword,

            /**
             * Nome da tabela onde o {@code JdbcOffsetBackingStore} persiste os offsets.
             * Criada pela migration Flyway {@code V5__create_wallet_outbox_offset.sql}.
             *
             * <p>O Debezium 2.7.x usa a tabela para ler/escrever o LSN confirmado do WAL.
             * A PRIMARY KEY em {@code id} garante lookup O(1) por ID de conector.
             * Default alinhado ao schema criado pela migration V5.</p>
             */
            @DefaultValue("wallet_outbox_offset") String offsetStorageJdbcTableName,

            /**
             * Quando {@code true}, dropa o slot de replicação ao parar o engine.
             * Recomendado para ambientes de teste para evitar acúmulo de slots inactivos.
             * Em produção, manter {@code false} para não perder posição de leitura do WAL.
             */
            @DefaultValue("false") boolean dropSlotOnStop

    ) {}
}
