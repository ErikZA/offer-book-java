package com.vibranium.utils.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.vibranium.utils.dto.KeycloakEventDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Abstract base class for RabbitMQ listeners that process Keycloak events.
 * 
 * <p>Handles common logic:
 * <ul>
 *   <li>JSON deserialization (handling potential double-encoding).</li>
 *   <li>Filtering relevant events (REGISTER/CREATE USER).</li>
 *   <li>Extracting userId and resolving eventId for idempotency.</li>
 * </ul>
 */
public abstract class AbstractKeycloakRabbitListener {

    private static final Logger logger = LoggerFactory.getLogger(AbstractKeycloakRabbitListener.class);
    
    protected static final String EVENT_TYPE_REGISTER = "REGISTER";
    protected static final String EVENT_ID_HEADER = "x-event-id";

    protected final ObjectMapper objectMapper;

    protected AbstractKeycloakRabbitListener(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Entry point for Spring AMQP @RabbitListener.
     */
    protected void onMessage(Message amqpMessage, Channel channel) throws Exception {
        long deliveryTag = amqpMessage.getMessageProperties().getDeliveryTag();
        String body = "";

        try {
            body = new String(amqpMessage.getBody(), StandardCharsets.UTF_8);
            KeycloakEventDto event = deserialize(body);
            
            if (event == null) {
                logger.error("Parsed Keycloak event is null — NACKing without requeue");
                channel.basicNack(deliveryTag, false, false);
                return;
            }

            if (!isRelevant(event)) {
                logger.debug("Ignoring irrelevant Keycloak event: type={}, operationType={}, resourceType={}",
                             event.type(), event.operationType(), event.resourceType());
                channel.basicAck(deliveryTag, false);
                return;
            }

            if (event.error() != null && !event.error().isBlank()) {
                logger.warn("Ignoring failed Keycloak event: error={}", event.error());
                channel.basicAck(deliveryTag, false);
                return;
            }

            UUID userId = extractUserId(event);
            if (userId == null) {
                logger.warn("Keycloak event without resolvable userId — NACKing without requeue.");
                channel.basicNack(deliveryTag, false, false);
                return;
            }

            String eventId = resolveEventId(amqpMessage, event, userId);
            
            handleEvent(event, body, userId, eventId, deliveryTag, channel);

        } catch (Exception e) {
            logger.error("Unexpected failure processing Keycloak event: {}", e.getMessage(), e);
            // NACK without requeue to avoid poison pill
            channel.basicNack(deliveryTag, false, false);
        }
    }

    /**
     * Template method for service-specific processing.
     */
    protected abstract void handleEvent(KeycloakEventDto event, String body, UUID userId, String eventId, long deliveryTag, Channel channel) throws Exception;

    private KeycloakEventDto deserialize(String body) {
        try {
            // Standard deserialization
            try {
                return objectMapper.readValue(body, KeycloakEventDto.class);
            } catch (Exception e) {
                // Handle potential double-encoding (escaped JSON string)
                String unescaped = objectMapper.readValue(body, String.class);
                return objectMapper.readValue(unescaped, KeycloakEventDto.class);
            }
        } catch (Exception e) {
            logger.error("Failed to deserialize Keycloak event: {}", e.getMessage());
            return null;
        }
    }

    private boolean isRelevant(KeycloakEventDto event) {
        // CLIENT REGISTER
        boolean isClientRegister = EVENT_TYPE_REGISTER.equalsIgnoreCase(event.type());

        // ADMIN CREATE USER
        boolean isAdminCreateUser = "CREATE".equalsIgnoreCase(event.operationType())
                && "USER".equalsIgnoreCase(event.resourceType());

        return isClientRegister || isAdminCreateUser;
    }

    private UUID extractUserId(KeycloakEventDto event) {
        if (event.userId() != null) {
            return event.userId();
        }
        String path = event.resourcePath();
        if (path != null && path.startsWith("users/")) {
            try {
                return UUID.fromString(path.substring("users/".length()));
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private String resolveEventId(Message message, KeycloakEventDto event, UUID userId) {
        // 1. Try x-event-id header
        Object rawHeader = message.getMessageProperties().getHeaders().get(EVENT_ID_HEADER);
        String headerValue = normalizeHeaderValue(rawHeader);
        if (headerValue != null && !headerValue.isBlank()) return headerValue;

        // 2. Try AMQP messageId
        String messageId = message.getMessageProperties().getMessageId();
        if (messageId != null && !messageId.isBlank()) return messageId;

        // 3. Try event.id from DTO
        if (event.id() != null && !event.id().isBlank()) return event.id();

        // 4. Fallback: hash of userId + time (semi-deterministic)
        return UUID.nameUUIDFromBytes((userId.toString() + "-" + event.time()).getBytes()).toString();
    }

    private String normalizeHeaderValue(Object value) {
        if (value == null) return null;
        if (value instanceof byte[] rawBytes) return new String(rawBytes, StandardCharsets.UTF_8);
        return value.toString();
    }
}
