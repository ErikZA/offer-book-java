-- V4: Adiciona coluna de versão para optimistic locking no agregado Wallet
--
-- O @Version do JPA implementa optimistic locking: cada UPDATE incrementa
-- a versão e detecta conflitos se outro processo alterou o registro no meio
-- da transação (lança OptimisticLockException). Complementa o lock pessimista
-- (SELECT FOR UPDATE) já existente, adicionando detecção de conflito fora do
-- escopo de uma única transação (ex: leitura sem lock seguida de escrita).

ALTER TABLE tb_wallet
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
