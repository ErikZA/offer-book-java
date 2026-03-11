package com.vibranium.walletservice.web.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.sql.SQLTransientConnectionException;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Handler global de exceções para os endpoints REST do wallet-service.
 *
 * <p>Mapeia exceções de domínio, validação e infraestrutura para respostas HTTP padronizadas.
 * Segue OWASP A01: NUNCA retorna stack trace ou detalhes internos ao cliente.</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final int DEFAULT_RETRY_AFTER_SECONDS = 5;

    /**
     * Resposta padronizada de erro.
     */
    public record ErrorResponse(String message) {}

    /**
     * Resposta padronizada de erro com correlação e retry (BUG-04).
     */
    public record ServiceUnavailableResponse(String error, String correlationId, long timestamp, int retryAfter) {}

    // -------------------------------------------------------------------------
    // Erros de domínio
    // -------------------------------------------------------------------------

    @ExceptionHandler(WalletNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleWalletNotFound(WalletNotFoundException ex) {
        logger.warn("Wallet not found: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientFunds(InsufficientFundsException ex) {
        logger.warn("Insufficient funds: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(InsufficientLockedFundsException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientLockedFunds(InsufficientLockedFundsException ex) {
        logger.error("Insufficient locked funds (Saga compensation path): {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ErrorResponse(ex.getMessage()));
    }

    // -------------------------------------------------------------------------
    // Erros de validação / desserialização
    // -------------------------------------------------------------------------

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getAllErrors().stream()
                .map(error -> error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        logger.debug("Validation error: {}", message);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(message));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String message = "Parâmetro inválido '" + ex.getName() + "': valor '" +
                ex.getValue() + "' não pode ser convertido para " +
                (ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "tipo esperado");
        logger.debug("Type mismatch: {}", message);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException ex) {
        logger.debug("Message not readable: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("Corpo da requisição inválido ou malformado"));
    }

    // -------------------------------------------------------------------------
    // BUG-04: Pool Exhaustion / Resource Unavailable → 503 + Retry-After
    // -------------------------------------------------------------------------

    @ExceptionHandler(SQLTransientConnectionException.class)
    public ResponseEntity<ServiceUnavailableResponse> handleSqlPoolExhaustion(SQLTransientConnectionException ex) {
        logger.error("Pool exhaustion on PostgreSQL: {}", ex.getMessage(), ex);
        return serviceUnavailable();
    }

    @ExceptionHandler(DataAccessResourceFailureException.class)
    public ResponseEntity<ServiceUnavailableResponse> handleDataAccessResourceFailure(DataAccessResourceFailureException ex) {
        logger.error("Pool exhaustion on data resource: {}", ex.getMessage(), ex);
        return serviceUnavailable();
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        logger.warn("Access denied: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse("Access denied"));
    }

    @ExceptionHandler(ErrorResponseException.class)
    public ResponseEntity<ErrorResponse> handleFrameworkHttpException(ErrorResponseException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        String message = safeHttpMessage(ex, status);

        if (status != null && status.is5xxServerError()) {
            logger.error("HTTP framework exception mapped to {}: {}", ex.getStatusCode().value(), ex.getMessage(), ex);
        } else {
            logger.warn("HTTP framework exception mapped to {}: {}", ex.getStatusCode().value(), ex.getMessage());
        }

        return ResponseEntity.status(ex.getStatusCode()).body(new ErrorResponse(message));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException ex) {
        logger.warn("HTTP resource not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("Resource not found"));
    }

    // -------------------------------------------------------------------------
    // Catch-all: previne vazamento de informação (OWASP A01)
    // -------------------------------------------------------------------------

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        String correlationId = UUID.randomUUID().toString();
        logger.error("Unhandled exception (correlationId={}): {}", correlationId, ex.getMessage(), ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("Internal server error"));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ResponseEntity<ServiceUnavailableResponse> serviceUnavailable() {
        ServiceUnavailableResponse body = new ServiceUnavailableResponse(
                "Service temporarily unavailable",
                UUID.randomUUID().toString(),
                Instant.now().toEpochMilli(),
                DEFAULT_RETRY_AFTER_SECONDS
        );
        HttpHeaders headers = new HttpHeaders();
        headers.set("Retry-After", String.valueOf(DEFAULT_RETRY_AFTER_SECONDS));
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).headers(headers).body(body);
    }

    private String safeHttpMessage(ErrorResponseException ex, HttpStatus status) {
        if (status == HttpStatus.NOT_FOUND) {
            return "Resource not found";
        }
        String reason = ex.getBody() != null ? ex.getBody().getDetail() : null;
        if (reason != null && !reason.isBlank()) {
            return reason;
        }
        return status != null ? status.getReasonPhrase() : "Request failed";
    }
}



