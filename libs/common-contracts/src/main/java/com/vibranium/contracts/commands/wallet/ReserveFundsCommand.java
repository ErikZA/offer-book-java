package com.vibranium.contracts.commands.wallet;

import com.vibranium.contracts.commands.Command;
import com.vibranium.contracts.enums.AssetType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Comando para reservar (bloquear) saldo na carteira antes de uma ordem
 * entrar no livro de ofertas.
 *
 * <p>Garante que o usuário possui fundos antes do Motor de Match (Redis)
 * processar a ordem — evitando ordens sem cobertura financeira.</p>
 *
 * <p>Para uma ordem BUY: bloqueia {@code amount} de BRL.<br>
 * Para uma ordem SELL: bloqueia {@code amount} de VIBRANIUM.</p>
 *
 * @param correlationId ID de correlação propagado da ordem original.
 * @param orderId       ID da ordem que solicitou o bloqueio.
 * @param walletId      ID da carteira do usuário dono da ordem.
 * @param asset         Tipo do ativo a ser bloqueado (BRL ou VIBRANIUM).
 * @param amount        Valor a ser movido de "available" para "locked".
 */
public record ReserveFundsCommand(

        @NotNull UUID correlationId,
        @NotNull UUID orderId,
        @NotNull UUID walletId,
        @NotNull AssetType asset,

        @NotNull
        @DecimalMin(value = "0.0", inclusive = false, message = "Amount must be positive")
        BigDecimal amount,

        // Versionamento do contrato — permite deploy independente entre producer e consumer.
        int schemaVersion

) implements Command {

    /** Compact constructor: garante schemaVersion=1 para payloads antigos (backward compat). */
    public ReserveFundsCommand {
        if (schemaVersion == 0) schemaVersion = 1;
    }
}
