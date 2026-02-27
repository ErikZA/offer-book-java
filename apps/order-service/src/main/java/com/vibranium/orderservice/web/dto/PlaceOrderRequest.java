package com.vibranium.orderservice.web.dto;

import com.vibranium.contracts.enums.OrderType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * DTO de entrada para o endpoint {@code POST /api/v1/orders}.
 *
 * <p>Validado via Bean Validation (JSR-380) antes de chegar ao serviço.
 * O {@code userId} NÃO é informado aqui — é extraído do claim {@code sub}
 * do JWT para evitar spoofing.</p>
 *
 * @param walletId  UUID da carteira do usuário que será debitada/bloqueada.
 * @param orderType BUY ou SELL.
 * @param price     Preço limite em BRL. Deve ser maior que zero.
 * @param amount    Quantidade de VIBRANIUM desejada. Deve ser maior que zero.
 */
public record PlaceOrderRequest(

        @NotNull(message = "walletId é obrigatório")
        UUID walletId,

        @NotNull(message = "orderType é obrigatório (BUY ou SELL)")
        OrderType orderType,

        @NotNull(message = "price é obrigatório")
        @DecimalMin(value = "0.00000001", message = "price deve ser maior que zero")
        BigDecimal price,

        @NotNull(message = "amount é obrigatório")
        @DecimalMin(value = "0.00000001", message = "amount deve ser maior que zero")
        BigDecimal amount

) {}
