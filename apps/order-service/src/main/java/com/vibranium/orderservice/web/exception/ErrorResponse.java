package com.vibranium.orderservice.web.exception;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO padronizado de resposta de erro (BUG-04).
 *
 * <p>Garante que NENHUMA informação interna (stack trace, classe, query SQL)
 * seja vazada para o cliente. Apenas campos seguros são serializados.</p>
 *
 * <p>O {@code correlationId} permite rastreamento no log do servidor sem
 * expor detalhes internos ao consumidor da API.</p>
 */
public record ErrorResponse(
        String error,
        String correlationId,
        long timestamp,
        Integer retryAfter
) {
    /**
     * Cria uma resposta de erro com correlationId gerado automaticamente.
     *
     * @param error      Mensagem de erro genérica e segura.
     * @param retryAfter Tempo em segundos para retry (nullable — apenas para 503).
     */
    public static ErrorResponse of(String error, Integer retryAfter) {
        return new ErrorResponse(
                error,
                UUID.randomUUID().toString(),
                Instant.now().toEpochMilli(),
                retryAfter
        );
    }

    /**
     * Cria uma resposta de erro sem header Retry-After.
     */
    public static ErrorResponse of(String error) {
        return of(error, null);
    }
}
