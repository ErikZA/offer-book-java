package com.vibranium.contracts.commands.wallet;

import com.vibranium.contracts.commands.Command;
import com.vibranium.contracts.enums.AssetType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Comando compensatório para liberar (desbloquear) saldo na carteira.
 *
 * <p>É o inverso do {@link ReserveFundsCommand}: move {@code amount} de
 * "locked" de volta para "available" no PostgreSQL — restaurando o estado
 * da carteira anterior à reserva.</p>
 *
 * <p>Disparado pela Saga Choreography quando uma ordem precisa ser cancelada
 * após a reserva ter sido realizada com sucesso (ex.: falha no motor de match,
 * cancelamento externo ou timeout da Saga).</p>
 *
 * <p>Para uma ordem BUY: libera {@code amount} de BRL.<br>
 * Para uma ordem SELL: libera {@code amount} de VIBRANIUM.</p>
 *
 * @param correlationId ID de correlação propagado da Saga original.
 * @param orderId       ID da ordem cujo bloqueio deve ser revertido.
 * @param walletId      ID da carteira do usuário dono da ordem.
 * @param asset         Tipo do ativo a ser liberado (BRL ou VIBRANIUM).
 * @param amount        Valor a ser movido de "locked" de volta para "available".
 * @param schemaVersion Versão do contrato para compatibilidade consumer/producer.
 */
public record ReleaseFundsCommand(

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
    public ReleaseFundsCommand {
        if (schemaVersion == 0) schemaVersion = 1;
    }
}
