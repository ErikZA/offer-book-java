package com.vibranium.orderservice.integration;

import com.vibranium.orderservice.config.RabbitMQConfig;
import com.vibranium.orderservice.domain.repository.UserRegistryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Testa o {@code KeycloakEventConsumer} do order-service.
 *
 * <p>Valida o processamento de eventos REGISTER publicados pelo plugin
 * aznamier do Keycloak via RabbitMQ. Simula o envio de bytes JSON brutos
 * (como o plugin Keycloak realmente faz), incluindo o header
 * {@code __TypeId__} que causa {@code MessageConversionException} quando
 * o consumer usa {@code Jackson2JsonMessageConverter} em vez de
 * {@code rawMessageContainerFactory}.</p>
 *
 * <p><strong>Nota:</strong> o campo {@code realmId} no payload é enviado
 * como UUID interno do Keycloak (ex: {@code 7628dd2f-...}), não como o
 * nome legível do realm. Isso reproduz o comportamento real do plugin.</p>
 */
@DisplayName("[Integration] KeycloakEventConsumer — Registro de usuários via Keycloak")
class KeycloakEventConsumerIntegrationTest extends AbstractIntegrationTest {

    private static final String KEYCLOAK_EXCHANGE = "amq.topic";
    private static final String KEYCLOAK_REGISTER_ROUTING_KEY = RabbitMQConfig.RK_KEYCLOAK_REGISTER_SUCCESS;
    private static final String KEYCLOAK_ADMIN_ROUTING_KEY =
            "KK.EVENT.ADMIN.orderbook-realm.SUCCESS.CREATE.USER";

    @Autowired
    private UserRegistryRepository userRegistryRepository;

    @BeforeEach
    void cleanup() {
        userRegistryRepository.deleteAll();
    }

