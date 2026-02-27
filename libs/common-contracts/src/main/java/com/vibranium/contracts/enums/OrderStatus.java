package com.vibranium.contracts.enums;

/**
 * Máquina de estados de uma Order no sistema.
 *
 * <pre>
 * PENDING → OPEN → PARTIAL → FILLED
 *         ↘                ↘ CANCELLED
 * </pre>
 *
 * PENDING   : recebida pela API, aguardando bloqueio de fundos.
 * OPEN      : fundos bloqueados, inserida no livro de ofertas (Redis).
 * PARTIAL   : parcialmente executada por um ou mais matches.
 * FILLED    : totalmente executada — encerrada.
 * CANCELLED : cancelada por saldo insuficiente, expiração ou falha.
 */
public enum OrderStatus {
    PENDING,
    OPEN,
    PARTIAL,
    FILLED,
    CANCELLED
}
