package com.vibranium.contracts.enums;

/**
 * Razões padronizadas de falha para eventos compensatórios da Saga.
 * Permite que consumidores tomem decisões sem fazer parse de mensagens livres.
 */
public enum FailureReason {
    /** Saldo disponível insuficiente para bloquear o valor da ordem. */
    INSUFFICIENT_FUNDS,

    /** Carteira não encontrada para o userId informado. */
    WALLET_NOT_FOUND,

    /** Transação de liquidação falhou por erro ACID no PostgreSQL. */
    SETTLEMENT_DB_ERROR,

    /** Tempo máximo de processamento da Saga excedido. */
    SAGA_TIMEOUT,

    /** Violação de idempotência — mensagem já foi processada. */
    DUPLICATE_MESSAGE,

    /** Erro interno não classificado. */
    INTERNAL_ERROR
}
