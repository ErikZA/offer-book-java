-- ==============================================================================
-- V3: Adiciona colunas extras na tabela de ordens
--
-- cancellation_reason: detalhe textual do motivo de cancelamento (saga)
-- version            : coluna de controle para Optimistic Locking (JPA @Version)
-- ==============================================================================

ALTER TABLE tb_orders
    ADD COLUMN IF NOT EXISTS cancellation_reason VARCHAR(500),
    ADD COLUMN IF NOT EXISTS version             BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN tb_orders.cancellation_reason IS
    'Motivo técnico do cancelamento (ex: INSUFFICIENT_FUNDS). Preenchido pelo handler da Saga.';
COMMENT ON COLUMN tb_orders.version IS
    'Versão para Optimistic Locking do JPA (@Version). Garante detecção de conflitos.';
