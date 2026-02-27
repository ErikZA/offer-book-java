package com.vibranium.orderservice.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Entidade de Ordem persisted em MongoDB
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "orders")
public class Order {

    @Id
    private String id;

    private String userId;
    private String symbol; // EUR/USD
    private String side; // BUY, SELL
    private BigDecimal quantity;
    private BigDecimal price;
    private String orderType; // LIMIT, MARKET
    private String status; // PENDING, PARTIALLY_FILLED, FILLED, CANCELLED
    private BigDecimal filledQuantity;
    private Instant createdAt;
    private Instant updatedAt;
    private String correlationId; // Para rastreamento

}
