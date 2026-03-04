-- =============================================================================
-- V7: Remove tabela de offset do relay CDC (JdbcOffsetBackingStore)
-- =============================================================================
-- Contexto: Migração do relay do Outbox para Polling SKIP LOCKED.
-- A tabela wallet_outbox_offset armazenava o LSN (Log Sequence Number) do WAL
-- para controle de posição do relay CDC. Com Polling SKIP LOCKED, o relay
-- é baseado em SELECT no campo processed=false, tornando esta tabela
-- desnecessária.
--
-- Nota: As migrations V5 e V6 (que criaram e alteraram esta tabela) são mantidas
-- no histórico para preservar o checksum do Flyway. V7 encerra o ciclo de vida
-- desta tabela.
-- =============================================================================

DROP TABLE IF EXISTS wallet_outbox_offset;
