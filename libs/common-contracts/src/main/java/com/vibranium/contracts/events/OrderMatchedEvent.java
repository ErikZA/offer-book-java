package com.vibranium.contracts.events;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Evento disparado quando duas ordens sofrem matching no order book.
 * Topic RabbitMQ: orders.matched
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderMatchedEvent extends BaseEvent {

    private static final long serialVersionUID = 1L;

    @JsonProperty("buy_order_id")
    private String buyOrderId;

    @JsonProperty("sell_order_id")
    private String sellOrderId;

    @JsonProperty("buyer_id")
    private String buyerId;

    @JsonProperty("seller_id")
    private String sellerId;

    @JsonProperty("symbol")
    private String symbol;

    @JsonProperty("matched_quantity")
    private BigDecimal matchedQuantity;

    @JsonProperty("matched_price")
    private BigDecimal matchedPrice;

    @JsonProperty("trade_id")
    private String tradeId;

    @JsonProperty("match_timestamp")
    private String matchTimestamp;

}
