package com.vibranium.contracts.events.order;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.vibranium.contracts.enums.OrderType;
import com.vibranium.contracts.events.DomainEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Evento publicado quando a ordem foi inserida com sucesso no livro
 * de ofertas (Redis Sorted Set) e está aguardando uma contraparte.
 *
 * <p>Status da ordem transita: {@code PENDING → OPEN}.</p>
 *
 * <p>Consumidor: MongoDB (persist Read Model da ordem com status OPEN).</p>
 *
 * @param eventId       Identificador único deste evento.
 * @param correlationId Correlação da Saga.
 * @param aggregateId   orderId.
 * @param occurredOn    Timestamp de inserção no livro.
 * @param orderId       UUID da ordem inserida.
 * @param orderType     BUY ou SELL.
 * @param price         Preço limite da ordem no livro.
 * @param remainingAmount Quantidade ainda não executada.
 */
public record OrderAddedToBookEvent(

        UUID eventId,
        UUID correlationId,
        String aggregateId,

        @JsonFormat(shape = JsonFormat.Shape.NUMBER_INT)
        Instant occurredOn,

        UUID orderId,
        OrderType orderType,
        BigDecimal price,
        BigDecimal remainingAmount

) implements DomainEvent {

    /** Factory method com geração automática de eventId e occurredOn. */
    public static OrderAddedToBookEvent of(UUID correlationId, UUID orderId,
                                           OrderType orderType,
                                           BigDecimal price, BigDecimal remainingAmount) {
        return new OrderAddedToBookEvent(
                UUID.randomUUID(),
                correlationId,
                orderId.toString(),
                Instant.now(),
                orderId,
                orderType,
                price,
                remainingAmount
        );
    }
}
