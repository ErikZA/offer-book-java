package com.vibranium.orderservice.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibranium.orderservice.config.RabbitMQConfig;
import com.vibranium.orderservice.domain.model.UserRegistry;
import com.vibranium.orderservice.domain.repository.UserRegistryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumidor de eventos do Keycloak publicados pelo plugin
 * {@code aznamier/keycloak-event-listener-rabbitmq}.
 *
 * <p>O plugin publica JSON bruto no exchange {@code amq.topic} com routing key
 * {@code KK.EVENT.CLIENT.{realm}.{eventType}}. Este consumidor filtra apenas
 * eventos do tipo {@code REGISTER} e persiste o usuário no registro local.</p>
 *
 * <p>Processamento é <strong>idempotente</strong>: verifica existência antes
 * de inserir — o UNIQUE constraint em {@code keycloak_id} é a segunda linha
 * de defesa, mas a verificação prévia evita exception de constraint violation.</p>
 *
 * <p>Payload do plugin aznamier (simplificado):</p>
 * <pre>{@code
 * {
 *   "id": "uuid",
 *   "time": 1234567890000,
 *   "type": "REGISTER",
 *   "realmId": "orderbook-realm",
 *   "clientId": "order-client",
 *   "userId": "uuid-do-usuario",
 *   "ipAddress": "127.0.0.1",
 *   "details": { "email": "...", "username": "..." }
 * }
 * }</pre>
 */
@Component
public class KeycloakEventConsumer {

    private static final Logger logger = LoggerFactory.getLogger(KeycloakEventConsumer.class);

    /** Tipo de evento Keycloak que dispara o registro do usuário. */
    private static final String EVENT_TYPE_REGISTER = "REGISTER";

    private final UserRegistryRepository userRegistryRepository;
    private final ObjectMapper objectMapper;

    public KeycloakEventConsumer(UserRegistryRepository userRegistryRepository,
                                 ObjectMapper objectMapper) {
        this.userRegistryRepository = userRegistryRepository;
        this.objectMapper           = objectMapper;
    }

    /**
     * Processa mensagens da fila do Keycloak.
     *
     * <p>O payload é JSON bruto (não um objeto Java serializado pelo Spring AMQP),
     * por isso o parâmetro é {@code String} — desserializamos manualmente.</p>
     *
     * <p>Mensagens malformadas são logadas e NÃO relançam exceção para evitar
     * bloqueio da fila. A mensagem será NAcked e roteada para a DLQ após
     * esgotar as tentativas configuradas no Spring Retry.</p>
     *
     * <p>O parâmetro é {@code Message} (AMQP nativo) em vez de {@code @Payload byte[]}
     * ou {@code @Payload String} para que o {@code Jackson2JsonMessageConverter} global
     * seja completamente ignorado. Receber o {@code Message} diretamente faz o
     * Spring AMQP entregar o objeto sem nenhuma conversão, evitando o
     * {@code MismatchedInputException} que ocorre quando o converter tenta
     * desserializar JSON Object como scalar String ou byte[].
     * A conversão para String é feita manualmente com UTF-8.</p>
     *
     * @param amqpMessage   Mensagem AMQP nativa com bytes JSON brutos no body.
     */
    @RabbitListener(queues = RabbitMQConfig.QUEUE_KEYCLOAK_REG)
    public void onKeycloakEvent(Message amqpMessage) {

        String routingKey = amqpMessage.getMessageProperties().getReceivedRoutingKey();
        logger.debug("Evento Keycloak recebido: routingKey={}", routingKey);

        try {
            String payload = new String(amqpMessage.getBody(), java.nio.charset.StandardCharsets.UTF_8);
            JsonNode tree = objectMapper.readTree(payload);
            processEvent(tree);
        } catch (JsonProcessingException e) {
            // JSON malformado → loga e descarta
            // O Spring Retry já tentou novamente antes de chegar aqui
            logger.error("Payload Keycloak malformado — descartando mensagem: error={}",
                    e.getMessage());
            // Re-lança para que o listener envie NACK e a DLQ capture
            throw new RuntimeException("Payload Keycloak inválido", e);
        }
    }

    // -------------------------------------------------------------------------
    // Lógica interna
    // -------------------------------------------------------------------------

    /**
     * Processa o payload JSON decodificado:
     * <ol>
     *   <li>Ignora eventos que não sejam {@code REGISTER}.</li>
     *   <li>Extrai {@code userId}; ignora se ausente ou vazio.</li>
     *   <li>Verifica idempotência e persiste.</li>
     * </ol>
     *
     * @param tree Nó JSON raiz do evento.
     */
    void processEvent(JsonNode tree) {
        String eventType = tree.path("type").asText("");

        // Filtra apenas eventos REGISTER
        if (!EVENT_TYPE_REGISTER.equalsIgnoreCase(eventType)) {
            logger.debug("Evento Keycloak ignorado: type={}", eventType);
            return;
        }

        // Usa isNull()/isMissingNode() para distinguir JSON null de string vazia.
        // asText("") retorna "null" (literalmente) para NullNode — não o default —
        // portanto a verificação de isBlank() sozinha não é suficiente.
        JsonNode userIdNode = tree.path("userId");
        if (userIdNode.isNull() || userIdNode.isMissingNode()) {
            logger.warn("Evento REGISTER sem userId (null/ausente) — descartando: payload={}",
                    tree);
            return;
        }
        String userId = userIdNode.asText("");
        if (userId.isBlank()) {
            logger.warn("Evento REGISTER sem userId (vazio) — descartando: payload={}", tree);
            return;
        }

        // Idempotência: evita inserção duplicada do mesmo usuário
        if (userRegistryRepository.existsByKeycloakId(userId)) {
            logger.debug("Usuário já registrado (idempotente): keycloakId={}", userId);
            return;
        }

        UserRegistry registry = new UserRegistry(userId);
        userRegistryRepository.save(registry);

        logger.info("Usuário registrado no order-service: keycloakId={}", userId);
    }
}
