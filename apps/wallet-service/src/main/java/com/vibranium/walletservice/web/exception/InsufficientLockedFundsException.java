package com.vibranium.walletservice.web.exception;

/**
 * Exceção de domínio lançada quando uma operação compensatória de release
 * tentaria decrementar um saldo "locked" além do que está efetivamente bloqueado.
 *
 * <p>Diferencia-se de {@link InsufficientFundsException} (que trata saldo
 * "available") para tornar explícito no diagnóstico que o problema está no
 * caminho compensatório da Saga — saldo bloqueado insuficiente para o release.</p>
 *
 * <p>Mapeada pelo {@code GlobalExceptionHandler} para HTTP 422 Unprocessable Entity.</p>
 */
public class InsufficientLockedFundsException extends RuntimeException {

    public InsufficientLockedFundsException(String message) {
        super(message);
    }
}
