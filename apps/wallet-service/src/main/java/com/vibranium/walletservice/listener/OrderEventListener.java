package com.vibranium.walletservice.listener;

import com.vibranium.contracts.events.OrderCreatedEvent;
import com.vibranium.contracts.events.OrderMatchedEvent;
import com.vibranium.walletservice.service.WalletService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Binding;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Listener de eventos RabbitMQ para processar eventos de ordens
 * Implementa idempotência consumindo eventos com correlation_id
 */
@Slf4j
@Component
public class OrderEventListener {

    private final WalletService walletService;

    @Autowired
    public OrderEventListener(WalletService walletService) {
        this.walletService = walletService;
    }

    /**
     * Listener para evento OrderCreatedEvent
     * Tópico: vibranium.orders / Rota: orders.created
     * Debita a carteira do comprador ao criar ordem de compra
     */
    @RabbitListener(
            bindings = @Binding(
                    exchange = @Exchange(name = "vibranium.orders", type = "topic", durable = true),
                    queue = @Queue(name = "wallet.order-created", durable = true),
                    key = "orders.created"
            ),
            ackMode = org.springframework.amqp.core.AcknowledgeMode.MANUAL
    )
    public void handleOrderCreated(OrderCreatedEvent event) {
        log.info("Recebido evento OrderCreatedEvent: {} para {}", event.getOrderId(), event.getUserId());

        try {
            // Se for BUY, debita a carteira (reserva o saldo)
            if ("BUY".equals(event.getSide())) {
                // Assumindo que o símbolo é "EUR/USD" e queremos debitar em EUR
                String currency = event.getSymbol().split("/")[0];
                walletService.debitWallet(
                        event.getEventId(),
                        event.getUserId(),
                        currency,
                        event.getTotalAmount(),
                        "ORDER_PLACEMENT",
                        event.getOrderId()
                );
                log.info("Saldo debitado para ordem de compra: {}", event.getOrderId());
            }
        } catch (Exception e) {
            log.error("Erro ao processar OrderCreatedEvent: {}", event.getOrderId(), e);
            throw new RuntimeException("Falha ao processar evento", e);
        }
    }

    /**
     * Listener para evento OrderMatchedEvent
     * Tópico: vibranium.orders / Rota: orders.matched
     * Transfere fundos entre carteiras ao fazer matching
     */
    @RabbitListener(
            bindings = @Binding(
                    exchange = @Exchange(name = "vibranium.orders", type = "topic", durable = true),
                    queue = @Queue(name = "wallet.order-matched", durable = true),
                    key = "orders.matched"
            ),
            ackMode = org.springframework.amqp.core.AcknowledgeMode.MANUAL
    )
    public void handleOrderMatched(OrderMatchedEvent event) {
        log.info("Recebido evento OrderMatchedEvent: {} (buy: {}, sell: {})", 
                event.getTradeId(), event.getBuyOrderId(), event.getSellOrderId());

        try {
            String currency = event.getSymbol().split("/")[0];
            String receiveCurrency = event.getSymbol().split("/")[1];

            // Vendedor recebe em moeda de recebimento
            walletService.creditWallet(
                    event.getEventId() + "_seller_credit",
                    event.getSellerId(),
                    receiveCurrency,
                    event.getMatchedPrice().multiply(event.getMatchedQuantity()),
                    "SELL_MATCH",
                    event.getTradeId()
            );

            // Comprador debita em moeda de pagamento pela quantidade recebida
            // (já debitou ao criar a ordem, agora ajusta só a diferença se necessário)
            log.info("Transações de match completadas para trade: {}", event.getTradeId());
        } catch (Exception e) {
            log.error("Erro ao processar OrderMatchedEvent: {}", event.getTradeId(), e);
            throw new RuntimeException("Falha ao processar evento", e);
        }
    }

}
