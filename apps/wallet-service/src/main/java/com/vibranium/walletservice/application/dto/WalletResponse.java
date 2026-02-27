package com.vibranium.walletservice.application.dto;

import com.vibranium.walletservice.domain.model.Wallet;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * DTO de resposta dos endpoints REST de consulta de carteira.
 *
 * <p>Expõe os quatro saldos (available/locked para BRL e VIB) além
 * dos campos de identificação e auditoria.</p>
 *
 * <p>Utiliza factory {@link #from(Wallet)} para desacoplar a camada de
 * apresentação da entidade JPA, seguindo o princípio de separação de responsabilidades.</p>
 */
public record WalletResponse(

        UUID walletId,
        UUID userId,

        BigDecimal brlAvailable,
        BigDecimal brlLocked,

        BigDecimal vibAvailable,
        BigDecimal vibLocked,

        /** Timestamp de criação da carteira — campo de auditoria. */
        Instant createdAt

) {

    /**
     * Converte uma entidade {@link Wallet} para o DTO de resposta.
     *
     * @param wallet Entidade persistida com todos os saldos.
     * @return DTO pronto para serialização JSON.
     */
    public static WalletResponse from(Wallet wallet) {
        return new WalletResponse(
                wallet.getId(),
                wallet.getUserId(),
                wallet.getBrlAvailable(),
                wallet.getBrlLocked(),
                wallet.getVibAvailable(),
                wallet.getVibLocked(),
                wallet.getCreatedAt()
        );
    }
}
