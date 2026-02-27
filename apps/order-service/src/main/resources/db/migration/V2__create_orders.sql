-- ==============================================================================
-- V2: Tabela de Ordens (Command Side)
--
-- Armazena o estado transacional de cada ordem enquanto a Saga de validação
-- de fundos está em andamento. Não é um Read Model — não é consultada pela
-- API de leitura (CQRS: Read Queries vão ao MongoDB/Redis).
--
-- Ciclo de vida: PENDING → OPEN | CANCELLED
--   PENDING  : criada pela API, aguardando FundsReservedEvent da Wallet
--   OPEN     : fundos bloqueados, inserida no livro de ofertas (Redis)
--   PARTIAL  : parcialmente executada por um ou mais matches (Redis)
--   FILLED   : 100% executada
--   CANCELLED: cancelada por InsuficientFunds, timeout ou rejeição do motor
-- ==============================================================================

CREATE TABLE IF NOT EXISTS tb_orders (
    -- Gerado pelo order-service na requisição REST (não pelo banco)
    id               UUID         PRIMARY KEY,

    -- Propaga o correlationId pelo ciclo completo da Saga (trace distribuído)
    correlation_id   UUID         NOT NULL,

    -- keycloak_id do usuário — deve existir em tb_user_registry
    -- FK lógica (não enforced): evita JOIN cross-service em alta frequência
    user_id          VARCHAR(36)  NOT NULL,

    -- Carteira do usuário informada na requisição REST (validada pela Wallet)
    wallet_id        UUID         NOT NULL,

    -- BUY ou SELL
    order_type       VARCHAR(4)   NOT NULL
        CONSTRAINT chk_order_type CHECK (order_type IN ('BUY', 'SELL')),

    -- Preço limite em BRL (escala monetária: 8 casas decimais)
    price            NUMERIC(18, 8) NOT NULL
        CONSTRAINT chk_price_positive CHECK (price > 0),

    -- Quantidade de VIBRANIUM (8 casas decimais para divisibilidade)
    amount           NUMERIC(18, 8) NOT NULL
        CONSTRAINT chk_amount_positive CHECK (amount > 0),

    -- Quantidade ainda não executada (decrementada a cada match parcial)
    remaining_amount NUMERIC(18, 8) NOT NULL,

    -- Máquina de estados da ordem
    status           VARCHAR(10)  NOT NULL DEFAULT 'PENDING'
        CONSTRAINT chk_status CHECK (status IN ('PENDING', 'OPEN', 'PARTIAL', 'FILLED', 'CANCELLED')),

    -- Timestamps de auditoria
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ
);

-- Índice para consultas do saga handler (busca pelo correlationId)
CREATE INDEX IF NOT EXISTS idx_orders_correlation_id
    ON tb_orders (correlation_id);

-- Índice para recuperação de ordens ativas de um usuário (dashboard / cancel)
CREATE INDEX IF NOT EXISTS idx_orders_user_status
    ON tb_orders (user_id, status);

COMMENT ON TABLE  tb_orders IS
    'Estado transacional das ordens no Command Side (CQRS). '
    'Não é Read Model — consultas de leitura vão ao MongoDB.';
COMMENT ON COLUMN tb_orders.correlation_id IS
    'Liga todos os eventos de uma mesma Saga: OrderReceived → FundsReserved → Match.';
COMMENT ON COLUMN tb_orders.remaining_amount IS
    'Decrementado a cada MatchExecutedEvent. Quando zerado, status = FILLED.';
