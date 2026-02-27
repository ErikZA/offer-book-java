package com.vibranium.orderservice.command;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * Comando para criar uma nova ordem (CQRS)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateOrderCommand {

    @NotBlank(message = "userId é obrigatório")
    private String userId;

    @NotBlank(message = "symbol é obrigatório")
    private String symbol;

    @NotBlank(message = "side (BUY/SELL) é obrigatório")
    private String side;

    @NotNull(message = "quantity não pode ser nula")
    @Positive(message = "quantity deve ser positiva")
    private BigDecimal quantity;

    @NotNull(message = "price não pode ser nula")
    @Positive(message = "price deve ser positiva")
    private BigDecimal price;

    @NotBlank(message = "orderType é obrigatório")
    private String orderType;

    private String correlationId;

}
