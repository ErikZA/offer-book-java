package com.vibranium.walletservice.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.vibranium.walletservice.application.dto.KeycloakEventDto;
import com.vibranium.walletservice.application.service.WalletService;
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
 * <p>Implementa at-most-once semantics via tabela {@code idempotency_key}:
 * o mesmo {@code messageId} jamais gera duas carteiras, mesmo que o broker
 * re-entregue a mensagem (at-least-once delivery).</p>
 *
 * <p>Política de ACK manual ({@code acknowledge-mode: manual}):</p>
 * <ul>
 *   <li>Sem {@code messageId} — NACK sem requeue (mensagem descartada).</li>
 *   <li>Já processado (idempotência) — ACK sem reprocessar.</li>
 *   <li>Tipo != REGISTER — ACK sem criar carteira.</li>
 *   <li>Sucesso — ACK após commit da transação.</li>
 *   <li>Falha inesperada — NACK sem requeue (evita poison pill).</li>
 * </ul>
 */
@Component
public class KeycloakRabbitListener {

    private static final Logger logger = LoggerFactory.getLogger(KeycloakRabbitListener.class);

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
    @RabbitListener(queues = "wallet.keycloak.events")
    public void handleKeycloakEvent(Message message, Channel channel) throws Exception {
        String messageId = message.getMessageProperties().getMessageId();
        long deliveryTag = message.getMessageProperties().getDeliveryTag();

        // Rejeita mensagens sem messageId — não há como garantir idempotência
        if (messageId == null || messageId.isBlank()) {
            logger.warn("Keycloak event received without messageId — NACKing without requeue");
            channel.basicNack(deliveryTag, false, false);
            return;
        }

        // Verificação de idempotência — mensagem já foi processada com sucesso
        if (idempotencyKeyRepository.existsById(messageId)) {
            logger.info("Keycloak event {} already processed — ACKing idempotently", messageId);
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            KeycloakEventDto event = objectMapper.readValue(body, KeycloakEventDto.class);

            // Ignora eventos que não sejam REGISTER (LOGIN, LOGOUT, etc.)
            if (!"REGISTER".equals(event.type())) {
                logger.debug("Ignoring Keycloak event type={} for userId={}", event.type(), event.userId());
                channel.basicAck(deliveryTag, false);
                return;
            }

            // Cria carteira e grava idempotency key na mesma transação
            walletService.createWallet(event.userId(), UUID.randomUUID(), messageId);

            channel.basicAck(deliveryTag, false);
            logger.info("Wallet created for userId={} via Keycloak REGISTER event", event.userId());

        } catch (Exception e) {
            logger.error("Failed to process Keycloak event messageId={}: {}", messageId, e.getMessage(), e);
            // NACK sem requeue — evita poison pill. Em produção, configurar dead-letter exchange.
            channel.basicNack(deliveryTag, false, false);
        }
    }
}
