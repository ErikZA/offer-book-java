package com.vibranium.orderservice.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declaração de toda a topologia do RabbitMQ do order-service.
 *
 * <p>Exchanges declaradas:</p>
 * <ul>
 *   <li>{@code vibranium.commands} — direct exchange para comandos entre serviços.</li>
 *   <li>{@code vibranium.events}   — topic exchange para eventos de domínio.</li>
 *   <li>{@code vibranium.dlq}      — direct exchange para dead-letter queue.</li>
 * </ul>
 *
 * <p>Todas as filas são {@code durable=true}: sobrevivem a restart do broker.</p>
 * <p>Filas principais têm DLX configurado para {@code vibranium.dlq} para
 * capturar mensagens rejeitadas após o número máximo de tentativas.</p>
 */
@Configuration
public class RabbitMQConfig {

    // -------------------------------------------------------------------------
    // Nomes das exchanges e filas (espelham application.yaml para consistência)
    // -------------------------------------------------------------------------

    /** Exchange de comandos (direct) — order-service → wallet-service. */
    public static final String COMMANDS_EXCHANGE     = "vibranium.commands";

    /** Exchange de eventos de domínio (topic) — compartilhada por todos os serviços. */
    public static final String EVENTS_EXCHANGE       = "vibranium.events";

    /** Exchange para mensagens na Dead Letter Queue. */
    public static final String DLQ_EXCHANGE          = "vibranium.dlq";

    /** Exchange built-in do RabbitMQ para eventos do Keycloak (plugin aznamier). */
    public static final String KEYCLOAK_EXCHANGE     = "amq.topic";

    // Filas consumidas pelo order-service
    public static final String QUEUE_FUNDS_RESERVED  = "order.events.funds-reserved";
    public static final String QUEUE_FUNDS_FAILED    = "order.events.funds-failed";
    public static final String QUEUE_KEYCLOAK_REG    = "order.keycloak.user-register";
    public static final String QUEUE_DEAD_LETTER     = "order.dead-letter";

    // Filas publicadas pelo order-service (declaradas para que o broker as crie)
    public static final String QUEUE_RESERVE_FUNDS   = "wallet.commands.reserve-funds";

    // Routing keys usadas pelos consumidores
    public static final String RK_FUNDS_RESERVED     = "wallet.events.funds-reserved";
    public static final String RK_FUNDS_FAILED       = "wallet.events.funds-reservation-failed";
    public static final String RK_KEYCLOAK_REGISTER  = "KK.EVENT.CLIENT.orderbook-realm.REGISTER";

    // -------------------------------------------------------------------------
    // Exchanges
    // -------------------------------------------------------------------------

    @Bean
    DirectExchange commandsExchange() {
        return new DirectExchange(COMMANDS_EXCHANGE, true, false);
    }

    @Bean
    TopicExchange eventsExchange() {
        return new TopicExchange(EVENTS_EXCHANGE, true, false);
    }

    @Bean
    DirectExchange dlqExchange() {
        return new DirectExchange(DLQ_EXCHANGE, true, false);
    }

    // -------------------------------------------------------------------------
    // Dead Letter Queue
    // -------------------------------------------------------------------------

    @Bean
    Queue orderDeadLetterQueue() {
        // DLQ sem TTL — mensagens ficam aqui até intervenção manual
        return QueueBuilder.durable(QUEUE_DEAD_LETTER).build();
    }

    // -------------------------------------------------------------------------
    // Fila de FundsReservedEvent (wallet → order: fundos bloqueados)
    // -------------------------------------------------------------------------

    @Bean
    Queue fundsReservedQueue() {
        return QueueBuilder.durable(QUEUE_FUNDS_RESERVED)
                // Mensagens rejeitadas vão para a DLQ
                .withArgument("x-dead-letter-exchange", DLQ_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", QUEUE_DEAD_LETTER)
                .build();
    }

    @Bean
    Binding fundsReservedBinding(Queue fundsReservedQueue, TopicExchange eventsExchange) {
        return BindingBuilder
                .bind(fundsReservedQueue)
                .to(eventsExchange)
                .with(RK_FUNDS_RESERVED);
    }

    // -------------------------------------------------------------------------
    // Fila de FundsReservationFailedEvent (wallet → order: reserva falhou)
    // -------------------------------------------------------------------------

    @Bean
    Queue fundsFailedQueue() {
        return QueueBuilder.durable(QUEUE_FUNDS_FAILED)
                .withArgument("x-dead-letter-exchange", DLQ_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", QUEUE_DEAD_LETTER)
                .build();
    }

    @Bean
    Binding fundsFailedBinding(Queue fundsFailedQueue, TopicExchange eventsExchange) {
        return BindingBuilder
                .bind(fundsFailedQueue)
                .to(eventsExchange)
                .with(RK_FUNDS_FAILED);
    }

    // -------------------------------------------------------------------------
    // Fila de eventos Keycloak (plugin aznamier → amq.topic)
    // -------------------------------------------------------------------------

    @Bean
    Queue keycloakRegisterQueue() {
        return QueueBuilder.durable(QUEUE_KEYCLOAK_REG)
                .withArgument("x-dead-letter-exchange", DLQ_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", QUEUE_DEAD_LETTER)
                .build();
    }

    /**
     * Bind para o amq.topic built-in do RabbitMQ.
     * O roteamento usa os routing keys do plugin aznamier:
     * {@code KK.EVENT.CLIENT.orderbook-realm.REGISTER}.
     */
    @Bean
    Binding keycloakRegisterBinding(Queue keycloakRegisterQueue) {
        return BindingBuilder
                .bind(keycloakRegisterQueue)
                .to(new TopicExchange(KEYCLOAK_EXCHANGE))
                .with(RK_KEYCLOAK_REGISTER);
    }

    // -------------------------------------------------------------------------
    // Fila de ReserveFundsCommand (declarada para garantir criação no broker)
    // -------------------------------------------------------------------------

    @Bean
    Queue reserveFundsQueue() {
        return QueueBuilder.durable(QUEUE_RESERVE_FUNDS).build();
    }

    @Bean
    Binding reserveFundsBinding(Queue reserveFundsQueue, DirectExchange commandsExchange) {
        return BindingBuilder
                .bind(reserveFundsQueue)
                .to(commandsExchange)
                .with(QUEUE_RESERVE_FUNDS);
    }

    // -------------------------------------------------------------------------
    // MessageConverter — JSON via Jackson (suporta records e DomainEvent)
    // -------------------------------------------------------------------------

    /**
     * Converte mensagens AMQP para/de JSON usando o ObjectMapper da aplicação.
     *
     * <p>O ObjectMapper deve ter o JavaTimeModule registrado para serialização
     * correta de {@code Instant} como epoch-millis (ver {@link JacksonConfig}).</p>
     */
    @Bean
    MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * Configura o RabbitTemplate para usar o conversor JSON.
     *
     * @param connectionFactory Factory de conexão do Spring AMQP.
     * @return RabbitTemplate pronto para publicar objetos Java como JSON.
     */
    @Bean
    RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());
        return template;
    }
}
