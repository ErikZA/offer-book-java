package com.vibranium.contracts.commands.wallet;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.vibranium.contracts.commands.Command;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Comando para liquidar um trade após o Motor de Match confirmar o cruzamento.
 *
 * <p>Instrui o {@code wallet-service} a realizar as transferências ACID:</p>
 * <ul>
 *   <li>Comprador: debita BRL locked → credita VIBRANIUM available</li>
 *   <li>Vendedor: debita VIBRANIUM locked → credita BRL available</li>
 * </ul>
 *
 * <p>Este comando deve ser processado de forma idempotente usando o
 * {@code matchId} como chave na tabela {@code idempotency_key}.</p>
 *
 * @param correlationId   ID de correlação da Saga.
 * @param matchId         ID único do match — chave de idempotência.
 * @param buyOrderId      ID da ordem de compra que foi cruzada.
 * @param sellOrderId     ID da ordem de venda que foi cruzada.
 * @param buyerWalletId   Carteira do comprador.
 * @param sellerWalletId  Carteira do vendedor.
 * @param matchPrice      Preço em BRL pelo qual o match foi executado.
 * @param matchAmount     Quantidade de VIBRANIUM transacionada.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SettleFundsCommand(

        @NotNull UUID correlationId,
        @NotNull UUID matchId,
        @NotNull UUID buyOrderId,
        @NotNull UUID sellOrderId,
        @NotNull UUID buyerWalletId,
        @NotNull UUID sellerWalletId,

        @NotNull
        @DecimalMin(value = "0.0", inclusive = false, message = "Match price must be positive")
        BigDecimal matchPrice,

        @NotNull
        @DecimalMin(value = "0.0", inclusive = false, message = "Match amount must be positive")
        BigDecimal matchAmount,

        // Versionamento do contrato — permite deploy independente entre producer e consumer.
        int schemaVersion

) implements Command {

    /** Compact constructor: garante schemaVersion=1 para payloads antigos (backward compat). */
    public SettleFundsCommand {
        if (schemaVersion == 0) schemaVersion = 1;
    }
}
