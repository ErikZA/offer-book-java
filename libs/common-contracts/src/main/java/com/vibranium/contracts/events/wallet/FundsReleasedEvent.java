package com.vibranium.contracts.events.wallet;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.vibranium.contracts.enums.AssetType;
import com.vibranium.contracts.events.DomainEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Evento publicado quando o saldo bloqueado foi liberado com sucesso na carteira.
 *
 * <p>Confirma que o {@link com.vibranium.contracts.commands.wallet.ReleaseFundsCommand}
 * foi executado: o campo {@code locked} foi decrementado e {@code available}
 * foi incrementado no PostgreSQL em transação ACID — restaurando o estado
 * anterior à reserva.</p>
 *
 * <p>É o evento compensatório correspondente ao {@link FundsReservedEvent}:
 * cada reserva bem-sucedida pode ser desfeita por um release.</p>
 *
 * <p>Consumidor: {@code order-service} — ao receber este evento, confirma
 * que a compensação foi concluída e pode finalizar o ciclo da Saga.</p>
 *
 * @param eventId        Identificador único deste evento.
 * @param correlationId  Correlação com a Saga.
 * @param aggregateId    ID da carteira (walletId).
 * @param occurredOn     Timestamp de quando a liberação ocorreu.
 * @param orderId        Ordem cujo bloqueio foi liberado.
 * @param walletId       Carteira onde a liberação foi aplicada.
 * @param asset          Ativo liberado (BRL para BUY, VIBRANIUM para SELL).
 * @param releasedAmount Valor efetivamente liberado de "locked" para "available".
 * @param schemaVersion  Versão do contrato para compatibilidade consumer/producer.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FundsReleasedEvent(

        UUID eventId,
        UUID correlationId,
        String aggregateId,

        @JsonFormat(shape = JsonFormat.Shape.NUMBER_INT)
        Instant occurredOn,

        UUID orderId,
        UUID walletId,
        AssetType asset,
        BigDecimal releasedAmount,

        // Versionamento do contrato — permite deploy independente entre producer e consumer.
        int schemaVersion

) implements DomainEvent {

    /** Compact constructor: garante schemaVersion=1 para payloads antigos (backward compat). */
    public FundsReleasedEvent {
        if (schemaVersion == 0) schemaVersion = 1;
    }

    /** Factory method com geração automática de eventId e occurredOn. */
    public static FundsReleasedEvent of(UUID correlationId, UUID orderId,
                                        UUID walletId, AssetType asset,
                                        BigDecimal releasedAmount) {
        return new FundsReleasedEvent(
                UUID.randomUUID(),
                correlationId,
                walletId.toString(),
                Instant.now(),
                orderId,
                walletId,
                asset,
                releasedAmount,
                1
        );
    }
}
