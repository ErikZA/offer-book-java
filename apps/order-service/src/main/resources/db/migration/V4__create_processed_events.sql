-- ==============================================================================
-- V4: Tabela de idempotência para eventos consumidos pelo order-service.
--
-- Propósito: Garantir at-most-once processing para mensagens RabbitMQ com
-- at-least-once delivery. Um eventId é inserido ANTES de qualquer lógica de
-- negócio; se a linha já existe (PK duplicada) o evento é descartado.
--
-- Mesma estratégia usada no wallet-service (V3__create_idempotency_key.sql).
-- ==============================================================================

CREATE TABLE IF NOT EXISTS tb_processed_events (
    event_id     UUID         NOT NULL,
    processed_at TIMESTAMP    NOT NULL DEFAULT now(),

    CONSTRAINT pk_processed_events PRIMARY KEY (event_id)
);

COMMENT ON TABLE  tb_processed_events              IS 'Registro de eventos já processados; garante idempotência via PK única.';
COMMENT ON COLUMN tb_processed_events.event_id     IS 'UUID imutável do evento (DomainEvent.eventId()). PK garante unicidade.';
COMMENT ON COLUMN tb_processed_events.processed_at IS 'Timestamp de quando o evento foi aceito pela primeira vez.';
