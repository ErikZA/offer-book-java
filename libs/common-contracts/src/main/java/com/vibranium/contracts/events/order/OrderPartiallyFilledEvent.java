package com.vibranium.contracts.events.order;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.vibranium.contracts.events.DomainEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Evento publicado quando um match parcial ocorre: a ordem foi
 * executada em parte, mas ainda possui quantidade restante no livro.
 *
 * <p>Status da ordem transita: {@code OPEN → PARTIAL}.</p>
 *
 * <p>Exemplo: ordem de 100 VIBRANIUM encontrou vendedor de 40.
 * A ordem permanece no livro com {@code remainingAmount = 60}.</p>
 *
 * @param eventId         Identificador único deste evento.
 * @param correlationId   Correlação da Saga.
 * @param aggregateId     orderId.
 * @param occurredOn      Timestamp do match parcial.
 * @param orderId         Ordem parcialmente executada.
 * @param matchId         ID do match que originou a execução parcial.
 * @param filledAmount    Quantidade executada neste match.
 * @param remainingAmount Quantidade restante no livro de ofertas.
 */
public record OrderPartiallyFilledEvent(

        UUID eventId,
        UUID correlationId,
        String aggregateId,

        @JsonFormat(shape = JsonFormat.Shape.NUMBER_INT)
        Instant occurredOn,

        UUID orderId,
        UUID matchId,
        BigDecimal filledAmount,
        BigDecimal remainingAmount,

        // Versionamento do contrato — permite deploy independente entre producer e consumer.
        int schemaVersion

) implements DomainEvent {

    /** Compact constructor: garante schemaVersion=1 para payloads antigos (backward compat). */
    public OrderPartiallyFilledEvent {
        if (schemaVersion == 0) schemaVersion = 1;
    }

    /** Factory method com geração automática de eventId e occurredOn. */
    public static OrderPartiallyFilledEvent of(UUID correlationId, UUID orderId,
                                               UUID matchId,
                                               BigDecimal filledAmount,
                                               BigDecimal remainingAmount) {
        return new OrderPartiallyFilledEvent(
                UUID.randomUUID(),
                correlationId,
                orderId.toString(),
                Instant.now(),
                orderId,
                matchId,
                filledAmount,
                remainingAmount,
                1
        );
    }
}
