-- ==============================================================================
-- V8: Event Store Imutável para wallet-service
--
-- Propósito: manter um log append-only de todos os eventos de domínio emitidos
-- pelo wallet-service. Complementar ao Transactional Outbox — enquanto a tabela
-- outbox_message serve como relay temporário para o broker, o Event Store é
-- o registro permanente e imutável para:
--   - Replay completo de eventos de saldo (reserve, release, settle)
--   - Audit trail para compliance regulatório financeiro
--   - Reconstrução de estado de qualquer carteira em qualquer ponto no tempo
--
-- Imutabilidade garantida por TRIGGER que rejeita UPDATE e DELETE.
-- Mesma estratégia do order-service (AT-14) — consistência arquitetural.
-- ==============================================================================

CREATE TABLE IF NOT EXISTS tb_event_store (

    -- Sequência monotônica global — ordering absoluto dos eventos
    sequence_id       BIGSERIAL    NOT NULL,

    -- UUID único do evento (DomainEvent.eventId()). Garante idempotência.
    event_id          UUID         NOT NULL,

    -- ID do agregado que originou o evento (ex: walletId)
    aggregate_id      VARCHAR(255) NOT NULL,

    -- Tipo do agregado (ex: "Wallet")
    aggregate_type    VARCHAR(100) NOT NULL,

    -- Tipo do evento (ex: "FundsReservedEvent", "FundsSettledEvent")
    event_type        VARCHAR(150) NOT NULL,

    -- Payload completo do evento serializado como JSONB para queries ad-hoc
    payload           JSONB        NOT NULL,

    -- Timestamp UTC de quando o fato ocorreu no domínio
    occurred_on       TIMESTAMPTZ  NOT NULL,

    -- ID de correlação da Saga (tracing distribuído)
    correlation_id    UUID         NOT NULL,

    -- Versão do schema do evento para evolução futura (backward compatibility)
    schema_version    INTEGER      NOT NULL DEFAULT 1,

    CONSTRAINT pk_event_store        PRIMARY KEY (sequence_id),
    CONSTRAINT uq_event_store_event  UNIQUE (event_id)
);

-- Índice composto para replay eficiente por agregado, ordenado pela sequência
CREATE INDEX IF NOT EXISTS idx_event_store_aggregate_replay
    ON tb_event_store (aggregate_id, sequence_id);

-- Índice para consulta por tipo de evento (auditoria)
CREATE INDEX IF NOT EXISTS idx_event_store_event_type
    ON tb_event_store (event_type);

-- Índice para consulta por correlationId (rastreabilidade de Saga)
CREATE INDEX IF NOT EXISTS idx_event_store_correlation
    ON tb_event_store (correlation_id);

COMMENT ON TABLE  tb_event_store IS
    'Event Store imutável (append-only). Registro permanente de todos os eventos '
    'de domínio emitidos pelo wallet-service. Protegido por trigger contra UPDATE/DELETE.';
COMMENT ON COLUMN tb_event_store.sequence_id IS
    'Sequência monotônica global (BIGSERIAL). Garante ordering absoluto dos eventos.';
COMMENT ON COLUMN tb_event_store.event_id IS
    'UUID único do evento (DomainEvent.eventId()). Constraint UNIQUE garante idempotência.';
COMMENT ON COLUMN tb_event_store.payload IS
    'Payload JSONB do evento para queries e replay. Formato versionado por schema_version.';
COMMENT ON COLUMN tb_event_store.schema_version IS
    'Versão do schema do evento. Permite evolução backward-compatible do payload.';

-- ==============================================================================
-- TRIGGER: Rejeita qualquer UPDATE na tabela tb_event_store.
-- Event Store é append-only — eventos de domínio são fatos imutáveis.
-- ==============================================================================
CREATE OR REPLACE FUNCTION fn_event_store_deny_update()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Event Store is append-only: UPDATE operations are not allowed on tb_event_store';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_event_store_deny_update
    BEFORE UPDATE ON tb_event_store
    FOR EACH ROW
    EXECUTE FUNCTION fn_event_store_deny_update();

-- ==============================================================================
-- TRIGGER: Rejeita qualquer DELETE na tabela tb_event_store.
-- Eventos nunca devem ser expurgados — imutabilidade por design.
-- ==============================================================================
CREATE OR REPLACE FUNCTION fn_event_store_deny_delete()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Event Store is append-only: DELETE operations are not allowed on tb_event_store';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_event_store_deny_delete
    BEFORE DELETE ON tb_event_store
    FOR EACH ROW
    EXECUTE FUNCTION fn_event_store_deny_delete();
