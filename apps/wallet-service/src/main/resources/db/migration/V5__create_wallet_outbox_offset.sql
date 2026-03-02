-- =============================================================================
-- Migration: V5__create_wallet_outbox_offset.sql
-- AT-08.1: Migra armazenamento de offset do Debezium de FileOffsetBackingStore
--          para JdbcOffsetBackingStore.
--
-- Propósito:
--   Persiste a posição do WAL (LSN) lida pelo Debezium Embedded Engine no banco
--   de dados em vez de em arquivo local (/tmp). Isso garante que o offset
--   sobreviva ao restart do container, eliminando a possibilidade de:
--     (a) Reprocessamento de eventos já publicados (duplicatas no RabbitMQ)
--     (b) Perda de eventos se o WAL for truncado antes do próximo processamento
--
-- Schema requerido pelo JdbcOffsetBackingStore (Debezium 2.7.x):
--   - id          : VARCHAR(255) PRIMARY KEY — identificador único do conector.
--                   O Debezium usa "wallet-outbox-connector" como valor.
--   - offset_key  : VARCHAR(1255) — chave de partição do offset (ex: partition info).
--   - offset_val  : BYTEA — valor binário serializado do offset (LSN + posição).
--   - record_insert_ts  : TIMESTAMP — auditoria: timestamp de inserção do registro.
--   - record_insert_seq : SERIAL    — auditoria: sequência de inserção (ordem relativa).
--
-- Garantias:
--   - Debezium verifica se a tabela existe antes de criar; com Flyway rodando
--     antes do contexto Spring, a tabela sempre existirá quando o engine iniciar.
--   - A PRIMARY KEY em `id` garante lookup O(1) para leitura/escrita do offset.
--   - `record_insert_seq` SERIAL garante ordem de inserção sem dependência de relógio.
-- =============================================================================

CREATE TABLE IF NOT EXISTS wallet_outbox_offset (
    -- Identificador do conector Debezium.
    -- O JdbcOffsetBackingStore usa um hash/UUID interno como chave primária.
    id                 VARCHAR(255) PRIMARY KEY,

    -- Chave do offset (contém informações de partição/conector serializado pelo Debezium).
    -- Tamanho 1255 alinhado ao padrão da tabela debezium_offset_storage da lib oficial.
    offset_key         VARCHAR(1255),

    -- Valor do offset serializado como bytes (usa formato interno do Kafka Connect).
    -- Contém o LSN (Log Sequence Number) do WAL PostgreSQL confirmado pelo Debezium.
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
    'Armazenamento persistente de offsets do Debezium Embedded Engine (JdbcOffsetBackingStore). '
    'Registra a posição LSN do WAL PostgreSQL confirmada pelo relay Outbox → RabbitMQ. '
    'Criado em: AT-08.1 — Migração de FileOffsetBackingStore para JdbcOffsetBackingStore.';

COMMENT ON COLUMN wallet_outbox_offset.offset_val IS
    'Offset serializado (LSN do WAL) em formato Kafka Connect interno (bytes). '
    'Persistir aqui garante que restart do container não cause reprocessamento de eventos.';
