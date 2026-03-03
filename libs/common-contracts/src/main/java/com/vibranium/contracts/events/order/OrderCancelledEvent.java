package com.vibranium.contracts.events.order;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.vibranium.contracts.enums.FailureReason;
import com.vibranium.contracts.events.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Evento publicado quando uma ordem é cancelada — seja por falha de
 * validação, saldo insuficiente (resposta da wallet-service), expiração
 * de tempo ou solicitação explícita do usuário.
 *
 * <p>Status da ordem transita: qualquer status → {@code CANCELLED}.</p>
 *
 * <p>Ao publicar este evento, o {@code order-service} deve também
 * remover a entrada da ordem no Redis (se ainda estiver no livro).</p>
 *
 * @param eventId       Identificador único deste evento.
 * @param correlationId Correlação da Saga.
 * @param aggregateId   orderId.
 * @param occurredOn    Timestamp do cancelamento.
 * @param orderId       Ordem cancelada.
 * @param reason        Razão padronizada do cancelamento.
 * @param detail        Descrição técnica do motivo (para auditoria).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderCancelledEvent(

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
    public OrderCancelledEvent {
        if (schemaVersion == 0) schemaVersion = 1;
    }

    /** Factory method com geração automática de eventId e occurredOn. */
    public static OrderCancelledEvent of(UUID correlationId, UUID orderId,
                                         FailureReason reason, String detail) {
        return new OrderCancelledEvent(
                UUID.randomUUID(),
                correlationId,
                orderId.toString(),
                Instant.now(),
                orderId,
                reason,
                detail,
                1
        );
    }
}
