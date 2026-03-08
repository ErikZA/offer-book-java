package com.vibranium.orderservice.web.exception;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.sql.SQLTransientConnectionException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Handler global de exceções do order-service.
 *
 * <p>Converte exceções de domínio e infraestrutura em respostas HTTP padronizadas.
 * Segue OWASP A01: NUNCA retorna stack trace, nome de classe, query SQL ou
 * detalhes internos ao cliente.</p>
 *
 * <p>Hierarquia de handlers:</p>
 * <ul>
 *   <li>{@link UserNotRegisteredException} → 403 Forbidden</li>
 *   <li>{@link MethodArgumentNotValidException} → 400 Bad Request</li>
 *   <li>Pool Exhaustion (SQLTransient, BulkheadFull, CircuitBreakerOpen) → 503 + Retry-After</li>
 *   <li>Catch-all {@link Exception} → 500 genérico (sem vazamento de info)</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Tempo padrão de retry em segundos para 503 Service Unavailable. */
    private static final int DEFAULT_RETRY_AFTER_SECONDS = 5;

    /**
     * Usuário autenticado mas não registrado localmente → 403 Forbidden.
     */
    @ExceptionHandler(UserNotRegisteredException.class)
    ResponseEntity<Map<String, Object>> handleUserNotRegistered(UserNotRegisteredException ex) {
        logger.warn("Tentativa de ordem por usuário não registrado: keycloakId={}",
                ex.getKeycloakId());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "USER_NOT_REGISTERED");
        body.put("message", ex.getMessage());
        body.put("keycloakId", ex.getKeycloakId());
        body.put("timestamp", Instant.now().toEpochMilli());

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    /**
     * Falha de validação Bean Validation no body da requisição → 400 Bad Request.
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
    // BUG-04: Pool Exhaustion / Resource Unavailable → 503 + Retry-After
    // -------------------------------------------------------------------------

    /**
     * HikariCP pool exhaustion — todas as conexões em uso, timeout esgotado.
     */
    @ExceptionHandler(SQLTransientConnectionException.class)
    ResponseEntity<ErrorResponse> handleSqlPoolExhaustion(SQLTransientConnectionException ex) {
        logger.error("Pool exhaustion on PostgreSQL: {}", ex.getMessage(), ex);
        return serviceUnavailable();
    }

    /**
     * Spring Data não conseguiu obter recurso (PG, MongoDB, etc.).
     */
    @ExceptionHandler(DataAccessResourceFailureException.class)
    ResponseEntity<ErrorResponse> handleDataAccessResourceFailure(DataAccessResourceFailureException ex) {
        logger.error("Pool exhaustion on data resource: {}", ex.getMessage(), ex);
        return serviceUnavailable();
    }

    /**
     * Resilience4j Circuit Breaker em estado OPEN — recurso externo indisponível.
     */
    @ExceptionHandler(CallNotPermittedException.class)
    ResponseEntity<ErrorResponse> handleCircuitBreakerOpen(CallNotPermittedException ex) {
        logger.warn("Circuit breaker OPEN — call not permitted: {}", ex.getMessage());
        return serviceUnavailable();
    }

    /**
     * Resilience4j Bulkhead cheio — max concurrent calls atingido.
     */
    @ExceptionHandler(BulkheadFullException.class)
    ResponseEntity<ErrorResponse> handleBulkheadFull(BulkheadFullException ex) {
        logger.warn("Bulkhead full — rejecting request: {}", ex.getMessage());
        return serviceUnavailable();
    }

    // -------------------------------------------------------------------------
    // Catch-all: previne vazamento de informação (OWASP A01)
    // -------------------------------------------------------------------------

    /**
     * Handler de último recurso — captura qualquer exceção não tratada.
     * Retorna 500 sem stack trace ou detalhes internos.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        ErrorResponse body = ErrorResponse.of("Internal server error");
        // Log completo no servidor para debugging
        logger.error("Unhandled exception (correlationId={}): {}", body.correlationId(), ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ResponseEntity<ErrorResponse> serviceUnavailable() {
        ErrorResponse body = ErrorResponse.of("Service temporarily unavailable", DEFAULT_RETRY_AFTER_SECONDS);
        HttpHeaders headers = new HttpHeaders();
        headers.set("Retry-After", String.valueOf(DEFAULT_RETRY_AFTER_SECONDS));
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).headers(headers).body(body);
    }

    private String safeMessage(FieldError err) {
        return err.getDefaultMessage() != null ? err.getDefaultMessage() : "Valor inválido";
    }
}
