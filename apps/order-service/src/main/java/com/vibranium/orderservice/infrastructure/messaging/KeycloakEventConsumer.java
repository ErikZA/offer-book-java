package com.vibranium.orderservice.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibranium.orderservice.config.RabbitMQConfig;
import com.vibranium.orderservice.domain.model.UserRegistry;
import com.vibranium.orderservice.domain.repository.UserRegistryRepository;
import com.rabbitmq.client.Channel;
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
     * <p>Mensagens malformadas ou que causam falha no processamento são NACKed
     * sem requeue, sendo roteadas para a DLQ. Mensagens processadas com sucesso
     * são ACKed.</p>
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
     * @param channel       Canal AMQP para ACK/NACK manual.
     */
    @RabbitListener(queues = RabbitMQConfig.QUEUE_KEYCLOAK_EVENTS, containerFactory = "rawMessageContainerFactory")
    public void onKeycloakEvent(Message amqpMessage, Channel channel) throws Exception {
        long deliveryTag = amqpMessage.getMessageProperties().getDeliveryTag();

        String routingKey = amqpMessage.getMessageProperties().getReceivedRoutingKey();
        logger.debug("Evento Keycloak recebido: routingKey={}", routingKey);

        try {
            String payload = new String(amqpMessage.getBody(), java.nio.charset.StandardCharsets.UTF_8);
            JsonNode tree = objectMapper.readTree(payload);
            if (tree.isTextual()) {
                // Compatibilidade com publishers que serializam JSON como string literal
                tree = objectMapper.readTree(tree.asText());
            }
            processEvent(tree);
            channel.basicAck(deliveryTag, false);
        } catch (JsonProcessingException e) {
            // JSON malformado — NACK sem requeue (envia para DLQ)
            logger.error("Payload Keycloak malformado — enviando para DLQ: error={}",
                    e.getMessage());
            channel.basicNack(deliveryTag, false, false);
        } catch (Exception e) {
            // Falha inesperada — NACK sem requeue para evitar poison pill
            logger.error("Falha ao processar evento Keycloak: {}", e.getMessage(), e);
            channel.basicNack(deliveryTag, false, false);
        }
    }

    // -------------------------------------------------------------------------
    // Lógica interna
    // -------------------------------------------------------------------------

    /**
     * Processa o payload JSON decodificado:
     * <ol>
     *   <li>Ignora eventos que não sejam {@code REGISTER}.</li>
     *   <li>Ignora eventos de erro ({@code error} preenchido).</li>
     *   <li>Extrai {@code userId}; ignora se ausente ou vazio.</li>
     *   <li>Verifica idempotência e persiste.</li>
     * </ol>
     *
     * @param tree Nó JSON raiz do evento.
     */
    void processEvent(JsonNode tree) {
        // Filtro de realm removido: o binding na exchange amq.topic já garante
        // que apenas eventos do realm correto (orderbook-realm) cheguem a esta fila.
        // O plugin aznamier envia realmId como UUID interno do Keycloak
        // (ex: 7628dd2f-df86-4fe6-b298-03b98b905fb0), não como nome legível.

        String eventType     = tree.path("type").asText("");
        String operationType = tree.path("operationType").asText("");
        String resourceType  = tree.path("resourceType").asText("");

        // Evento CLIENT (auto-cadastro via form de registro)
        boolean isClientRegister = EVENT_TYPE_REGISTER.equalsIgnoreCase(eventType);

        // Evento ADMIN (criação de usuário via Keycloak Admin API / console)
        // Payload: { "operationType": "CREATE", "resourceType": "USER", "resourcePath": "users/{uuid}" }
        boolean isAdminCreateUser = "CREATE".equalsIgnoreCase(operationType)
                && "USER".equalsIgnoreCase(resourceType);

        if (!isClientRegister && !isAdminCreateUser) {
            logger.debug("Evento Keycloak ignorado: type={}, operationType={}, resourceType={}",
                         eventType, operationType, resourceType);
            return;
        }

        // Verifica campo error (presente em eventos de FALHA — não deve criar registro)
        JsonNode errorNode = tree.path("error");
        if (!errorNode.isMissingNode() && !errorNode.isNull()) {
            String error = errorNode.asText("");
            if (!error.isBlank()) {
                logger.warn("Evento Keycloak REGISTER/CREATE de erro ignorado: error={}", error);
                return;
            }
        }

        String userId;

        if (isClientRegister) {
            // Eventos CLIENT sempre carregam userId no campo raiz
            JsonNode userIdNode = tree.path("userId");
            if (userIdNode.isNull() || userIdNode.isMissingNode()) {
                logger.warn("Evento REGISTER sem userId (null/ausente) — descartando: payload={}", tree);
                return;
            }
            userId = userIdNode.asText("");
        } else {
            // Eventos ADMIN carregam o userId no resourcePath: "users/{uuid}"
            String resourcePath = tree.path("resourcePath").asText("");
            if (resourcePath.startsWith("users/")) {
                userId = resourcePath.substring("users/".length());
            } else {
                // Fallback: alguns eventos admin também trazem userId no campo raiz
                JsonNode userIdNode = tree.path("userId");
                if (!userIdNode.isNull() && !userIdNode.isMissingNode()) {
                    userId = userIdNode.asText("");
                } else {
                    logger.warn("Evento ADMIN CREATE sem resourcePath ou userId válido — descartando: payload={}", tree);
                    return;
                }
            }
        }

        if (userId == null || userId.isBlank()) {
            logger.warn("Evento Keycloak sem userId válido (vazio) — descartando: payload={}", tree);
            return;
        }

        // Idempotência: evita inserção duplicada do mesmo usuário
        if (userRegistryRepository.existsByKeycloakId(userId)) {
            logger.debug("Usuário já registrado (idempotente): keycloakId={}", userId);
            return;
        }

        UserRegistry registry = new UserRegistry(userId);
        userRegistryRepository.save(registry);

        logger.info("Usuário registrado no order-service: keycloakId={} (via {})",
                userId, isClientRegister ? "CLIENT/REGISTER" : "ADMIN/CREATE");
    }
}
