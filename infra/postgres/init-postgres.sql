-- Inicialização do banco de dados PostgreSQL para Wallet Service
-- Cria schema e tabelas necessárias

CREATE SCHEMA IF NOT EXISTS vibranium;

-- Tabela de Wallets
CREATE TABLE IF NOT EXISTS vibranium.wallets (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    balance NUMERIC(19, 8) NOT NULL DEFAULT 0,
    available_balance NUMERIC(19, 8) NOT NULL DEFAULT 0,
    reserved_balance NUMERIC(19, 8) NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    version BIGINT DEFAULT 0,
    UNIQUE(user_id, currency)
);

CREATE INDEX idx_wallet_user ON vibranium.wallets(user_id);
CREATE INDEX idx_wallet_currency ON vibranium.wallets(currency);

-- Tabela de Transações de Wallet (para idempotência)
CREATE TABLE IF NOT EXISTS vibranium.wallet_transactions (
    id BIGSERIAL PRIMARY KEY,
    event_id VARCHAR(255) NOT NULL UNIQUE,
    wallet_id BIGINT NOT NULL REFERENCES vibranium.wallets(id),
    user_id VARCHAR(255) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    amount NUMERIC(19, 8) NOT NULL,
    type VARCHAR(10) NOT NULL CHECK (type IN ('DEBIT', 'CREDIT')),
    reason VARCHAR(100) NOT NULL,
    reference_id VARCHAR(255),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED')),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    balance_before NUMERIC(19, 8),
    balance_after NUMERIC(19, 8)
);

CREATE INDEX idx_transaction_event ON vibranium.wallet_transactions(event_id);
CREATE INDEX idx_transaction_wallet ON vibranium.wallet_transactions(wallet_id);
CREATE INDEX idx_transaction_status ON vibranium.wallet_transactions(status);

-- Permissões
ALTER TABLE vibranium.wallets OWNER TO postgres;
ALTER TABLE vibranium.wallet_transactions OWNER TO postgres;
