-- =============================================================================
-- Migration: V6__fix_wallet_outbox_offset_val_type.sql
-- NOTA HISTÓRICA: Corrige tipo incorreto da coluna offset_val criada na V5.
--
-- Bug original: V5 criou offset_val como BYTEA, mas o backing store gravava
-- o offset como VARCHAR (String). PostgreSQL rejeitava a escrita com:
--   ERROR: column "offset_val" is of type bytea
--          but expression is of type character varying
--
-- Correção: ALTER COLUMN para VARCHAR(1255).
-- Esta tabela é dropada integralmente pela V7.
-- Migration mantida para preservar integridade do checksum Flyway.
-- =============================================================================

ALTER TABLE wallet_outbox_offset
    ALTER COLUMN offset_val TYPE VARCHAR(1255);

COMMENT ON COLUMN wallet_outbox_offset.offset_val IS
    'Offset serializado (LSN do WAL) como string pelo JdbcOffsetBackingStore. '
    'Corrigido de BYTEA para VARCHAR em V6. '
    'Tabela removida integralmente pela V7 (migração para Polling SKIP LOCKED).';
