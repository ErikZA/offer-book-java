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
 * (como o plugin Keycloak realmente faz) e verifica a criação no
 * {@code UserRegistryRepository}.</p>
 */
@DisplayName("[Integration] KeycloakEventConsumer — Registro de usuários via Keycloak")
class KeycloakEventConsumerIntegrationTest extends AbstractIntegrationTest {

    private static final String KEYCLOAK_EXCHANGE = "amq.topic";
    private static final String KEYCLOAK_ROUTING_KEY = RabbitMQConfig.RK_KEYCLOAK_REGISTER;

    @Autowired
    private UserRegistryRepository userRegistryRepository;

    @BeforeEach
    void cleanup() {
        userRegistryRepository.deleteAll();
    }

    // =========================================================================
    // Cenário principal: bytes brutos JSON (como o plugin Keycloak envia)
    // =========================================================================

    @Test
    @DisplayName("Deve registrar usuário quando Keycloak publica evento REGISTER com bytes JSON brutos")
    void shouldRegisterUserFromRawJsonBytes() {
        // Arrange — simula o payload do plugin aznamier como bytes brutos
        UUID userId = UUID.randomUUID();
        String jsonPayload = buildKeycloakPayload(userId, "REGISTER");

        // Act — envia como bytes raw (sem Jackson2JsonMessageConverter),
        // exatamente como o plugin Keycloak faz
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        Message message = new Message(jsonPayload.getBytes(StandardCharsets.UTF_8), props);
        rabbitTemplate.send(KEYCLOAK_EXCHANGE, KEYCLOAK_ROUTING_KEY, message);

        // Assert — aguarda criação do UserRegistry
        await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() ->
                        assertThat(userRegistryRepository.existsByKeycloakId(userId.toString()))
                                .as("UserRegistry deve ser criado para o userId do evento REGISTER")
                                .isTrue());
    }

    // =========================================================================
    // Filtro de tipo de evento
    // =========================================================================

    @Test
    @DisplayName("Deve ignorar eventos que não sejam REGISTER (ex: LOGIN)")
    void shouldIgnoreNonRegisterEvents() {
        UUID userId = UUID.randomUUID();
        String jsonPayload = buildKeycloakPayload(userId, "LOGIN");

        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        Message message = new Message(jsonPayload.getBytes(StandardCharsets.UTF_8), props);
        rabbitTemplate.send(KEYCLOAK_EXCHANGE, KEYCLOAK_ROUTING_KEY, message);

        // Aguarda processamento da mensagem, mas usuário NÃO deve ser criado
        await()
                .during(2, TimeUnit.SECONDS)
                .atMost(3, TimeUnit.SECONDS)
                .untilAsserted(() ->
                        assertThat(userRegistryRepository.existsByKeycloakId(userId.toString()))
                                .isFalse());
    }

    // =========================================================================
    // Idempotência
    // =========================================================================

    @Test
    @DisplayName("Evento REGISTER duplicado não deve criar registro duplicado (idempotência)")
    void shouldBeIdempotentForDuplicateRegisterEvents() {
        UUID userId = UUID.randomUUID();
        String jsonPayload = buildKeycloakPayload(userId, "REGISTER");

        // Envia 2x o mesmo evento
        for (int i = 0; i < 2; i++) {
            MessageProperties props = new MessageProperties();
            props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            Message message = new Message(jsonPayload.getBytes(StandardCharsets.UTF_8), props);
            rabbitTemplate.send(KEYCLOAK_EXCHANGE, KEYCLOAK_ROUTING_KEY, message);
        }

        // Espera processamento de ambas as mensagens
        await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() ->
                        assertThat(userRegistryRepository.existsByKeycloakId(userId.toString()))
                                .isTrue());

        // Verifica que só há 1 registro (não duplicado)
        long count = userRegistryRepository.findAll().stream()
                .filter(r -> r.getKeycloakId().equals(userId.toString()))
                .count();
        assertThat(count).isEqualTo(1);
    }

    // =========================================================================
    // Helper
    // =========================================================================

    private String buildKeycloakPayload(UUID userId, String eventType) {
        return """
                {
                  "id": "%s",
                  "time": %d,
                  "type": "%s",
                  "realmId": "orderbook-realm",
                  "clientId": "order-client",
                  "userId": "%s",
                  "ipAddress": "127.0.0.1",
                  "details": {"username": "test-user"}
                }
                """.formatted(UUID.randomUUID(), System.currentTimeMillis(), eventType, userId);
    }
}
