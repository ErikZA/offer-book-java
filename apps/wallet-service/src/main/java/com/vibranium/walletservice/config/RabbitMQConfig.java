package com.vibranium.walletservice.config;

import com.vibranium.utils.messaging.BaseRabbitMQConfig;
import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuração das exchanges, filas e bindings do RabbitMQ para o wallet-service.
 * Estende {@link BaseRabbitMQConfig} para herdar conversão JSON e rastreabilidade.
 */
@Configuration
public class RabbitMQConfig extends BaseRabbitMQConfig {

    // -------------------------------------------------------------------------
    // Dead Letter Exchange (DLX) central
    // -------------------------------------------------------------------------
    @Bean
    public DirectExchange vibraniumDlqExchange() {
        return new DirectExchange(VIBRANIUM_DLQ_EXCHANGE, true, false);
    }

    // -------------------------------------------------------------------------
    // Keycloak Events (Topic)
    // -------------------------------------------------------------------------
    public static final String QUEUE_KEYCLOAK_EVENTS = "wallet.keycloak.events";
    public static final String QUEUE_KEYCLOAK_EVENTS_DLQ = "wallet.keycloak.events.dlq";
    public static final String RK_KEYCLOAK_REGISTER_SUCCESS = "KK.EVENT.*.*.SUCCESS.#";

    @Bean
    public TopicExchange keycloakEventsExchange() {
        return new TopicExchange(AMQ_TOPIC_EXCHANGE, true, false);
    }

