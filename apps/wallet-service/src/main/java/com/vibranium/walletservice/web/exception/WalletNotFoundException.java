package com.vibranium.walletservice.web.exception;

import java.util.UUID;

/**
 * Exceção de domínio lançada quando uma carteira não é encontrada no banco.
 *
 * <p>Mapeada pelo {@code GlobalExceptionHandler} para HTTP 404 Not Found.</p>
 */
public class WalletNotFoundException extends RuntimeException {

    public WalletNotFoundException(String message) {
        super(message);
    }

    /**
     * Factory para buscas por ID de carteira (PATCH /balance, reserve, settle).
     */
    public static WalletNotFoundException forWalletId(UUID walletId) {
        return new WalletNotFoundException("Carteira não encontrada: id=" + walletId);
    }

    /**
     * Factory para buscas por userId (GET /wallets/{userId}).
     */
    public static WalletNotFoundException forUserId(UUID userId) {
        return new WalletNotFoundException("Carteira não encontrada para o usuário: id=" + userId);
    }
}
