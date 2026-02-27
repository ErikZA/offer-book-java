-- V1: Criação da tabela principal de carteiras
-- Garante a regra 1:1 com user_id UNIQUE e constraints de saldo não-negativo

CREATE TABLE tb_wallet (
    id          UUID        NOT NULL DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL,
    brl_available NUMERIC(19, 8) NOT NULL DEFAULT 0,
    brl_locked    NUMERIC(19, 8) NOT NULL DEFAULT 0,
    vib_available NUMERIC(19, 8) NOT NULL DEFAULT 0,
    vib_locked    NUMERIC(19, 8) NOT NULL DEFAULT 0,
    created_at  TIMESTAMP   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMP   NOT NULL DEFAULT now(),

    CONSTRAINT pk_wallet PRIMARY KEY (id),
    CONSTRAINT uq_wallet_user_id UNIQUE (user_id),

    -- Invariantes financeiras: saldo nunca negativo
    CONSTRAINT chk_brl_available_non_negative CHECK (brl_available >= 0),
    CONSTRAINT chk_brl_locked_non_negative    CHECK (brl_locked >= 0),
    CONSTRAINT chk_vib_available_non_negative CHECK (vib_available >= 0),
    CONSTRAINT chk_vib_locked_non_negative    CHECK (vib_locked >= 0)
);

CREATE INDEX idx_wallet_user_id ON tb_wallet (user_id);
