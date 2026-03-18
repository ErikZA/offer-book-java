package com.vibranium.orderservice.config;

import com.vibranium.utils.messaging.BaseRabbitMQConfig;
import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declaração de toda a topologia do RabbitMQ do order-service.
 * Estende {@link BaseRabbitMQConfig} para herdar conversão JSON e rastreabilidade.
 */
@Configuration
public class RabbitMQConfig extends BaseRabbitMQConfig {
    
    // -------------------------------------------------------------------------
    // Order Publishing Routing Keys
    // -------------------------------------------------------------------------
    public static final String RK_ORDER_ADDED_TO_BOOK = "order.events.order-added-to-book";
    public static final String RK_ORDER_FILLED = "order.events.order-filled";
    public static final String RK_ORDER_PARTIALLY_FILLED = "order.events.order-partially-filled";
    public static final String RK_SETTLE_FUNDS = "wallet.command.settle-funds";

    // -------------------------------------------------------------------------
    // External Services (e.g. Wallet) queues used for publishing
    // -------------------------------------------------------------------------
    public static final String QUEUE_RESERVE_FUNDS = "wallet.commands.reserve-funds";
    public static final String QUEUE_RELEASE_FUNDS = "wallet.commands.release-funds";

    // -------------------------------------------------------------------------
    // Global Topology & Exchanges
    // -------------------------------------------------------------------------

    public static final String COMMANDS_EXCHANGE     = VIBRANIUM_COMMANDS_EXCHANGE;
    public static final String EVENTS_EXCHANGE       = VIBRANIUM_EVENTS_EXCHANGE;
    public static final String DLQ_EXCHANGE          = VIBRANIUM_DLQ_EXCHANGE;
    public static final String KEYCLOAK_EXCHANGE     = AMQ_TOPIC_EXCHANGE;

    @Bean
    DirectExchange vibraniumDlqExchange() {
        return new DirectExchange(VIBRANIUM_DLQ_EXCHANGE, true, false);
    }

    @Bean
    DirectExchange vibraniumCommandsExchange() {
        return new DirectExchange(VIBRANIUM_COMMANDS_EXCHANGE, true, false);
    }

    @Bean
    TopicExchange vibraniumEventsExchange() {
        return new TopicExchange(VIBRANIUM_EVENTS_EXCHANGE, true, false);
    }

    @Bean
    TopicExchange walletCommandsExchange() {
        return new TopicExchange(WALLET_COMMANDS_EXCHANGE, true, false);
    }

    @Bean
    TopicExchange keycloakEventsExchange() {
        return new TopicExchange(AMQ_TOPIC_EXCHANGE, true, false);
    }

    // -------------------------------------------------------------------------
    // Global Dead Letter Queue (Shared by most command/event queues)
    // -------------------------------------------------------------------------
    public static final String QUEUE_DEAD_LETTER = "order.dead-letter";

    @Bean
    Queue orderDeadLetterQueue() {
        return QueueBuilder.durable(QUEUE_DEAD_LETTER).build();
    }

    @Bean
    Binding deadLetterBinding(
            @Qualifier("orderDeadLetterQueue") Queue orderDeadLetterQueue,
            @Qualifier("vibraniumDlqExchange")          DirectExchange vibraniumDlqExchange) {
        return BindingBuilder.bind(orderDeadLetterQueue).to(vibraniumDlqExchange).with(QUEUE_DEAD_LETTER);
    }

    // -------------------------------------------------------------------------
    // Keycloak Events
    // -------------------------------------------------------------------------
    public static final String QUEUE_KEYCLOAK_EVENTS = "order.keycloak.events";
    public static final String QUEUE_KEYCLOAK_EVENTS_DLQ = "order.keycloak.events.dlq";
    public static final String RK_KEYCLOAK_REGISTER_SUCCESS = "KK.EVENT.*.*.SUCCESS.#";

    // Aliases for compatibility
    public static final String RK_KEYCLOAK_REGISTER = RK_KEYCLOAK_REGISTER_SUCCESS;
    public static final String QUEUE_KEYCLOAK_REG = QUEUE_KEYCLOAK_EVENTS;

