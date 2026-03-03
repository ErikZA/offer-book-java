package com.vibranium.walletservice.infrastructure.outbox;

/**
 * Define o roteamento dinâmico de cada tipo de evento do wallet para o RabbitMQ.
 *
 * <p>O {@link com.vibranium.walletservice.infrastructure.outbox.OutboxPublisherService}
 * usa {@link #fromEventType(String)} para obter a exchange e a routing-key corretas
 * a partir da coluna {@code event_type} da tabela {@code outbox_message}, evitando
 * qualquer lógica de string espalhada no publisher.</p>
 *
 * <p>Todos os eventos de domínio do wallet usam a exchange {@code vibranium.events}
 * (topic exchange), roteados por routing-keys no formato
 * {@code wallet.events.<nome-kebab-case>}.</p>
 *
 * <p>Para adicionar um novo evento, basta inserir uma nova constante e registrar
 * o mapeamento no {@code switch} de {@link #fromEventType(String)}.</p>
 */
public enum EventRoute {

    /** Evento emitido quando fundos são reservados com sucesso. */
    FUNDS_RESERVED("vibranium.events", "wallet.events.funds-reserved"),

    /** Evento emitido quando a reserva de fundos falha (saldo insuficiente, etc.). */
    FUNDS_FAILED("vibranium.events",   "wallet.events.funds-reservation-failed"),

    /** Evento emitido após liquidação bem-sucedida do trade. */
    FUNDS_SETTLED("vibranium.events",  "wallet.events.funds-settled"),

    /** Evento emitido quando uma nova carteira é criada no sistema. */
    WALLET_CREATED("vibranium.events", "wallet.events.wallet-created"),

    /** Evento emitido quando fundos bloqueados são liberados com sucesso (compensação Saga). */
    FUNDS_RELEASED("vibranium.events",       "wallet.events.funds-released"),

    /** Evento emitido quando a liberação de fundos falha (incidente crítico). */
    FUNDS_RELEASE_FAILED("vibranium.events", "wallet.events.funds-release-failed");

    // -------------------------------------------------------------------------
    // Estado imutável
    // -------------------------------------------------------------------------

    /** Nome da exchange RabbitMQ de destino. */
    private final String exchange;

    /** Routing-key usada para rotear a mensagem dentro da exchange. */
    private final String routingKey;

    EventRoute(String exchange, String routingKey) {
        this.exchange   = exchange;
        this.routingKey = routingKey;
    }

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    /**
     * Resolve o {@code EventRoute} correspondente ao nome simples do evento.
     *
     * <p>O valor esperado é o nome da classe do evento (ex: {@code "FundsReservedEvent"}),
     * conforme gravado na coluna {@code event_type} do Outbox pelo
     * {@link com.vibranium.walletservice.application.service.WalletService}.</p>
     *
     * <p>O {@code switch} em vez de {@code Enum.valueOf} permite total
     * desacoplamento entre o nome do enum e o nome do evento de domínio,
     * facilitando refatorações sem quebrar compatibilidade com dados históricos.</p>
     *
     * @param eventType Nome simples do evento (case-sensitive).
     * @return {@link EventRoute} correspondente.
     * @throws IllegalArgumentException se o {@code eventType} não for reconhecido.
     */
    public static EventRoute fromEventType(String eventType) {
        return switch (eventType) {
            case "FundsReservedEvent"          -> FUNDS_RESERVED;
            case "FundsReservationFailedEvent" -> FUNDS_FAILED;
            case "FundsSettledEvent"           -> FUNDS_SETTLED;
            case "WalletCreatedEvent"          -> WALLET_CREATED;
            case "FundsReleasedEvent"           -> FUNDS_RELEASED;
            case "FundsReleaseFailedEvent"      -> FUNDS_RELEASE_FAILED;
            default -> throw new IllegalArgumentException(
                    "Unknown event type: " + eventType);
        };
    }

    // -------------------------------------------------------------------------
    // Getters
    // -------------------------------------------------------------------------

    /** @return Nome da exchange RabbitMQ de destino. */
    public String getExchange() {
        return exchange;
    }

    /** @return Routing-key para roteamento dentro da exchange. */
    public String getRoutingKey() {
        return routingKey;
    }
}
