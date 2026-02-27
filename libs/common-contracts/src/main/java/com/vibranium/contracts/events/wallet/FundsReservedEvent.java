package com.vibranium.contracts.events.wallet;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.vibranium.contracts.enums.AssetType;
import com.vibranium.contracts.events.DomainEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Evento publicado quando o saldo foi bloqueado com sucesso na carteira.
 *
 * <p>Confirma que o {@link com.vibranium.contracts.commands.wallet.ReserveFundsCommand}
 * foi executado: o campo {@code available} foi decrementado e {@code locked}
 * foi incrementado no PostgreSQL em transação ACID.</p>
 *
 * <p>Consumidor: {@code order-service} — ao receber este evento, adiciona
 * a ordem ao livro de ofertas no Redis.</p>
 *
 * @param eventId        Identificador único deste evento.
 * @param correlationId  Correlação com a Saga.
 * @param aggregateId    ID da carteira (walletId).
 * @param occurredOn     Timestamp de quando o bloqueio ocorreu.
 * @param orderId        Ordem que originou o bloqueio.
 * @param walletId       Carteira onde o bloqueio foi aplicado.
 * @param asset          Ativo bloqueado (BRL para BUY, VIBRANIUM para SELL).
 * @param reservedAmount Valor efetivamente bloqueado.
 */
public record FundsReservedEvent(

        UUID eventId,
        UUID correlationId,
        String aggregateId,

        @JsonFormat(shape = JsonFormat.Shape.NUMBER_INT)
        Instant occurredOn,

        UUID orderId,
        UUID walletId,
        AssetType asset,
        BigDecimal reservedAmount

) implements DomainEvent {

    /** Factory method com geração automática de eventId e occurredOn. */
    public static FundsReservedEvent of(UUID correlationId, UUID orderId,
                                        UUID walletId, AssetType asset,
                                        BigDecimal reservedAmount) {
        return new FundsReservedEvent(
                UUID.randomUUID(),
                correlationId,
                walletId.toString(),
                Instant.now(),
                orderId,
                walletId,
                asset,
                reservedAmount
        );
    }
}
