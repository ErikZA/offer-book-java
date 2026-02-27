package com.vibranium.contracts.commands.order;

import com.vibranium.contracts.commands.Command;
import com.vibranium.contracts.enums.OrderType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Comando de entrada da Saga: representa a intenção de um usuário de
 * comprar ou vender Vibranium no livro de ofertas.
 *
 * <p>Recebido pelo {@code order-service} via API REST (POST /orders),
 * validado e publicado como {@link com.vibranium.contracts.events.order.OrderReceivedEvent}
 * no RabbitMQ para iniciar a coreografia da Saga.</p>
 *
 * @param correlationId  Gerado na entrada da API; identifica a Saga completa.
 * @param orderId        UUID gerado pela API para esta ordem.
 * @param userId         UUID do usuário autenticado (vem do JWT Keycloak).
 * @param walletId       Carteira do usuário — obtida via query antes de emitir.
 * @param orderType      BUY ou SELL.
 * @param price          Preço limite em BRL desejado pelo usuário.
 * @param amount         Quantidade de VIBRANIUM desejada.
 */
public record CreateOrderCommand(

        @NotNull UUID correlationId,
        @NotNull UUID orderId,
        @NotNull UUID userId,
        @NotNull UUID walletId,
        @NotNull OrderType orderType,

        @NotNull
        @DecimalMin(value = "0.0", inclusive = false, message = "Price must be positive")
        BigDecimal price,

        @NotNull
        @DecimalMin(value = "0.0", inclusive = false, message = "Amount must be positive")
        BigDecimal amount

) implements Command {}
