package com.vibranium.orderservice.integration;

import com.vibranium.orderservice.config.RabbitMQConfig;
import com.vibranium.orderservice.domain.model.EventStoreEntry;
import com.vibranium.orderservice.domain.model.UserRegistry;
import com.vibranium.orderservice.domain.repository.EventStoreRepository;
import com.vibranium.orderservice.domain.repository.UserRegistryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Teste específico para verificar a persistência no Event Store junto com o registro do usuário.
 */
@DisplayName("Event Store Persistence — Integração Keycloak → Event Store")
class EventStorePersistenceIntegrationTest extends AbstractIntegrationTest {

    private static final String KC_EXCHANGE   = "amq.topic";
    private static final String KC_ROUTING_KEY = RabbitMQConfig.RK_KEYCLOAK_REGISTER_SUCCESS;

    @Autowired
    private UserRegistryRepository userRegistryRepository;

    @Autowired
    private EventStoreRepository eventStoreRepository;

    @BeforeEach
    void cleanDatabase() {
        userRegistryRepository.deleteAll();
        eventStoreRepository.deleteAll();
    }

    @Test
    @DisplayName("Deve salvar usuário e persistir o evento bruto no Event Store")
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void whenRegisterEventArrives_thenUserIsSavedAndEventIsStored() throws Exception {
        // --- ARRANGE ---
        UUID userId = UUID.randomUUID();
        String payload = """
                {
                    "id": "%s",
                    "time": %d,
                    "type": "REGISTER",
                    "realmId": "orderbook-realm",
                    "clientId": "order-client",
                    "userId": "%s"
                }
                """.formatted(UUID.randomUUID(), System.currentTimeMillis(), userId);

        // --- ACT ---
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        Message message = new Message(payload.getBytes(StandardCharsets.UTF_8), props);
        rabbitTemplate.send(KC_EXCHANGE, KC_ROUTING_KEY, message);

        // --- ASSERT ---
        await().atMost(10, SECONDS)
                .pollInterval(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    // 1. Verifica se o usuário foi registrado
                    Optional<UserRegistry> user = userRegistryRepository.findByKeycloakId(userId.toString());
                    assertThat(user).isPresent();

                    // 2. Verifica se o evento foi persistido no Event Store
                    var events = eventStoreRepository.findByAggregateIdOrderBySequenceIdAsc(userId.toString());
                    assertThat(events)
                            .as("Deve existir 1 evento no event store para o userId")
                            .hasSize(1);
                    
                    EventStoreEntry entry = events.get(0);
                    assertThat(entry.getAggregateType()).isEqualTo("User");
                    assertThat(entry.getEventType()).isEqualTo("REGISTER");
                    assertThat(entry.getPayload()).contains(userId.toString());
                    assertThat(entry.getOccurredOn()).isNotNull();
                });
    }
}
