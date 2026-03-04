package com.vibranium.walletservice.web.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

/**
 * Handler global de exceções para os endpoints REST do wallet-service.
 *
 * <p>Mapeia exceções de domínio e de validação para respostas HTTP padronizadas.
 * Todos os erros retornam um JSON com o campo {@code message} para facilitar
 * o diagnóstico por clientes.</p>
 *
 * <p>Mapeamentos:</p>
 * <ul>
 *   <li>{@link WalletNotFoundException} → 404 Not Found</li>
 *   <li>{@link InsufficientFundsException} → 422 Unprocessable Entity</li>
 *   <li>{@link InsufficientLockedFundsException} → 422 Unprocessable Entity (caminho compensatório Saga)</li>
 *   <li>{@link MethodArgumentNotValidException} → 400 Bad Request (validação JSR-380)</li>
 *   <li>{@link MethodArgumentTypeMismatchException} → 400 Bad Request (UUID inválido na URL)</li>
 *   <li>{@link HttpMessageNotReadableException} → 400 Bad Request (JSON malformado)</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Resposta padronizada de erro. O campo {@code message} é usado pelos
     * testes de integração para verificar a mensagem de erro.
     */
    public record ErrorResponse(String message) {}

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
        // Saldo bloqueado insuficiente: incidente crítico no caminho compensatório da Saga.
        // Logado como ERROR pois indica inconsistência de estado — locked < 0 nunca deveria ocorrer.
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
}
