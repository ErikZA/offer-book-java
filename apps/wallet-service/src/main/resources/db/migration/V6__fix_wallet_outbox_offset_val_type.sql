-- =============================================================================
-- Migration: V6__fix_wallet_outbox_offset_val_type.sql
-- Corrige tipo incorreto da coluna offset_val na tabela wallet_outbox_offset.
--
-- Bug: A V5 criou offset_val como BYTEA, mas o JdbcOffsetBackingStore do
--      Debezium 2.7.x grava o offset serializado como VARCHAR (String).
--      O PostgreSQL rejeita a escrita com:
--
--        ERROR: column "offset_val" is of type bytea
--               but expression is of type character varying
--
-- Correção: ALTER COLUMN para VARCHAR(1255), alinhado ao tipo de offset_key
--           e ao schema padrão da lib debezium-storage-jdbc.
-- =============================================================================

ALTER TABLE wallet_outbox_offset
    ALTER COLUMN offset_val TYPE VARCHAR(1255);

COMMENT ON COLUMN wallet_outbox_offset.offset_val IS
    'Offset serializado (LSN do WAL) como string pelo JdbcOffsetBackingStore do Debezium 2.7.x. '
    'Persistir aqui garante que restart do container não cause reprocessamento de eventos. '
    'Corrigido de BYTEA para VARCHAR em V6 — Debezium serializa como String, não como bytes.';