    @Test
    @DisplayName("Deve registrar usuário quando Keycloak publica evento REGISTER com bytes JSON brutos")
    void shouldRegisterUserFromRawJsonBytes() {
        UUID userId = UUID.randomUUID();
        String jsonPayload = buildKeycloakPayload(userId, "REGISTER");

        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        // Simula header __TypeId__ do plugin aznamier
        props.setHeader("__TypeId__",
                "com.github.aznamier.keycloak.event.provider.EventClientNotificationMqMsg");
        Message message = new Message(jsonPayload.getBytes(StandardCharsets.UTF_8), props);
        rabbitTemplate.send(KEYCLOAK_EXCHANGE, KEYCLOAK_REGISTER_ROUTING_KEY, message);

        await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() ->
                        assertThat(userRegistryRepository.existsByKeycloakId(userId.toString()))
                                .as("UserRegistry deve ser criado para o userId do evento REGISTER")
                                .isTrue());
    }

    @Test
    @DisplayName("Deve ignorar eventos que não sejam REGISTER (ex: LOGIN)")
    void shouldIgnoreNonRegisterEvents() {
        UUID userId = UUID.randomUUID();
        String jsonPayload = buildKeycloakPayload(userId, "LOGIN");

        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setHeader("__TypeId__",
                "com.github.aznamier.keycloak.event.provider.EventClientNotificationMqMsg");
        Message message = new Message(jsonPayload.getBytes(StandardCharsets.UTF_8), props);
        rabbitTemplate.send(KEYCLOAK_EXCHANGE, "KK.EVENT.CLIENT.orderbook-realm.SUCCESS.order-client.LOGIN", message);

        await()
                .during(2, TimeUnit.SECONDS)
                .atMost(3, TimeUnit.SECONDS)
                .untilAsserted(() ->
                        assertThat(userRegistryRepository.existsByKeycloakId(userId.toString()))
                                .isFalse());
    }

    @Test
    @DisplayName("Deve ignorar REGISTER com campo error preenchido")
    void shouldIgnoreFailedRegisterEvents() {
        UUID userId = UUID.randomUUID();
        String jsonPayload = buildKeycloakPayload(userId, "REGISTER", null, "email already exists");

        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setHeader("__TypeId__",
                "com.github.aznamier.keycloak.event.provider.EventClientNotificationMqMsg");
        Message message = new Message(jsonPayload.getBytes(StandardCharsets.UTF_8), props);
        rabbitTemplate.send(KEYCLOAK_EXCHANGE, KEYCLOAK_REGISTER_ROUTING_KEY, message);

        await()
                .during(2, TimeUnit.SECONDS)
                .atMost(3, TimeUnit.SECONDS)
                .untilAsserted(() ->
                        assertThat(userRegistryRepository.existsByKeycloakId(userId.toString()))
                                .isFalse());
    }

    @Test
    @DisplayName("Deve ser idempotente para eventos REGISTER duplicados")
    void shouldBeIdempotentForDuplicateRegisterEvents() {
        UUID userId = UUID.randomUUID();
        String jsonPayload = buildKeycloakPayload(userId, "REGISTER");

        for (int i = 0; i < 2; i++) {
            MessageProperties props = new MessageProperties();
            props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            props.setHeader("__TypeId__",
                    "com.github.aznamier.keycloak.event.provider.EventClientNotificationMqMsg");
            Message message = new Message(jsonPayload.getBytes(StandardCharsets.UTF_8), props);
            rabbitTemplate.send(KEYCLOAK_EXCHANGE, KEYCLOAK_REGISTER_ROUTING_KEY, message);
        }

        await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() ->
                        assertThat(userRegistryRepository.existsByKeycloakId(userId.toString()))
                                .isTrue());

        long count = userRegistryRepository.findAll().stream()
                .filter(r -> r.getKeycloakId().equals(userId.toString()))
                .count();
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("Deve registrar usuário via evento ADMIN CREATE (criação via Keycloak Admin API)")
    void shouldRegisterUserFromAdminCreateEvent() {
        UUID userId = UUID.randomUUID();
        // Payload no formato de Admin Event do plugin aznamier
        String jsonPayload = """
                {
                  "id": "%s",
                  "time": %d,
                  "operationType": "CREATE",
                  "resourceType": "USER",
                  "resourcePath": "users/%s",
                  "realmId": "orderbook-realm"
                }
                """.formatted(UUID.randomUUID(), System.currentTimeMillis(), userId);

        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setHeader("__TypeId__",
                "com.github.aznamier.keycloak.event.provider.EventClientNotificationMqMsg");
        Message message = new Message(jsonPayload.getBytes(StandardCharsets.UTF_8), props);
        // Admin events usam routing key com segmento ADMIN
        rabbitTemplate.send(KEYCLOAK_EXCHANGE, KEYCLOAK_ADMIN_ROUTING_KEY, message);

        await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() ->
                        assertThat(userRegistryRepository.existsByKeycloakId(userId.toString()))
                                .as("UserRegistry deve ser criado para Admin Event CREATE USER")
                                .isTrue());
    }

    private String buildKeycloakPayload(UUID userId, String eventType) {
        return buildKeycloakPayload(userId, eventType, null, null);
    }

    /**
     * Constrói um payload JSON simulando o formato real do plugin aznamier.
     * O realmId é sempre enviado como UUID (comportamento real do plugin),
     * exceto quando um valor específico é fornecido.
     */
    private String buildKeycloakPayload(UUID userId, String eventType, String realmId, String error) {
        String realmValue = realmId != null ? realmId : UUID.randomUUID().toString();
        String errorValue = error == null ? "null" : "\"" + error + "\"";
        return """
                {
                  "id": "%s",
                  "time": %d,
                  "type": "%s",
                  "realmId": "%s",
                  "clientId": "order-client",
                  "userId": "%s",
                  "ipAddress": "127.0.0.1",
                  "error": %s,
                  "details": {"username": "test-user"}
                }
                """.formatted(UUID.randomUUID(), System.currentTimeMillis(), eventType, realmValue, userId, errorValue);
    }
}
