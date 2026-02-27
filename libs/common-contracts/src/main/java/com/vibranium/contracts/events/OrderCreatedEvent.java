package com.vibranium.contracts.events;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Evento disparado quando uma nova ordem é criada.
 * Topic RabbitMQ: orders.created
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderCreatedEvent extends BaseEvent {

    private static final long serialVersionUID = 1L;

    @JsonProperty("order_id")
    private String orderId;

    @JsonProperty("user_id")
    private String userId;

    @JsonProperty("symbol")
    private String symbol; // Ex: "EUR/USD"

    @JsonProperty("side")
    private String side; // "BUY" ou "SELL"

    @JsonProperty("quantity")
    private BigDecimal quantity;

    @JsonProperty("price")
    private BigDecimal price;

    @JsonProperty("order_type")
    private String orderType; // "LIMIT", "MARKET"

    @JsonProperty("total_amount")
    private BigDecimal totalAmount; // quantity * price

}
