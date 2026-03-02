package com.vibranium.contracts.events.wallet;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.vibranium.contracts.enums.FailureReason;
import com.vibranium.contracts.events.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Evento compensatório publicado quando o bloqueio de saldo falha.
 *
 * <p>Representa o caminho de falha do
 * {@link com.vibranium.contracts.commands.wallet.ReserveFundsCommand}:
 * o usuário não tem saldo suficiente ou a carteira não foi encontrada.</p>
 *
 * <p>Consumidor: {@code order-service} — ao receber este evento, deve
 * cancelar a ordem transitando seu status para {@code CANCELLED}.</p>
 *
 * @param eventId       Identificador único deste evento.
 * @param correlationId Correlação com a Saga.
 * @param aggregateId   ID da carteira (walletId) ou orderId se carteira
 *                      não encontrada.
 * @param occurredOn    Timestamp da falha.
 * @param orderId       Ordem que solicitou o bloqueio.
 * @param reason        Razão padronizada da falha (enum FailureReason).
 * @param detail        Mensagem técnica adicional para logs/auditoria.
 */
public record FundsReservationFailedEvent(

        UUID eventId,
        UUID correlationId,
        String aggregateId,

        @JsonFormat(shape = JsonFormat.Shape.NUMBER_INT)
        Instant occurredOn,

        UUID orderId,
        FailureReason reason,
        String detail,

        // Versionamento do contrato — permite deploy independente entre producer e consumer.
        int schemaVersion

) implements DomainEvent {

    /** Compact constructor: garante schemaVersion=1 para payloads antigos (backward compat). */
    public FundsReservationFailedEvent {
        if (schemaVersion == 0) schemaVersion = 1;
    }

    /** Factory method com geração automática de eventId e occurredOn. */
    public static FundsReservationFailedEvent of(UUID correlationId, UUID orderId,
                                                 String aggregateId,
                                                 FailureReason reason, String detail) {
        return new FundsReservationFailedEvent(
                UUID.randomUUID(),
                correlationId,
                aggregateId,
                Instant.now(),
                orderId,
                reason,
                detail,
                1
        );
    }
}
