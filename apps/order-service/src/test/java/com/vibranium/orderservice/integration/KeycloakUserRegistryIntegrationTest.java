package com.vibranium.orderservice.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibranium.orderservice.config.RabbitMQConfig;
import com.vibranium.orderservice.domain.model.UserRegistry;                     // [RED] classe ainda não existe
import com.vibranium.orderservice.domain.repository.UserRegistryRepository;       // [RED] classe ainda não existe
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Testes de integração: consumo do evento REGISTER do Keycloak via RabbitMQ.
 *
 * <p>Fluxo testado:</p>
 * <pre>
 *   Keycloak (plugin aznamier)
 *     → amq.topic  (routing key: KK.EVENT.CLIENT.orderbook-realm.REGISTER)
 *       → order.keycloak.user-register (queue)
 *         → KeycloakUserRabbitListener  [RED — não implementado]
 *           → UserRegistryRepository.save()
 *             → tb_user_registry (PostgreSQL)
 * </pre>
 *
 * <p><strong>Estado RED:</strong> As classes {@code KeycloakUserRabbitListener} e
 * {@code UserRegistryRepository} ainda não existem. Todos os testes falham
 * por falha de compilação ou por {@code NoSuchBeanDefinitionException} ao subir
 * o contexto. Isso é esperado na Fase RED do TDD.</p>
 */
@DisplayName("Keycloak User Registry — Integração RabbitMQ → PostgreSQL")
class KeycloakUserRegistryIntegrationTest extends AbstractIntegrationTest {

    /** Exchange do Keycloak (plugin aznamier publica aqui) */
    private static final String KC_EXCHANGE   = "amq.topic";
    /**
     * Routing key gerada pelo plugin aznamier para eventos de tipo REGISTER.
     * Formato: KK.EVENT.CLIENT.{realm}.{eventType}
     */
    private static final String KC_ROUTING_KEY = "KK.EVENT.CLIENT.orderbook-realm.REGISTER";

    @Autowired
    private UserRegistryRepository userRegistryRepository;   // [RED] não existe

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanDatabase() throws InterruptedException {
        // 1. Drena mensagens pendentes na fila (leftover de testes anteriores).
        //    Com acknowledge-mode=auto, mensagens já entregues ao consumer
        //    são auto-acked; mas mensagens ainda na fila seriam processadas
        //    DEPOIS do deleteAll, criando registros “stale” que quebram
        //    os asserts dos testes subsequentes.
        while (rabbitTemplate.receive(RabbitMQConfig.QUEUE_KEYCLOAK_REG, 200) != null) {
            // descarta mensagens residuais
        }
        // 2. Aguarda consumidores em-voo (já com a mensagem, antes do ack) concluírem.
        Thread.sleep(300);
        // 3. Limpa o banco com estado consistente.
        userRegistryRepository.deleteAll();
    }

    // =========================================================================
    // Cenário 1 — Happy Path: evento REGISTER chega e usuário é salvo
    // =========================================================================

