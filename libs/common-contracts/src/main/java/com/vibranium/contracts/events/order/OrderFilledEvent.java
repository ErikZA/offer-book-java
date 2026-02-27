package com.vibranium.contracts.events.order;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.vibranium.contracts.events.DomainEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Evento publicado quando a ordem foi totalmente executada.
 *
 * <p>Status da ordem transita: {@code OPEN|PARTIAL → FILLED}.</p>
 *
 * <p>Após este evento, a ordem é removida do livro de ofertas (Redis)
 * e seu documento no MongoDB é atualizado com o status final.</p>
 *
 * @param eventId       Identificador único deste evento.
 * @param correlationId Correlação da Saga.
 * @param aggregateId   orderId.
 * @param occurredOn    Timestamp do preenchimento total.
 * @param orderId       Ordem totalmente executada.
 * @param totalFilled   Quantidade total de VIBRANIUM executada.
 * @param averagePrice  Preço médio de execução (para ordens com matches parciais).
 */
public record OrderFilledEvent(

        UUID eventId,
        UUID correlationId,
        String aggregateId,

        @JsonFormat(shape = JsonFormat.Shape.NUMBER_INT)
        Instant occurredOn,

        UUID orderId,
        BigDecimal totalFilled,
        BigDecimal averagePrice

) implements DomainEvent {

    /** Factory method com geração automática de eventId e occurredOn. */
    public static OrderFilledEvent of(UUID correlationId, UUID orderId,
                                      BigDecimal totalFilled, BigDecimal averagePrice) {
        return new OrderFilledEvent(
                UUID.randomUUID(),
                correlationId,
                orderId.toString(),
                Instant.now(),
                orderId,
                totalFilled,
                averagePrice
        );
    }
}
