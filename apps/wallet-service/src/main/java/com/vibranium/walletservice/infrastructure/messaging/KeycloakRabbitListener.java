package com.vibranium.walletservice.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.vibranium.utils.dto.KeycloakEventDto;
import com.vibranium.utils.messaging.AbstractKeycloakRabbitListener;
import com.vibranium.walletservice.application.service.WalletService;
import com.vibranium.walletservice.config.RabbitMQConfig;
import com.vibranium.walletservice.domain.repository.IdempotencyKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Listener that processes user events from Keycloak to create wallets.
 *
 * <p>Implementation logic:
 * <ul>
 *   <li>Only REGISTER or ADMIN CREATE events trigger wallet creation.</li>
 *   <li>Uses IdempotencyKeyRepository to prevent duplicate processing.</li>
 *   <li>Policy: ACK on success or duplicate, NACK on fatal error.</li>
 * </ul>
 */
@Component
public class KeycloakRabbitListener extends AbstractKeycloakRabbitListener {

    private static final Logger logger = LoggerFactory.getLogger(KeycloakRabbitListener.class);

    private final WalletService walletService;
    private final IdempotencyKeyRepository idempotencyKeyRepository;

    public KeycloakRabbitListener(WalletService walletService,
                                   IdempotencyKeyRepository idempotencyKeyRepository,
                                   ObjectMapper objectMapper) {
        super(objectMapper);
        this.walletService = walletService;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
    }

    @Override
    @RabbitListener(queues = RabbitMQConfig.QUEUE_KEYCLOAK_EVENTS, containerFactory = "rawMessageContainerFactory")
    public void onMessage(Message message, Channel channel) throws Exception {
        super.onMessage(message, channel);
    }

    @Override
    protected void handleEvent(KeycloakEventDto event, String body, UUID userId, String eventId, long deliveryTag, Channel channel) throws Exception {
        if (idempotencyKeyRepository.existsById(eventId)) {
            logger.info("Keycloak event {} already processed — ACKing idempotently", eventId);
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            walletService.createWallet(userId, UUID.randomUUID(), eventId);
            channel.basicAck(deliveryTag, false);
            logger.info("Wallet created for userId={} via Keycloak event {}", userId, eventId);
        } catch (Exception e) {
            logger.error("Failed to process Keycloak event id={}: {}", eventId, e.getMessage(), e);
            // This propagates to super.onMessage which will NACK without requeue
            throw e; 
        }
    }
}
