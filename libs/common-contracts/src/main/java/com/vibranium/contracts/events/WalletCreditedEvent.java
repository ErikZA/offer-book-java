package com.vibranium.contracts.events;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Evento disparado quando uma carteira recebe crédito (venda bem-sucedida ou devolução).
 * Topic RabbitMQ: wallet.credited
 * Idempotente: mesmo evento_id não duplica o crédito.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WalletCreditedEvent extends BaseEvent {

    private static final long serialVersionUID = 1L;

    @JsonProperty("wallet_id")
    private String walletId;

    @JsonProperty("user_id")
    private String userId;

    @JsonProperty("currency")
    private String currency; // "EUR", "USD"

    @JsonProperty("amount")
    private BigDecimal amount;

    @JsonProperty("reason")
    private String reason; // "SELL_MATCH", "REFUND"

    @JsonProperty("reference_id")
    private String referenceId; // ID da ordem ou trade

    @JsonProperty("balance_before")
    private BigDecimal balanceBefore;

    @JsonProperty("balance_after")
    private BigDecimal balanceAfter;

}
