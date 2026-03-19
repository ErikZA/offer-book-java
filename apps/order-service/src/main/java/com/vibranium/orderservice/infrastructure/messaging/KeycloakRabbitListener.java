package com.vibranium.orderservice.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.vibranium.orderservice.application.service.UserRegistryService;
import com.vibranium.orderservice.config.RabbitMQConfig;
import com.vibranium.utils.dto.KeycloakEventDto;
import com.vibranium.utils.messaging.AbstractKeycloakRabbitListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Keycloak event listener for order-service.
 * Persists registered users to the local database and event store.
 */
@Component
public class KeycloakRabbitListener extends AbstractKeycloakRabbitListener {

    private static final Logger logger = LoggerFactory.getLogger(KeycloakRabbitListener.class);

    private final UserRegistryService userRegistryService;

    public KeycloakRabbitListener(UserRegistryService userRegistryService, ObjectMapper objectMapper) {
        super(objectMapper);
        this.userRegistryService = userRegistryService;
    }

    @Override
    @RabbitListener(queues = RabbitMQConfig.QUEUE_KEYCLOAK_EVENTS, containerFactory = "rawMessageContainerFactory")
    public void onMessage(Message amqpMessage, Channel channel) throws Exception {
        super.onMessage(amqpMessage, channel);
    }

    @Override
    protected void handleEvent(KeycloakEventDto event, String body, UUID userId, String eventId, long deliveryTag, Channel channel) throws Exception {
        logger.debug("Processing Keycloak event for order-service: userId={}, eventId={}", userId, eventId);
        
        // order-service uses String userId in UserRegistryService
        userRegistryService.registerUser(userId.toString(), event, body);
        
        channel.basicAck(deliveryTag, false);
        logger.info("User registered in order-service: userId={} (eventId={})", userId, eventId);
    }
}
