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
 * <p><b>Exemplo de configuração:</b></p>
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
 *       offset-storage: org.apache.kafka.connect.storage.FileOffsetBackingStore
 *       offset-storage-file: /tmp/wallet-outbox-offset.dat
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
             * Classe de storage de offsets Debezium.
             * <ul>
             *   <li>Produção: {@code org.apache.kafka.connect.storage.FileOffsetBackingStore}</li>
             *   <li>Testes: {@code org.apache.kafka.connect.storage.MemoryOffsetBackingStore}</li>
             * </ul>
             */
            @DefaultValue("org.apache.kafka.connect.storage.FileOffsetBackingStore") String offsetStorage,

            /**
             * Caminho do arquivo de offsets (usado quando {@code offsetStorage} é FileOffsetBackingStore).
             * Ignorado para MemoryOffsetBackingStore.
             */
            @DefaultValue("/tmp/wallet-outbox-offset.dat") String offsetStorageFile,

            /**
             * Quando {@code true}, dropa o slot de replicação ao parar o engine.
             * Recomendado para ambientes de teste para evitar acúmulo de slots inactivos.
             * Em produção, manter {@code false} para não perder posição de leitura do WAL.
             */
            @DefaultValue("false") boolean dropSlotOnStop

    ) {}
}
