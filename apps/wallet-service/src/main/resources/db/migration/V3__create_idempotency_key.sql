-- V3: Tabela de idempotência
-- Protege contra at-least-once delivery do RabbitMQ: o message_id é gravado
-- antes de processar qualquer comando — duplicata levanta UniqueConstraintViolation

CREATE TABLE idempotency_key (
    message_id   VARCHAR(36)  NOT NULL,
    processed_at TIMESTAMP    NOT NULL DEFAULT now(),

    CONSTRAINT pk_idempotency_key PRIMARY KEY (message_id)
);
