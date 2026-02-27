package com.vibranium.contracts.events.wallet;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.vibranium.contracts.events.DomainEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Evento publicado quando a liquidação de um trade foi concluída com sucesso.
 *
 * <p>Confirma que o {@link com.vibranium.contracts.commands.wallet.SettleFundsCommand}
 * foi executado em transação ACID no PostgreSQL:</p>
 * <ul>
 *   <li>Comprador: BRL locked → VIBRANIUM available</li>
 *   <li>Vendedor: VIBRANIUM locked → BRL available</li>
 * </ul>
 *
 * <p>Consumidor: {@code order-service} — atualiza o status das ordens
 * e persiste o Trade Document no MongoDB.</p>
 *
 * @param eventId        Identificador único deste evento.
 * @param correlationId  Correlação com a Saga.
 * @param aggregateId    matchId — chave do trade liquidado.
 * @param occurredOn     Timestamp da liquidação.
 * @param matchId        ID único do match liquidado.
 * @param buyOrderId     Ordem de compra liquidada.
 * @param sellOrderId    Ordem de venda liquidada.
 * @param buyerWalletId  Carteira do comprador.
 * @param sellerWalletId Carteira do vendedor.
 * @param matchPrice     Preço em BRL do trade.
 * @param matchAmount    Quantidade de VIBRANIUM transacionada.
 */
public record FundsSettledEvent(

        UUID eventId,
        UUID correlationId,
        String aggregateId,

        @JsonFormat(shape = JsonFormat.Shape.NUMBER_INT)
        Instant occurredOn,

        UUID matchId,
        UUID buyOrderId,
        UUID sellOrderId,
        UUID buyerWalletId,
        UUID sellerWalletId,
        BigDecimal matchPrice,
        BigDecimal matchAmount

) implements DomainEvent {

    /** Factory method com geração automática de eventId e occurredOn. */
    public static FundsSettledEvent of(UUID correlationId, UUID matchId,
                                       UUID buyOrderId, UUID sellOrderId,
                                       UUID buyerWalletId, UUID sellerWalletId,
                                       BigDecimal matchPrice, BigDecimal matchAmount) {
        return new FundsSettledEvent(
                UUID.randomUUID(),
                correlationId,
                matchId.toString(),
                Instant.now(),
                matchId,
                buyOrderId,
                sellOrderId,
                buyerWalletId,
                sellerWalletId,
                matchPrice,
                matchAmount
        );
    }
}
