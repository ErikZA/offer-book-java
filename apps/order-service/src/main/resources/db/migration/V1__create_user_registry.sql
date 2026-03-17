-- ==============================================================================
-- V1: Registry local de usuários autorizados a operar
--
-- Propósito: validação de integridade referencial SEM chamadas HTTP síncronas
-- ao Keycloak. O order-service rejeita ordens de userId não presente aqui.
--
-- Populado via: KeycloakUserRabbitListener consome evento REGISTER do plugin
-- aznamier (amq.topic routing key KK.EVENT.CLIENT.orderbook-realm.SUCCESS.*.REGISTER).
-- ==============================================================================

CREATE TABLE IF NOT EXISTS tb_user_registry (
    -- PK interna — gerada pelo banco, não exposta na API
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),

    -- ID do usuário no Keycloak (UUID string). Único e imutável após criação.
    keycloak_id      VARCHAR(36) NOT NULL,

    -- Timestamp UTC do evento REGISTER recebido do Keycloak via RabbitMQ
    registered_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_keycloak_id UNIQUE (keycloak_id)
);

-- Índice para o lookup crítico no caminho quente: "este userId pode operar?"
CREATE INDEX IF NOT EXISTS idx_user_registry_keycloak_id
    ON tb_user_registry (keycloak_id);

COMMENT ON TABLE  tb_user_registry IS
    'Registry local de usuários Keycloak autorizados a colocar ordens. '
    'Processado via evento REGISTER do plugin keycloak-to-rabbitmq (aznamier).';
COMMENT ON COLUMN tb_user_registry.keycloak_id IS
    'UUID do sub claim do JWT Keycloak. Usado na validação pré-aceitação de ordem.';
