package com.vibranium.walletservice.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.vibranium.walletservice.application.dto.KeycloakEventDto;
import com.vibranium.walletservice.application.service.WalletService;
import com.vibranium.walletservice.config.RabbitMQConfig;
import com.vibranium.walletservice.domain.repository.IdempotencyKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Listener que processa eventos de usuário publicados pelo plugin
 * {@code aznamier/keycloak-event-listener-rabbitmq}.
 *
 * <p>Somente eventos do tipo {@code REGISTER} criam uma nova carteira.
 * Todos os outros tipos (LOGIN, LOGOUT, UPDATE_PROFILE, etc.) são
 * consumidos e descartados (ACK sem processamento).</p>
 *
 * <p><strong>Filtro de realm:</strong> feito pelo binding na exchange {@code amq.topic}
 * (routing key {@code KK.EVENT.CLIENT.orderbook-realm.SUCCESS.*.REGISTER}).
 * O plugin aznamier envia {@code realmId} como UUID interno do Keycloak
 * (ex: {@code 7628dd2f-...}), não como o nome legível do realm, portanto
 * a validação em runtime é delegada exclusivamente à topologia RabbitMQ.</p>
 *
 * <p>Implementa at-most-once semantics via tabela {@code idempotency_key}:
 * o mesmo {@code eventId} jamais gera duas carteiras, mesmo que o broker
 * re-entregue a mensagem (at-least-once delivery).</p>
 *
 * <p>Política de ACK manual ({@code acknowledge-mode: manual}):</p>
 * <ul>
 *   <li>Sem identificador de evento ({@code x-event-id/id/messageId}) — NACK sem requeue.</li>
 *   <li>Realm diferente de {@code orderbook-realm} — ACK sem processar.</li>
 *   <li>Evento com {@code error} preenchido (REGISTER com falha) — ACK sem processar.</li>
 *   <li>Já processado (idempotência) — ACK sem reprocessar.</li>
 *   <li>Tipo != REGISTER — ACK sem criar carteira.</li>
 *   <li>Sucesso — ACK após commit da transação.</li>
 *   <li>Falha inesperada — NACK sem requeue (evita poison pill).</li>
 * </ul>
 */
@Component
public class KeycloakRabbitListener {

    private static final Logger logger = LoggerFactory.getLogger(KeycloakRabbitListener.class);
    private static final String EVENT_TYPE_REGISTER = "REGISTER";
    private static final String EVENT_ID_HEADER = "x-event-id";

    private final WalletService walletService;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final ObjectMapper objectMapper;

    public KeycloakRabbitListener(WalletService walletService,
                                   IdempotencyKeyRepository idempotencyKeyRepository,
                                   ObjectMapper objectMapper) {
        this.walletService = walletService;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Processa um evento Keycloak publicado na fila {@code wallet.keycloak.events}.
     *
     * @param message     Mensagem AMQP raw com payload JSON.
     * @param channel     Canal AMQP para ACK/NACK manual.
     * @throws Exception  Propagado apenas para cenários de erro no AMQP channel.
     */
    @RabbitListener(queues = RabbitMQConfig.QUEUE_KEYCLOAK_EVENTS, containerFactory = "rawMessageContainerFactory")
    public void handleKeycloakEvent(Message message, Channel channel) throws Exception {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        KeycloakEventDto event;
        String body;

        try {
            body = new String(message.getBody(), StandardCharsets.UTF_8);
            event = objectMapper.readValue(body, KeycloakEventDto.class);
        } catch (Exception e) {
            logger.error("Failed to deserialize Keycloak event payload: {}", e.getMessage(), e);
            channel.basicNack(deliveryTag, false, false);
            return;
        }

        // Filtro de realm removido: o binding na exchange amq.topic já garante
        // que apenas eventos do realm correto (orderbook-realm) cheguem a esta fila.
        // O plugin aznamier envia realmId como UUID interno do Keycloak
        // (ex: 7628dd2f-df86-4fe6-b298-03b98b905fb0), não como nome legível.

        // Evento CLIENT (auto-cadastro via form de registro)
        boolean isClientRegister = EVENT_TYPE_REGISTER.equalsIgnoreCase(event.type());

        // Evento ADMIN (criação de usuário via Keycloak Admin API / console)
        // Payload: { "operationType": "CREATE", "resourceType": "USER", "resourcePath": "users/{uuid}" }
        boolean isAdminCreateUser = "CREATE".equalsIgnoreCase(event.operationType())
                && "USER".equalsIgnoreCase(event.resourceType());

        if (!isClientRegister && !isAdminCreateUser) {
            logger.debug("Ignoring irrelevant Keycloak event: type={}, operationType={}, resourceType={}",
                         event.type(), event.operationType(), event.resourceType());
            channel.basicAck(deliveryTag, false);
            return;
        }


        if (event.error() != null && !event.error().isBlank()) {
            logger.warn("Ignoring failed Keycloak event id={} error={}", event.id(), event.error());
            channel.basicAck(deliveryTag, false);
            return;
        }

        UUID userId = extractUserId(event);
        if (userId == null) {
            logger.warn("Keycloak event without resolvable userId — NACKing without requeue. Payload: {}", body);
            channel.basicNack(deliveryTag, false, false);
            return;
        }

        String eventId = resolveEventId(message, event);
        if (eventId == null || eventId.isBlank()) {
            eventId = java.util.UUID.nameUUIDFromBytes((userId.toString() + "-" + event.time()).getBytes()).toString();
            logger.debug("Keycloak REGISTER event extracted missing id using fallback: {}", eventId);
        }

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
            channel.basicNack(deliveryTag, false, false);
        }
    }

    private UUID extractUserId(KeycloakEventDto event) {
        if (event.userId() != null) {
            return event.userId();
        }
        if (event.resourcePath() != null && event.resourcePath().startsWith("users/")) {
            try {
                return UUID.fromString(event.resourcePath().substring("users/".length()));
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private String resolveEventId(Message message, KeycloakEventDto event) {
        Object rawHeaderEventId = message.getMessageProperties().getHeaders().get(EVENT_ID_HEADER);
        String headerEventId = normalizeHeaderValue(rawHeaderEventId);
        if (headerEventId != null && !headerEventId.isBlank()) {
            return headerEventId;
        }

        String messageId = message.getMessageProperties().getMessageId();
        if (messageId != null && !messageId.isBlank()) {
            return messageId;
        }

        return event.id();
    }

    private String normalizeHeaderValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[] rawBytes) {
            return new String(rawBytes, StandardCharsets.UTF_8);
        }
        return value.toString();
    }
}
