-- V2: Transactional Outbox — garante entrega de eventos ao RabbitMQ sem 2-phase commit
-- O campo processed é atualizado pelo scheduler após publicar no broker

CREATE TABLE outbox_message (
    id           UUID        NOT NULL DEFAULT gen_random_uuid(),
    event_type   VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(36)  NOT NULL,
    payload      TEXT        NOT NULL,
    created_at   TIMESTAMP   NOT NULL DEFAULT now(),
    processed    BOOLEAN     NOT NULL DEFAULT FALSE,

    CONSTRAINT pk_outbox_message PRIMARY KEY (id)
);

-- Índice parcial: apenas mensagens não processadas são consultadas pelo scheduler
CREATE INDEX idx_outbox_unprocessed ON outbox_message (created_at)
    WHERE processed = FALSE;
