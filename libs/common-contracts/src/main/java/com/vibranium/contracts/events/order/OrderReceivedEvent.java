package com.vibranium.contracts.events.order;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.vibranium.contracts.enums.OrderType;
import com.vibranium.contracts.events.DomainEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Evento publicado quando o {@code order-service} aceita e valida
 * uma nova intenção de compra ou venda.
 *
 * <p>Este é o <strong>evento de entrada da Saga</strong>:
 * ao publicá-lo, o {@code order-service} inicia a coreografia assíncrona
 * e retorna {@code 202 Accepted} para o cliente.</p>
 *
 * <p>Consumidor: {@code wallet-service} — ao receber este evento, executa
 * o {@link com.vibranium.contracts.commands.wallet.ReserveFundsCommand}.</p>
 *
 * @param eventId       Identificador único deste evento.
 * @param correlationId Correlação da Saga — gerado na API de entrada.
 * @param aggregateId   orderId.
 * @param occurredOn    Timestamp de recebimento da ordem.
 * @param orderId       UUID da ordem criada.
 * @param userId        UUID do usuário que enviou a ordem.
 * @param walletId      Carteira do usuário (para o wallet-service reservar).
 * @param orderType     BUY ou SELL.
 * @param price         Preço limite em BRL.
 * @param amount        Quantidade de VIBRANIUM desejada.
 */
public record OrderReceivedEvent(

        UUID eventId,
        UUID correlationId,
        String aggregateId,

        @JsonFormat(shape = JsonFormat.Shape.NUMBER_INT)
        Instant occurredOn,

        UUID orderId,
        UUID userId,
        UUID walletId,
        OrderType orderType,
        BigDecimal price,
        BigDecimal amount,

        // Versionamento do contrato — permite deploy independente entre producer e consumer.
        // Valor padrão 1 é aplicado pelo compact constructor quando ausente no JSON.
        int schemaVersion

) implements DomainEvent {

    /**
     * Compact constructor: garante {@code schemaVersion=1} ao desserializar payloads
     * antigos que não possuíam este campo (backward compatibility).
     */
    public OrderReceivedEvent {
        if (schemaVersion == 0) schemaVersion = 1;
    }

    /** Factory method com geração automática de eventId e occurredOn. */
    public static OrderReceivedEvent of(UUID correlationId, UUID orderId,
                                        UUID userId, UUID walletId,
                                        OrderType orderType,
                                        BigDecimal price, BigDecimal amount) {
        return new OrderReceivedEvent(
                UUID.randomUUID(),
                correlationId,
                orderId.toString(),
                Instant.now(),
                orderId,
                userId,
                walletId,
                orderType,
                price,
                amount,
                1
        );
    }
}
