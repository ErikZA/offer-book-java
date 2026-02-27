package com.vibranium.orderservice.web.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Handler global de exceções do order-service.
 *
 * <p>Converte exceções de domínio em respostas HTTP no formato JSON plano
 * ({@code ResponseEntity<Map>}), garantindo que campos como {@code error} e
 * {@code errors} fiquem no root do JSON e sejam acessíveis via JSONPath
 * (ex.: {@code $.error}, {@code $.errors[0].field}).</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Usuário autenticado mas não registrado localmente → 403 Forbidden.
     *
     * <p>Retorna JSON plano com {@code "error": "USER_NOT_REGISTERED"} no root
     * para garantir compatibilidade com {@code $.error} nos testes e clientes.
     * Evita depender do mixin Jackson do {@code ProblemDetail} (RFC 7807), que
     * requer o {@code ObjectMapper} auto-configurado do Spring Boot — o qual
     * não está disponível quando se usa {@code new ObjectMapper()} customizado.</p>
     */
    @ExceptionHandler(UserNotRegisteredException.class)
    ResponseEntity<Map<String, Object>> handleUserNotRegistered(UserNotRegisteredException ex) {
        logger.warn("Tentativa de ordem por usuário não registrado: keycloakId={}",
                ex.getKeycloakId());

        // LinkedHashMap preserva ordem de inserção para leitura mais clara no log/debug.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "USER_NOT_REGISTERED");
        body.put("message", ex.getMessage());
        body.put("keycloakId", ex.getKeycloakId());
        body.put("timestamp", Instant.now().toEpochMilli());

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    /**
     * Falha de validação Bean Validation no body da requisição → 400 Bad Request.
     *
     * <p>Retorna JSON plano com {@code "errors": [{"field": ..., "message": ...}]}
     * no root level, compatível com o caminho JSONPath {@code $.errors[0].field}
     * utilizado nos testes de integração.</p>
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        List<Map<String, String>> fieldErrors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(err -> Map.of(
                        "field", err.getField(),
                        "message", safeMessage(err)))
                .toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("errors", fieldErrors);
        body.put("message", "Payload inválido: campos com erro de validação");
        body.put("timestamp", Instant.now().toEpochMilli());

        return ResponseEntity.badRequest().body(body);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String safeMessage(FieldError err) {
        return err.getDefaultMessage() != null ? err.getDefaultMessage() : "Valor inválido";
    }
}
