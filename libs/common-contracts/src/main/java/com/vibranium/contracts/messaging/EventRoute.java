package com.vibranium.contracts.messaging;

/**
 * Contrato comum de roteamento dos eventos de domínio publicados no RabbitMQ.
 *
 * <p>Centraliza a relação entre {@code event_type} (nome simples da classe do evento)
 * e o par {@code exchange + routing-key}, evitando literais espalhados entre
 * os serviços.</p>
 */
public enum EventRoute {

    // -------------------------------------------------------------------------
    // Contexto: wallet-service
    // -------------------------------------------------------------------------

    /** Evento emitido quando a reserva de fundos conclui com sucesso. */
    FUNDS_RESERVED("vibranium.events", "wallet.events.funds-reserved"),

    /** Evento emitido quando a reserva de fundos falha. */
    FUNDS_RESERVATION_FAILED("vibranium.events", "wallet.events.funds-reservation-failed"),

    /** Evento emitido quando a liquidação de fundos conclui com sucesso. */
    FUNDS_SETTLED("vibranium.events", "wallet.events.funds-settled"),

    /** Evento emitido quando uma nova carteira é criada. */
    WALLET_CREATED("vibranium.events", "wallet.events.wallet-created"),

    /** Evento emitido quando os fundos bloqueados são liberados. */
    FUNDS_RELEASED("vibranium.events", "wallet.events.funds-released"),

    /** Evento emitido quando a liberação de fundos falha. */
    FUNDS_RELEASE_FAILED("vibranium.events", "wallet.events.funds-release-failed"),

    /** Evento emitido quando a liquidação de fundos falha. */
    FUNDS_SETTLEMENT_FAILED("vibranium.events", "wallet.events.funds-settlement-failed"),

    // -------------------------------------------------------------------------
    // Contexto: order-service
    // -------------------------------------------------------------------------

    /** Evento emitido quando a ordem é recebida no sistema. */
    ORDER_RECEIVED("vibranium.events", "order.events.order-received"),

    /** Evento emitido quando a ordem é adicionada ao livro. */
    ORDER_ADDED_TO_BOOK("vibranium.events", "order.events.order-added-to-book"),

    /** Evento emitido quando uma execução de match ocorre. */
    MATCH_EXECUTED("vibranium.events", "order.events.match-executed"),

    /** Evento emitido quando a ordem é totalmente preenchida. */
    ORDER_FILLED("vibranium.events", "order.events.order-filled"),

    /** Evento emitido quando a ordem é parcialmente preenchida. */
    ORDER_PARTIALLY_FILLED("vibranium.events", "order.events.order-partially-filled"),

    /** Evento emitido quando a ordem é cancelada. */
    ORDER_CANCELLED("vibranium.events", "order.events.order-cancelled");

    private final String exchange;
    private final String routingKey;

    EventRoute(String exchange, String routingKey) {
        this.exchange = exchange;
        this.routingKey = routingKey;
    }

    /**
     * Resolve a rota do RabbitMQ pelo nome simples da classe do evento.
     *
     * @param eventType Nome simples do evento (ex.: {@code FundsReservedEvent}).
     * @return rota correspondente.
     * @throws IllegalArgumentException quando o tipo não possui mapeamento.
     */
    public static EventRoute fromEventType(String eventType) {
        return switch (eventType) {
            // wallet-service
            case "FundsReservedEvent" -> FUNDS_RESERVED;
            case "FundsReservationFailedEvent" -> FUNDS_RESERVATION_FAILED;
            case "FundsSettledEvent" -> FUNDS_SETTLED;
            case "WalletCreatedEvent" -> WALLET_CREATED;
            case "FundsReleasedEvent" -> FUNDS_RELEASED;
            case "FundsReleaseFailedEvent" -> FUNDS_RELEASE_FAILED;
            case "FundsSettlementFailedEvent" -> FUNDS_SETTLEMENT_FAILED;

            // order-service
            case "OrderReceivedEvent" -> ORDER_RECEIVED;
            case "OrderAddedToBookEvent" -> ORDER_ADDED_TO_BOOK;
            case "MatchExecutedEvent" -> MATCH_EXECUTED;
            case "OrderFilledEvent" -> ORDER_FILLED;
            case "OrderPartiallyFilledEvent" -> ORDER_PARTIALLY_FILLED;
            case "OrderCancelledEvent" -> ORDER_CANCELLED;

            default -> throw new IllegalArgumentException("Unknown event type: " + eventType);
        };
    }

    public String getExchange() {
        return exchange;
    }

    public String getRoutingKey() {
        return routingKey;
    }
}
