package com.vibranium.orderservice.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibranium.orderservice.application.dto.KeycloakEventDto;
import com.vibranium.orderservice.config.RabbitMQConfig;
import com.vibranium.orderservice.application.service.UserRegistryService;
import com.vibranium.orderservice.domain.model.UserRegistry;
import com.vibranium.orderservice.domain.repository.UserRegistryRepository;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Consumidor de eventos do Keycloak publicados pelo plugin
 * {@code aznamier/keycloak-event-listener-rabbitmq}.
 *
 * <p>O plugin publica JSON bruto no exchange {@code amq.topic} com routing key
 * {@code KK.EVENT.CLIENT.{realm}.{SUCCESS|ERROR}.{clientId}.{eventType}}.
 * Este consumidor persiste apenas REGISTER de sucesso.
 *
 * <p><strong>Filtro de realm:</strong> feito pelo binding na exchange {@code amq.topic}
 * (routing key {@code KK.EVENT.CLIENT.orderbook-realm.SUCCESS.*.REGISTER}).
 * O plugin aznamier envia {@code realmId} como UUID interno do Keycloak
 * (ex: {@code 7628dd2f-...}), não como o nome legível do realm, portanto
 * a validação em runtime é delegada exclusivamente à topologia RabbitMQ.</p>
 *
 * <p>Processamento é <strong>idempotente</strong>: verifica existência antes
 * de inserir — o UNIQUE constraint em {@code keycloak_id} é a segunda linha
 * de defesa, mas a verificação prévia evita exception de constraint violation.</p>
 */
@Component
public class KeycloakEventConsumer {

    private static final Logger logger = LoggerFactory.getLogger(KeycloakEventConsumer.class);

    /** Tipo de evento Keycloak que dispara o registro do usuário. */
    private static final String EVENT_TYPE_REGISTER = "REGISTER";

    private final UserRegistryService userRegistryService;
    private final ObjectMapper objectMapper;

    public KeycloakEventConsumer(UserRegistryService userRegistryService,
                                 ObjectMapper objectMapper) {
        this.userRegistryService = userRegistryService;
        this.objectMapper        = objectMapper;
    }

    /**
     * Processa mensagens da fila do Keycloak.
     *
     * <p>O payload é JSON bruto (não um objeto Java serializado pelo Spring AMQP),
     * por isso o parâmetro é {@code Message} — desserializamos manualmente.</p>
     *
     * @param amqpMessage   Mensagem AMQP nativa com bytes JSON brutos no body.
     * @param channel       Canal AMQP para ACK/NACK manual.
     */
    @RabbitListener(queues = RabbitMQConfig.QUEUE_KEYCLOAK_EVENTS, containerFactory = "rawMessageContainerFactory")
    public void onKeycloakEvent(Message amqpMessage, Channel channel) throws Exception {
        long deliveryTag = amqpMessage.getMessageProperties().getDeliveryTag();

        String routingKey = amqpMessage.getMessageProperties().getReceivedRoutingKey();
        logger.debug("Evento Keycloak recebido: routingKey={}", routingKey);

        KeycloakEventDto event;
        String body;

        try {
            body = new String(amqpMessage.getBody(), StandardCharsets.UTF_8);
            
            // Tenta desserializar diretamente. Se falhar, tenta como String literal (compatibilidade).
            try {
                event = objectMapper.readValue(body, KeycloakEventDto.class);
            } catch (Exception e) {
                // Tenta extrair como string literal (caso o publisher tenha escapado o JSON)
                String unescaped = objectMapper.readValue(body, String.class);
                event = objectMapper.readValue(unescaped, KeycloakEventDto.class);
            }

            if (event == null) {
                logger.error("Payload Keycloak resultou em null — enviando para DLQ");
                channel.basicNack(deliveryTag, false, false);
                return;
            }

            processEvent(event, body);
            channel.basicAck(deliveryTag, false);
        } catch (JsonProcessingException e) {
            // JSON genuinamente malformado ou incompatível com o DTO
            logger.error("Payload Keycloak malformado — enviando para DLQ: error={}", e.getMessage());
            channel.basicNack(deliveryTag, false, false);
        } catch (Exception e) {
            // Falha inesperada (ex: DB fora) — NACK sem requeue para evitar poison pill
            logger.error("Falha ao processar evento Keycloak: {}", e.getMessage(), e);
            channel.basicNack(deliveryTag, false, false);
        }
    }

    // -------------------------------------------------------------------------
    // Lógica interna
    // -------------------------------------------------------------------------

    /**
     * Processa o payload DTO decodificado.
     *
     * @param event DTO do evento Keycloak.
     * @param body  JSON bruto do evento.
     */
    private void processEvent(KeycloakEventDto event, String body) {
        // Evento CLIENT (auto-cadastro via form de registro)
        boolean isClientRegister = EVENT_TYPE_REGISTER.equalsIgnoreCase(event.type());

        // Evento ADMIN (criação de usuário via Keycloak Admin API / console)
        boolean isAdminCreateUser = "CREATE".equalsIgnoreCase(event.operationType())
                && "USER".equalsIgnoreCase(event.resourceType());

        if (!isClientRegister && !isAdminCreateUser) {
            logger.debug("Evento Keycloak ignorado: type={}, operationType={}, resourceType={}",
                         event.type(), event.operationType(), event.resourceType());
            return;
        }

        // Verifica campo error (presente em eventos de FALHA)
        if (event.error() != null && !event.error().isBlank()) {
            logger.warn("Evento Keycloak de erro ignorado: error={}", event.error());
            return;
        }

        String userId = null;
        if (isClientRegister) {
            if (event.userId() != null) {
                userId = event.userId().toString();
            }
        } else {
            String resourcePath = event.resourcePath();
            if (resourcePath != null && resourcePath.startsWith("users/")) {
                userId = resourcePath.substring("users/".length());
            } else if (event.userId() != null) {
                userId = event.userId().toString();
            }
        }

        if (userId == null || userId.isBlank()) {
            logger.warn("Evento Keycloak sem userId válido — descartando.");
            return;
        }

        // Delega para o serviço de registro (transacional: UserRegistry + EventStore)
        userRegistryService.registerUser(userId, event, body);
    }
}