    @Bean
    Queue orderKeycloakEventsQueue() {
        return QueueBuilder.durable(QUEUE_KEYCLOAK_EVENTS)
                .withArgument("x-dead-letter-exchange", VIBRANIUM_DLQ_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", QUEUE_KEYCLOAK_EVENTS_DLQ)
                .build();
    }

    @Bean
    Binding keycloakEventsBinding(
            @Qualifier("orderKeycloakEventsQueue") Queue orderKeycloakEventsQueue,
            @Qualifier("keycloakEventsExchange")   TopicExchange keycloakEventsExchange) {
        return BindingBuilder.bind(orderKeycloakEventsQueue).to(keycloakEventsExchange).with(RK_KEYCLOAK_REGISTER_SUCCESS);
    }

    @Bean
    Queue orderKeycloakEventsDlQueue() {
        return QueueBuilder.durable(QUEUE_KEYCLOAK_EVENTS_DLQ).build();
    }

    @Bean
    Binding orderKeycloakEventsDlqBinding(
            @Qualifier("orderKeycloakEventsDlQueue") Queue orderKeycloakEventsDlQueue,
            @Qualifier("vibraniumDlqExchange")                 DirectExchange vibraniumDlqExchange) {
        return BindingBuilder.bind(orderKeycloakEventsDlQueue).to(vibraniumDlqExchange).with(QUEUE_KEYCLOAK_EVENTS_DLQ);
    }

    // -------------------------------------------------------------------------
    // Funds Events (Wallet -> Order)
    // -------------------------------------------------------------------------
    public static final String RK_FUNDS_RESERVED = "wallet.events.funds-reserved";
    public static final String QUEUE_FUNDS_RESERVED = "order.events.funds-reserved";

    @Bean
    Queue fundsReservedQueue() {
        return QueueBuilder.durable(QUEUE_FUNDS_RESERVED)
                .withArgument("x-dead-letter-exchange", VIBRANIUM_DLQ_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", QUEUE_DEAD_LETTER)
                .build();
    }

    @Bean
    Binding fundsReservedBinding(
            @Qualifier("fundsReservedQueue") Queue fundsReservedQueue,
            @Qualifier("vibraniumEventsExchange")     TopicExchange vibraniumEventsExchange) {
        return BindingBuilder.bind(fundsReservedQueue).to(vibraniumEventsExchange).with(RK_FUNDS_RESERVED);
    }

    public static final String RK_FUNDS_FAILED = "wallet.events.funds-reservation-failed";
    public static final String QUEUE_FUNDS_FAILED = "order.events.funds-failed";

    @Bean
    Queue fundsFailedQueue() {
        return QueueBuilder.durable(QUEUE_FUNDS_FAILED)
                .withArgument("x-dead-letter-exchange", VIBRANIUM_DLQ_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", QUEUE_DEAD_LETTER)
                .build();
    }

    @Bean
    Binding fundsFailedBinding(
            @Qualifier("fundsFailedQueue") Queue fundsFailedQueue,
            @Qualifier("vibraniumEventsExchange")   TopicExchange vibraniumEventsExchange) {
        return BindingBuilder.bind(fundsFailedQueue).to(vibraniumEventsExchange).with(RK_FUNDS_FAILED);
    }

    public static final String RK_FUNDS_SETTLEMENT_FAILED = "wallet.events.funds-settlement-failed";
    public static final String QUEUE_FUNDS_SETTLEMENT_FAILED = "order.events.funds-settlement-failed";

    @Bean
    Queue fundsSettlementFailedQueue() {
        return QueueBuilder.durable(QUEUE_FUNDS_SETTLEMENT_FAILED)
                .withArgument("x-dead-letter-exchange", VIBRANIUM_DLQ_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", QUEUE_DEAD_LETTER)
                .build();
    }

    @Bean
    Binding fundsSettlementFailedBinding(
            @Qualifier("fundsSettlementFailedQueue") Queue fundsSettlementFailedQueue,
            @Qualifier("vibraniumEventsExchange")             TopicExchange vibraniumEventsExchange) {
        return BindingBuilder.bind(fundsSettlementFailedQueue).to(vibraniumEventsExchange).with(RK_FUNDS_SETTLEMENT_FAILED);
    }

    public static final String RK_FUNDS_RELEASE_FAILED = "wallet.events.funds-release-failed";
    public static final String QUEUE_FUNDS_RELEASE_FAILED = "order.events.funds-release-failed";
    public static final String QUEUE_FUNDS_RELEASE_FAILED_DLQ = "order.events.funds-release-failed.dlq";

    @Bean
    Queue fundsReleaseFailedQueue() {
        return QueueBuilder.durable(QUEUE_FUNDS_RELEASE_FAILED)
                .withArgument("x-dead-letter-exchange", VIBRANIUM_DLQ_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", QUEUE_FUNDS_RELEASE_FAILED_DLQ)
                .build();
    }

    @Bean
    Binding fundsReleaseFailedBinding(
            @Qualifier("fundsReleaseFailedQueue") Queue fundsReleaseFailedQueue,
            @Qualifier("vibraniumEventsExchange")          TopicExchange vibraniumEventsExchange) {
        return BindingBuilder.bind(fundsReleaseFailedQueue).to(vibraniumEventsExchange).with(RK_FUNDS_RELEASE_FAILED);
    }

    @Bean
    Queue fundsReleaseFailedDlq() {
        return QueueBuilder.durable(QUEUE_FUNDS_RELEASE_FAILED_DLQ).build();
    }

    @Bean
    Binding fundsReleaseFailedDlqBinding(
            @Qualifier("fundsReleaseFailedDlq") Queue fundsReleaseFailedDlq,
            @Qualifier("vibraniumDlqExchange")           DirectExchange vibraniumDlqExchange) {
        return BindingBuilder.bind(fundsReleaseFailedDlq).to(vibraniumDlqExchange).with(QUEUE_FUNDS_RELEASE_FAILED_DLQ);
    }

    // -------------------------------------------------------------------------
    // Order Projections (MongoDB - Read Model)
    // -------------------------------------------------------------------------

    // Projection: Order Received
    public static final String RK_ORDER_RECEIVED = "order.events.order-received";
    public static final String QUEUE_ORDER_PROJECTION_RECEIVED = "order.projection.received";
    public static final String QUEUE_ORDER_PROJECTION_RECEIVED_DLQ = "order.projection.received.dlq";

    @Bean
    Queue orderProjectionReceivedQueue() {
        return QueueBuilder.durable(QUEUE_ORDER_PROJECTION_RECEIVED)
                .withArgument("x-dead-letter-exchange", VIBRANIUM_DLQ_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", QUEUE_ORDER_PROJECTION_RECEIVED_DLQ)
                .build();
    }

    @Bean
    Binding orderProjectionReceivedBinding(
            @Qualifier("orderProjectionReceivedQueue") Queue orderProjectionReceivedQueue,
            @Qualifier("vibraniumEventsExchange")               TopicExchange vibraniumEventsExchange) {
        return BindingBuilder.bind(orderProjectionReceivedQueue).to(vibraniumEventsExchange).with(RK_ORDER_RECEIVED);
    }

    @Bean
    Queue orderProjectionReceivedDlq() {
        return QueueBuilder.durable(QUEUE_ORDER_PROJECTION_RECEIVED_DLQ).build();
    }

    @Bean
    Binding orderProjectionReceivedDlqBinding(
            @Qualifier("orderProjectionReceivedDlq") Queue orderProjectionReceivedDlq,
            @Qualifier("vibraniumDlqExchange")                DirectExchange vibraniumDlqExchange) {
        return BindingBuilder.bind(orderProjectionReceivedDlq).to(vibraniumDlqExchange).with(QUEUE_ORDER_PROJECTION_RECEIVED_DLQ);
    }

    // Projection: Funds Reserved
    public static final String QUEUE_ORDER_PROJECTION_FUNDS = "order.projection.funds-reserved";
    public static final String QUEUE_ORDER_PROJECTION_FUNDS_DLQ = "order.projection.funds-reserved.dlq";

    @Bean
    Queue orderProjectionFundsQueue() {
        return QueueBuilder.durable(QUEUE_ORDER_PROJECTION_FUNDS)
                .withArgument("x-dead-letter-exchange", VIBRANIUM_DLQ_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", QUEUE_ORDER_PROJECTION_FUNDS_DLQ)
                .build();
    }

    @Bean
    Binding orderProjectionFundsBinding(
            @Qualifier("orderProjectionFundsQueue") Queue orderProjectionFundsQueue,
            @Qualifier("vibraniumEventsExchange")            TopicExchange vibraniumEventsExchange) {
        return BindingBuilder.bind(orderProjectionFundsQueue).to(vibraniumEventsExchange).with(RK_FUNDS_RESERVED);
    }

    @Bean
    Queue orderProjectionFundsDlq() {
        return QueueBuilder.durable(QUEUE_ORDER_PROJECTION_FUNDS_DLQ).build();
    }

    @Bean
    Binding orderProjectionFundsDlqBinding(
            @Qualifier("orderProjectionFundsDlq") Queue orderProjectionFundsDlq,
            @Qualifier("vibraniumDlqExchange")             DirectExchange vibraniumDlqExchange) {
        return BindingBuilder.bind(orderProjectionFundsDlq).to(vibraniumDlqExchange).with(QUEUE_ORDER_PROJECTION_FUNDS_DLQ);
    }

    // Projection: Match Executed
    public static final String RK_MATCH_EXECUTED = "order.events.match-executed";
    public static final String QUEUE_ORDER_PROJECTION_MATCH = "order.projection.match-executed";
    public static final String QUEUE_ORDER_PROJECTION_MATCH_DLQ = "order.projection.match-executed.dlq";

    @Bean
    Queue orderProjectionMatchQueue() {
        return QueueBuilder.durable(QUEUE_ORDER_PROJECTION_MATCH)
                .withArgument("x-dead-letter-exchange", VIBRANIUM_DLQ_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", QUEUE_ORDER_PROJECTION_MATCH_DLQ)
                .build();
    }

    @Bean
    Binding orderProjectionMatchBinding(
            @Qualifier("orderProjectionMatchQueue") Queue orderProjectionMatchQueue,
            @Qualifier("vibraniumEventsExchange")            TopicExchange vibraniumEventsExchange) {
        return BindingBuilder.bind(orderProjectionMatchQueue).to(vibraniumEventsExchange).with(RK_MATCH_EXECUTED);
    }

    @Bean
    Queue orderProjectionMatchDlq() {
        return QueueBuilder.durable(QUEUE_ORDER_PROJECTION_MATCH_DLQ).build();
    }

    @Bean
    Binding orderProjectionMatchDlqBinding(
            @Qualifier("orderProjectionMatchDlq") Queue orderProjectionMatchDlq,
            @Qualifier("vibraniumDlqExchange")             DirectExchange vibraniumDlqExchange) {
        return BindingBuilder.bind(orderProjectionMatchDlq).to(vibraniumDlqExchange).with(QUEUE_ORDER_PROJECTION_MATCH_DLQ);
    }

    // Projection: Order Cancelled
    public static final String RK_ORDER_CANCELLED = "order.events.order-cancelled";
    public static final String QUEUE_ORDER_PROJECTION_CANCELLED = "order.projection.cancelled";
    public static final String QUEUE_ORDER_PROJECTION_CANCELLED_DLQ = "order.projection.cancelled.dlq";

    @Bean
    Queue orderProjectionCancelledQueue() {
        return QueueBuilder.durable(QUEUE_ORDER_PROJECTION_CANCELLED)
                .withArgument("x-dead-letter-exchange", VIBRANIUM_DLQ_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", QUEUE_ORDER_PROJECTION_CANCELLED_DLQ)
                .build();
    }

    @Bean
    Binding orderProjectionCancelledBinding(
            @Qualifier("orderProjectionCancelledQueue") Queue orderProjectionCancelledQueue,
            @Qualifier("vibraniumEventsExchange")            TopicExchange vibraniumEventsExchange) {
        return BindingBuilder.bind(orderProjectionCancelledQueue).to(vibraniumEventsExchange).with(RK_ORDER_CANCELLED);
    }

    @Bean
    Queue orderProjectionCancelledDlq() {
        return QueueBuilder.durable(QUEUE_ORDER_PROJECTION_CANCELLED_DLQ).build();
    }

    @Bean
    Binding orderProjectionCancelledDlqBinding(
            @Qualifier("orderProjectionCancelledDlq") Queue orderProjectionCancelledDlq,
            @Qualifier("vibraniumDlqExchange")             DirectExchange vibraniumDlqExchange) {
        return BindingBuilder.bind(orderProjectionCancelledDlq).to(vibraniumDlqExchange).with(QUEUE_ORDER_PROJECTION_CANCELLED_DLQ);
    }
}
