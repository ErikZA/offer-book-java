package com.vibranium.contracts.events.order;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.vibranium.contracts.events.DomainEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * EVENTO CRÍTICO — Publicado quando o Motor de Match (Redis) cruza
 * com sucesso uma ordem de compra com uma de venda.
 *
 * <p>Este evento <strong>inicia a Fase de Liquidação (Settlement)</strong>:
 * o {@code wallet-service} consome este evento e executa o
 * {@link com.vibranium.contracts.commands.wallet.SettleFundsCommand}.</p>
 *
 * <p>Contém os dois lados do trade para que a liquidação seja atômica:</p>
 * <ul>
 *   <li>Lado comprador: {@code buyOrderId} + {@code buyerUserId}</li>
 *   <li>Lado vendedor: {@code sellOrderId} + {@code sellerUserId}</li>
 * </ul>
 *
 * <p><strong>Rastreabilidade:</strong> Este evento deve ser persistido
 * no MongoDB como {@code TradeDocument} para geração de gráficos Candlestick.</p>
 *
 * @param eventId        Identificador único deste evento.
 * @param correlationId  Correlação da Saga (vem do buyOrder original).
 * @param aggregateId    matchId — ID único deste cruzamento.
 * @param occurredOn     Timestamp exato do match (base para Candlestick).
 * @param matchId        UUID único do match.
 * @param buyOrderId     ID da ordem de compra cruzada.
 * @param sellOrderId    ID da ordem de venda cruzada.
 * @param buyerUserId    Usuário comprador.
 * @param sellerUserId   Usuário vendedor.
 * @param buyerWalletId  Carteira do comprador.
 * @param sellerWalletId Carteira do vendedor.
 * @param matchPrice     Preço em BRL pelo qual o negócio fechou.
 * @param matchAmount    Quantidade de VIBRANIUM transacionada.
 */
public record MatchExecutedEvent(

        UUID eventId,
        UUID correlationId,
        String aggregateId,

        @JsonFormat(shape = JsonFormat.Shape.NUMBER_INT)
        Instant occurredOn,

        UUID matchId,
        UUID buyOrderId,
        UUID sellOrderId,
        UUID buyerUserId,
        UUID sellerUserId,
        UUID buyerWalletId,
        UUID sellerWalletId,
        BigDecimal matchPrice,
        BigDecimal matchAmount

) implements DomainEvent {

    /** Factory method com geração automática de matchId, eventId e occurredOn. */
    public static MatchExecutedEvent of(UUID correlationId,
                                        UUID buyOrderId, UUID sellOrderId,
                                        UUID buyerUserId, UUID sellerUserId,
                                        UUID buyerWalletId, UUID sellerWalletId,
                                        BigDecimal matchPrice, BigDecimal matchAmount) {
        UUID matchId = UUID.randomUUID();
        return new MatchExecutedEvent(
                UUID.randomUUID(),
                correlationId,
                matchId.toString(),
                Instant.now(),
                matchId,
                buyOrderId,
                sellOrderId,
                buyerUserId,
                sellerUserId,
                buyerWalletId,
                sellerWalletId,
                matchPrice,
                matchAmount
        );
    }
}