    @Bean
    public Queue walletKeycloakEventsQueue() {
        return QueueBuilder.durable(QUEUE_KEYCLOAK_EVENTS)
                .withArgument("x-dead-letter-exchange", VIBRANIUM_DLQ_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", QUEUE_KEYCLOAK_EVENTS_DLQ)
                .build();
    }

    @Bean
    public Binding keycloakEventsBinding(
            @Qualifier("walletKeycloakEventsQueue") Queue walletKeycloakEventsQueue,
            @Qualifier("keycloakEventsExchange")    TopicExchange keycloakEventsExchange) {
        return BindingBuilder.bind(walletKeycloakEventsQueue).to(keycloakEventsExchange).with(RK_KEYCLOAK_REGISTER_SUCCESS);
    }

    @Bean
    public Queue walletKeycloakEventsDlQueue() {
        return QueueBuilder.durable(QUEUE_KEYCLOAK_EVENTS_DLQ).build();
    }

    @Bean
    public Binding walletKeycloakEventsDlqBinding(
            @Qualifier("walletKeycloakEventsDlQueue") Queue walletKeycloakEventsDlQueue,
            @Qualifier("vibraniumDlqExchange")                 DirectExchange vibraniumDlqExchange) {
        return BindingBuilder.bind(walletKeycloakEventsDlQueue).to(vibraniumDlqExchange).with(QUEUE_KEYCLOAK_EVENTS_DLQ);
    }

    // -------------------------------------------------------------------------
    // Saga: Reserve Funds (Direct via vibranium.commands)
    // -------------------------------------------------------------------------
    public static final String QUEUE_RESERVE_FUNDS = "wallet.commands.reserve-funds";
    public static final String QUEUE_RESERVE_FUNDS_DLQ = "wallet.commands.reserve-funds.dlq";

    @Bean
    public DirectExchange vibraniumCommandsExchange() {
        return new DirectExchange(VIBRANIUM_COMMANDS_EXCHANGE, true, false);
    }

    @Bean
    public Queue reserveFundsQueue() {
        return QueueBuilder.durable(QUEUE_RESERVE_FUNDS)
                .withArgument("x-dead-letter-exchange", VIBRANIUM_DLQ_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", QUEUE_RESERVE_FUNDS_DLQ)
                .build();
    }

    @Bean
    public Binding reserveFundsQueueBinding(
            @Qualifier("reserveFundsQueue")         Queue reserveFundsQueue,
            @Qualifier("vibraniumCommandsExchange") DirectExchange vibraniumCommandsExchange) {
        return BindingBuilder.bind(reserveFundsQueue).to(vibraniumCommandsExchange).with(QUEUE_RESERVE_FUNDS);
    }

    @Bean
    public Queue reserveFundsDlQueue() {
        return QueueBuilder.durable(QUEUE_RESERVE_FUNDS_DLQ).build();
    }

    @Bean
    public Binding reserveFundsDlqBinding(
            @Qualifier("reserveFundsDlQueue") Queue reserveFundsDlQueue,
            @Qualifier("vibraniumDlqExchange")         DirectExchange vibraniumDlqExchange) {
        return BindingBuilder.bind(reserveFundsDlQueue).to(vibraniumDlqExchange).with(QUEUE_RESERVE_FUNDS_DLQ);
    }

    // -------------------------------------------------------------------------
    // Saga: Release Funds (Direct via vibranium.commands)
    // -------------------------------------------------------------------------
    public static final String QUEUE_RELEASE_FUNDS = "wallet.commands.release-funds";
    public static final String QUEUE_RELEASE_FUNDS_DLQ = "wallet.commands.release-funds.dlq";

    @Bean
    public Queue releaseFundsQueue() {
        return QueueBuilder.durable(QUEUE_RELEASE_FUNDS)
                .withArgument("x-dead-letter-exchange", VIBRANIUM_DLQ_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", QUEUE_RELEASE_FUNDS_DLQ)
                .build();
    }

    @Bean
    public Binding releaseFundsQueueBinding(
            @Qualifier("releaseFundsQueue")         Queue releaseFundsQueue,
            @Qualifier("vibraniumCommandsExchange") DirectExchange vibraniumCommandsExchange) {
        return BindingBuilder.bind(releaseFundsQueue).to(vibraniumCommandsExchange).with(QUEUE_RELEASE_FUNDS);
    }

    @Bean
    public Queue releaseFundsDlQueue() {
        return QueueBuilder.durable(QUEUE_RELEASE_FUNDS_DLQ).build();
    }

    @Bean
    public Binding releaseFundsDlqBinding(
            @Qualifier("releaseFundsDlQueue") Queue releaseFundsDlQueue,
            @Qualifier("vibraniumDlqExchange")         DirectExchange vibraniumDlqExchange) {
        return BindingBuilder.bind(releaseFundsDlQueue).to(vibraniumDlqExchange).with(QUEUE_RELEASE_FUNDS_DLQ);
    }

    // -------------------------------------------------------------------------
    // Settle Funds & Retry Mechanism (Topic)
    // -------------------------------------------------------------------------
    public static final String QUEUE_SETTLE_RETRY = "wallet.commands.settle-retry";
    public static final String QUEUE_WALLET_COMMANDS_DLQ = "wallet.commands.dlq";
    public static final long SETTLE_RETRY_TTL_MS = 3000;

    @Bean
    public TopicExchange walletCommandsExchange() {
        return new TopicExchange(WALLET_COMMANDS_EXCHANGE, true, false);
    }

    @Bean
    public Queue walletCommandsQueue() {
        return QueueBuilder.durable(WALLET_COMMANDS_EXCHANGE)
                .withArgument("x-dead-letter-exchange", VIBRANIUM_DLQ_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", QUEUE_WALLET_COMMANDS_DLQ)
                .build();
    }

    @Bean
    public Binding walletCommandsBinding(
            @Qualifier("walletCommandsQueue")    Queue walletCommandsQueue,
            @Qualifier("walletCommandsExchange") TopicExchange walletCommandsExchange) {
        return BindingBuilder.bind(walletCommandsQueue).to(walletCommandsExchange).with("wallet.command.settle-funds");
    }

    @Bean
    public Queue walletCommandsDlqQueue() {
        return QueueBuilder.durable(QUEUE_WALLET_COMMANDS_DLQ).build();
    }

    @Bean
    public Binding walletCommandsDlqBinding(
            @Qualifier("walletCommandsDlqQueue") Queue walletCommandsDlqQueue,
            @Qualifier("vibraniumDlqExchange")            DirectExchange vibraniumDlqExchange) {
        return BindingBuilder.bind(walletCommandsDlqQueue).to(vibraniumDlqExchange).with(QUEUE_WALLET_COMMANDS_DLQ);
    }

    @Bean
    public Queue settleRetryQueue() {
        return QueueBuilder.durable(QUEUE_SETTLE_RETRY)
                .withArgument("x-dead-letter-exchange", WALLET_COMMANDS_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", "wallet.command.settle-funds")
                .withArgument("x-message-ttl", SETTLE_RETRY_TTL_MS)
                .build();
    }

    @Bean
    public Binding settleRetryBinding(
            @Qualifier("settleRetryQueue") Queue settleRetryQueue,
            @Qualifier("vibraniumDlqExchange")      DirectExchange vibraniumDlqExchange) {
        return BindingBuilder.bind(settleRetryQueue).to(vibraniumDlqExchange).with(QUEUE_SETTLE_RETRY);
    }

    // -------------------------------------------------------------------------
    // Wallet Events (Topic)
    // -------------------------------------------------------------------------
    @Bean
    public TopicExchange vibraniumEventsExchange() {
        return new TopicExchange(VIBRANIUM_EVENTS_EXCHANGE, true, false);
    }
}
