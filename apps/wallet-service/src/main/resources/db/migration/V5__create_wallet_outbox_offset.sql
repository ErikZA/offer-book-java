-- =============================================================================
-- Migration: V5__create_wallet_outbox_offset.sql
-- NOTA HISTÓRICA (AT-08.1): Esta migration criou a tabela de offset usada pelo
-- relay CDC (Debezium Embedded Engine), que foi posteriormente removido do projeto.
-- A tabela wallet_outbox_offset é dropada pela V7__drop_wallet_outbox_offset.sql.
--
-- Com a migração para Polling SKIP LOCKED (OutboxPublisherService), o offset
-- não precisa mais ser persistido externamente — o relay usa o campo
-- `processed = false` para selecionar mensagens pendentes.
--
-- Esta migration é mantida no histórico Flyway para preservar integridade
-- do checksum da cadeia de migrations.
-- =============================================================================

CREATE TABLE IF NOT EXISTS wallet_outbox_offset (
    -- Identificador único do conector (usado pelo JdbcOffsetBackingStore).
    id                 VARCHAR(255) PRIMARY KEY,

    -- Chave de partição do offset serializado.
    -- Tamanho 1255 alinhado ao padrão da tabela de offset_storage da lib oficial.
    offset_key         VARCHAR(1255),

    -- Valor do offset serializado como bytes (LSN do WAL PostgreSQL).
    offset_val         BYTEA,

    -- Timestamp de inserção — usado para auditoria e diagnóstico.
    -- DEFAULT CURRENT_TIMESTAMP garante preenchimento automático.
    record_insert_ts   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Sequência de inserção — garante ordenação determinística de registros
    -- inseridos no mesmo timestamp (ex: bootstrap inicial).
    record_insert_seq  SERIAL NOT NULL
);

-- Índice auxiliar para consultas de diagnóstico por timestamp
-- (ex: "quando foi o último offset salvo?")
CREATE INDEX IF NOT EXISTS idx_wallet_outbox_offset_ts
    ON wallet_outbox_offset (record_insert_ts DESC);

-- Comentário de tabela para documentação no banco
COMMENT ON TABLE wallet_outbox_offset IS
    'Armazenamento persistente de offsets do relay Outbox (JdbcOffsetBackingStore). '
    'Registra a posição LSN do WAL PostgreSQL confirmada pelo relay Outbox → RabbitMQ. '
    'Criado em: AT-08.1. Tabela removida em V7 após migração para Polling SKIP LOCKED.';

COMMENT ON COLUMN wallet_outbox_offset.offset_val IS
    'Offset serializado (LSN do WAL) em formato Kafka Connect interno (bytes). '
    'Persistir aqui garante que restart do container não cause reprocessamento de eventos.';
