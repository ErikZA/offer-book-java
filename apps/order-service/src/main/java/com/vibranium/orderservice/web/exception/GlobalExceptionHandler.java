package com.vibranium.orderservice.web.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Handler global de exceções do order-service.
 *
 * <p>Converte exceções de domínio em respostas HTTP com
 * {@link ProblemDetail} (RFC 7807) para compatibilidade com o ecossistema REST.</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Usuário autenticado mas não registrado localmente → 403 Forbidden.
     *
     * <p>Body contém {@code "error": "USER_NOT_REGISTERED"} para que o cliente
     * possa distinguir do 403 genérico de permissão insuficiente.</p>
     */
    @ExceptionHandler(UserNotRegisteredException.class)
    ProblemDetail handleUserNotRegistered(UserNotRegisteredException ex) {
        logger.warn("Tentativa de ordem por usuário não registrado: keycloakId={}",
                ex.getKeycloakId());

        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, ex.getMessage());
        pd.setType(URI.create("https://vibranium.com/errors/user-not-registered"));
        pd.setTitle("Usuário não registrado");
        pd.setProperty("error", "USER_NOT_REGISTERED");
        pd.setProperty("keycloakId", ex.getKeycloakId());
        pd.setProperty("timestamp", Instant.now().toEpochMilli());
        return pd;
    }

    /**
     * Falha de validação Bean Validation no body da requisição → 400 Bad Request.
     *
     * <p>Retorna lista de erros por campo para facilitar correção no cliente.</p>
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        List<Map<String, String>> fieldErrors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(err -> Map.of(
                        "field", err.getField(),
                        "message", safeMessage(err)))
                .toList();

        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Payload inválido: campos com erro de validação");
        pd.setType(URI.create("https://vibranium.com/errors/validation-failed"));
        pd.setTitle("Erro de validação");
        pd.setProperty("errors", fieldErrors);
        return pd;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String safeMessage(FieldError err) {
        return err.getDefaultMessage() != null ? err.getDefaultMessage() : "Valor inválido";
    }
}
