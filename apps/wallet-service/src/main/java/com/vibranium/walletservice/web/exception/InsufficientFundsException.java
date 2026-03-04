package com.vibranium.walletservice.web.exception;

/**
 * Exceção de domínio lançada quando uma operação resultaria em saldo negativo.
 *
 * <p>Mapeada pelo {@code GlobalExceptionHandler} para HTTP 422 Unprocessable Entity.
 * A mensagem sempre contém a palavra "saldo" para facilitar a identificação
 * por clientes e testes de integração.</p>
 */
public class InsufficientFundsException extends RuntimeException {

    public InsufficientFundsException(String message) {
        super(message);
    }
}
