package com.vibranium.utils.secret;

/**
 * Exceção lançada quando um arquivo de Docker Secret não pode ser lido.
 *
 * <p>Causas típicas: arquivo inexistente, permissão negada, conteúdo vazio.</p>
 */
public class SecretReadException extends RuntimeException {

    public SecretReadException(String message) {
        super(message);
    }

    public SecretReadException(String message, Throwable cause) {
        super(message, cause);
    }
}