    @Test
    @DisplayName("Dado evento REGISTER válido, deve salvar userId em tb_user_registry")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void whenValidRegisterEventArrives_thenUserIsSavedInRegistry() throws Exception {
        // --- ARRANGE ---
        UUID userId = UUID.randomUUID();
        String payload = buildKeycloakRegisterEvent(userId, "REGISTER");

        // --- ACT ---
        // Simula o Keycloak (plugin aznamier) publicando na exchange amq.topic
        rabbitTemplate.convertAndSend(KC_EXCHANGE, KC_ROUTING_KEY, payload);

        // --- ASSERT ---
        // O listener deve consumir e salvar assincronamente; Awaitility aguarda até 8s
        await().atMost(8, SECONDS)
                .pollInterval(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    Optional<UserRegistry> found = userRegistryRepository.findByKeycloakId(userId.toString());
                    assertThat(found).isPresent();
                    assertThat(found.get().getKeycloakId()).isEqualTo(userId.toString());
                    assertThat(found.get().getRegisteredAt()).isNotNull();
                });
    }

    // =========================================================================
    // Cenário 2 — Idempotência: evento duplicado não cria registro duplicado
    // =========================================================================

    @Test
    @DisplayName("Dado evento REGISTER duplicado, deve ser idempotente (upsert, não duplicata)")
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void whenDuplicateRegisterEventArrives_thenIdempotentSave() throws Exception {
        // --- ARRANGE ---
        UUID userId = UUID.randomUUID();
        String payload = buildKeycloakRegisterEvent(userId, "REGISTER");

        // --- ACT --- Publica 3 vezes o mesmo userId
        for (int i = 0; i < 3; i++) {
            rabbitTemplate.convertAndSend(KC_EXCHANGE, KC_ROUTING_KEY, payload);
        }

        // --- ASSERT --- Deve existir EXATAMENTE 1 registro para o userId
        await().atMost(10, SECONDS)
                .untilAsserted(() -> {
                    long count = userRegistryRepository.countByKeycloakId(userId.toString());
                    assertThat(count)
                            .as("Deveria existir exatamente 1 registro para userId=%s", userId)
                            .isEqualTo(1L);
                });
    }

    // =========================================================================
    // Cenário 3 — Evento de tipo diferente (LOGIN) não deve ser processado
    // =========================================================================

    @Test
    @DisplayName("Dado evento Keycloak tipo LOGIN (não REGISTER), não deve criar registro")
    @Timeout(value = 8, unit = TimeUnit.SECONDS)
    void whenLoginEventArrives_thenUserIsNotSavedInRegistry() throws Exception {
        // --- ARRANGE ---
        UUID userId = UUID.randomUUID();
        String loginRoutingKey = "KK.EVENT.CLIENT.orderbook-realm.LOGIN";
        String payload = buildKeycloakRegisterEvent(userId, "LOGIN");

        // --- ACT ---
        rabbitTemplate.convertAndSend(KC_EXCHANGE, loginRoutingKey, payload);

        // --- ASSERT --- Após 3s, o usuário NÃO deve aparecer no registry
        // (a queue está binding apenas para REGISTER)
        Thread.sleep(3_000);
        assertThat(userRegistryRepository.findByKeycloakId(userId.toString()))
                .as("Evento LOGIN não deve criar entrada no tb_user_registry")
                .isEmpty();
    }

    // =========================================================================
    // Cenário 4 — Payload malformado vai para Dead Letter Queue
    // =========================================================================

    @Test
    @DisplayName("Dado payload JSON malformado, mensagem deve ser roteada para DLQ")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void whenMalformedJsonPayloadArrives_thenMessageGoesToDeadLetterQueue() throws Exception {
        // --- ARRANGE ---
        String malformedPayload = "{ INVALID JSON :: NOT_PARSEABLE }";
        MessageProperties props = new MessageProperties();
        props.setContentType("application/json");
        Message message = new Message(malformedPayload.getBytes(), props);

        // --- ACT ---
        rabbitTemplate.send(KC_EXCHANGE, KC_ROUTING_KEY, message);

        // --- ASSERT ---
        // Após 5s a DLQ deve ter recebido a mensagem (após 3 tentativas de retry)
        await().atMost(8, SECONDS)
                .untilAsserted(() -> {
                    // Verifica que a mensagem chegou na DLQ via polling
                    Message dlq = rabbitTemplate.receive("order.dead-letter", 1000);
                    assertThat(dlq)
                            .as("Mensagem malformada deve estar na DLQ após falha no listener")
                            .isNotNull();
                });
    }

    // =========================================================================
    // Cenário 5 — Evento sem userId não deve criar registro + não deve propagar exceção
    // =========================================================================

    @Test
    @DisplayName("Dado evento REGISTER sem userId, deve rejeitar silenciosamente sem salvar")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void whenRegisterEventMissingUserId_thenNoUserSavedAndNoException() throws Exception {
        // --- ARRANGE ---
        String payloadSemUserId = """
                {
                    "id": "%s",
                    "time": %d,
                    "type": "REGISTER",
                    "realmId": "orderbook-realm",
                    "clientId": "order-client",
                    "userId": null
                }
                """.formatted(UUID.randomUUID(), System.currentTimeMillis());

        // --- ACT --- Não lança exceção ou para o context (expectativas negativas)
        rabbitTemplate.convertAndSend(KC_EXCHANGE, KC_ROUTING_KEY, payloadSemUserId);

        // --- ASSERT --- Após 3s, nenhum registro deve ter sido criado
        Thread.sleep(3_000);
        assertThat(userRegistryRepository.count())
                .as("Registro com userId null não deve ser salvo")
                .isZero();
    }

    // =========================================================================
    // Cenário 6 — Volume: 100 usuários diferentes registrando consecutivamente
    // =========================================================================

    @Test
    @DisplayName("Dado 100 eventos REGISTER de usuários distintos, todos devem ser persistidos")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void whenHundredDistinctUsersRegister_thenAllAreSaved() throws Exception {
        // --- ARRANGE ---
        int count = 100;
        UUID[] userIds = new UUID[count];
        for (int i = 0; i < count; i++) {
            userIds[i] = UUID.randomUUID();
        }

        // --- ACT ---
        for (UUID uid : userIds) {
            rabbitTemplate.convertAndSend(KC_EXCHANGE, KC_ROUTING_KEY,
                    buildKeycloakRegisterEvent(uid, "REGISTER"));
        }

        // --- ASSERT ---
        await().atMost(25, SECONDS)
                .untilAsserted(() ->
                        assertThat(userRegistryRepository.count())
                                .as("Todos os 100 usuários devem estar no registry")
                                .isEqualTo(100L));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Constrói o payload JSON no formato do plugin keycloak-to-rabbitmq (aznamier).
     * Ref: https://github.com/aznamier/keycloak-event-listener-rabbitmq
     */
    private String buildKeycloakRegisterEvent(UUID userId, String type) {
        return """
                {
                    "id": "%s",
                    "time": %d,
                    "type": "%s",
                    "realmId": "orderbook-realm",
                    "clientId": "order-client",
                    "userId": "%s",
                    "sessionId": "session-%s",
                    "ipAddress": "127.0.0.1",
                    "details": {
                        "register_method": "form",
                        "email": "user-%s@vibranium.com",
                        "username": "user-%s"
                    }
                }
                """.formatted(
                UUID.randomUUID(),
                System.currentTimeMillis(),
                type,
                userId,
                UUID.randomUUID().toString().substring(0, 8),
                userId.toString().substring(0, 8),
                userId.toString().substring(0, 8)
        );
    }
}
