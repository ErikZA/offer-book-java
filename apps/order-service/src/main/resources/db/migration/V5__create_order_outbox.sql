-- ==============================================================================
-- V5: Tabela de Outbox do Order-Service
--
-- Implementa o Outbox Pattern para o OrderCommandService.placeOrder():
-- em vez de publicar ReserveFundsCommand diretamente no RabbitMQ (suscetível
-- à falha transiente do broker), o comando é gravado nesta tabela dentro da
-- MESMA transação que persiste a Ordem.
--
-- O OrderOutboxPublisherService (scheduler) faz o relay:
--   1. Busca mensagens com published_at IS NULL
--   2. Publica no RabbitMQ via RabbitTemplate
--   3. Atualiza published_at = now() na mesma transação
--
-- Inspirado no Debezium Outbox Event Router Schema:
--   https://debezium.io/documentation/reference/3.5/transformations/outbox-event-router
--   (aggregate_type, aggregate_id, type/event_type, payload)
-- ==============================================================================

CREATE TABLE IF NOT EXISTS tb_order_outbox (

    -- ID único da mensagem outbox (gerado pelo order-service, não pelo banco)
    id              UUID         NOT NULL,

    -- Tipo do agregado - sempre "Order" neste contexto
    aggregate_type  VARCHAR(50)  NOT NULL,

    -- ID do agregado afetado (orderId)
    aggregate_id    UUID         NOT NULL,

    -- Tipo do comando/evento a ser publicado (ex: "ReserveFundsCommand")
    event_type      VARCHAR(100) NOT NULL,

    -- Exchange RabbitMQ de destino
    exchange        VARCHAR(100) NOT NULL,

    -- Routing key de destino
    routing_key     VARCHAR(100) NOT NULL,

    -- Payload serializado como JSON
    payload         JSONB        NOT NULL,

    -- Timestamp de criação (gravado junto com a Ordem na mesma transação)
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- Timestamp de publicação bem-sucedida no broker (NULL = ainda não enviado)
    published_at    TIMESTAMPTZ  NULL,

    CONSTRAINT pk_order_outbox PRIMARY KEY (id)
);

-- Índice parcial para o query hot path do scheduler: busca apenas não-publicadas
CREATE INDEX IF NOT EXISTS idx_order_outbox_unpublished
    ON tb_order_outbox (created_at)
    WHERE published_at IS NULL;

COMMENT ON TABLE tb_order_outbox IS
    'Outbox Pattern para garantia de entrega do ReserveFundsCommand. '
    'Gravado na mesma transação que a Ordem; publicado no broker pelo OutboxPublisherService.';

COMMENT ON COLUMN tb_order_outbox.published_at IS
    'NULL = aguardando publicação. Preenchido pelo OutboxPublisherService após basicAck do broker.';
