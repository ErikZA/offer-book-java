package com.vibranium.walletservice.integration;

import com.vibranium.walletservice.AbstractIntegrationTest;
import com.vibranium.walletservice.domain.repository.WalletRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * FASE RED — Testa o fluxo de criação de carteira disparado pelo Keycloak.
 *
 * <p>O plugin {@code aznamier/keycloak-event-listener-rabbitmq} publica um JSON
 * na exchange do RabbitMQ quando um usuário é registrado. Este teste simula
 * esse payload e valida que o {@code KeycloakRabbitListener} cria uma linha
 * na tabela {@code tb_wallet} com todos os saldos zerados.</p>
 *
 * <p><b>RED:</b> Todos os testes falharão até que o listener e o serviço
 * estejam implementados (Fase Green).</p>
 */
@DisplayName("[RED] KeycloakUserCreation - Integração completa com RabbitMQ e PostgreSQL")
class KeycloakUserCreationIntegrationTest extends AbstractIntegrationTest {

    /** Exchange configurada para receber eventos do Keycloak (plugin aznamier). */
    private static final String KEYCLOAK_EXCHANGE = "keycloak.events";
    private static final String KEYCLOAK_ROUTING_KEY = "KK.EVENT.CLIENT.vibranium-realm.SUCCESS.CLIENT.REGISTER";

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // -------------------------------------------------------------------------
    // Happy Path
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Deve criar carteira com saldos zerados quando Keycloak publica evento REGISTER")
    void shouldCreateWalletWithZeroBalanceOnKeycloakRegisterEvent() throws Exception {
        // Arrange — monta o payload do plugin aznamier
        UUID userId = UUID.randomUUID();
        String keycloakPayload = buildKeycloakRegisterPayload(userId, "REGISTER");

        // Act — publica na exchange do Keycloak como o plugin faria
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setMessageId(UUID.randomUUID().toString());
        Message message = new Message(keycloakPayload.getBytes(StandardCharsets.UTF_8), props);
        rabbitTemplate.send(KEYCLOAK_EXCHANGE, KEYCLOAK_ROUTING_KEY, message);

        // Assert — Awaitility aguarda o processamento assíncrono (até 10s)
        await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    var wallet = walletRepository.findByUserId(userId);
                    assertThat(wallet).isPresent();

                    var w = wallet.get();
                    assertThat(w.getBrlAvailable()).isEqualByComparingTo("0");
                    assertThat(w.getBrlLocked()).isEqualByComparingTo("0");
                    assertThat(w.getVibAvailable()).isEqualByComparingTo("0");
                    assertThat(w.getVibLocked()).isEqualByComparingTo("0");
                });
    }

    @Test
    @DisplayName("Deve gerar WalletCreatedEvent no Outbox após criar carteira")
    void shouldPersistWalletCreatedEventInOutbox() throws Exception {
        // Arrange
        UUID userId = UUID.randomUUID();
        String payload = buildKeycloakRegisterPayload(userId, "REGISTER");

        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setMessageId(UUID.randomUUID().toString());
        Message message = new Message(payload.getBytes(StandardCharsets.UTF_8), props);

        // Act
        rabbitTemplate.send(KEYCLOAK_EXCHANGE, KEYCLOAK_ROUTING_KEY, message);

        // Assert — verifica que o Outbox recebeu o evento de domínio
        await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    Integer count = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM outbox_message WHERE event_type = 'WalletCreatedEvent' AND processed = false",
                            Integer.class
                    );
                    assertThat(count).isGreaterThan(0);
                });
    }

    // -------------------------------------------------------------------------
    // Falha / Edge Cases
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Não deve criar carteira duplicada quando evento REGISTER é recebido duas vezes")
    void shouldNotCreateDuplicateWalletOnDuplicateKeycloakEvent() throws Exception {
        // Arrange
        UUID userId = UUID.randomUUID();
        String messageId = UUID.randomUUID().toString();
        String payload = buildKeycloakRegisterPayload(userId, "REGISTER");

        // Act — envia o mesmo messageId duas vezes (simula at-least-once)
        for (int i = 0; i < 2; i++) {
            MessageProperties props = new MessageProperties();
            props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            props.setMessageId(messageId); // mesmo ID!
            Message message = new Message(payload.getBytes(StandardCharsets.UTF_8), props);
            rabbitTemplate.send(KEYCLOAK_EXCHANGE, KEYCLOAK_ROUTING_KEY, message);
        }

        // Assert — apenas 1 carteira deve existir após a idempotência
        await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    long count = walletRepository.countByUserId(userId);
                    assertThat(count).isEqualTo(1);
                });
    }

    @Test
    @DisplayName("Deve ignorar (ACK sem processar) eventos que não são tipo REGISTER")
    void shouldIgnoreNonRegisterKeycloakEvents() throws Exception {
        // Arrange — evento de LOGIN não deve criar carteira
        UUID userId = UUID.randomUUID();
        String payload = buildKeycloakRegisterPayload(userId, "LOGIN");

        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setMessageId(UUID.randomUUID().toString());
        Message message = new Message(payload.getBytes(StandardCharsets.UTF_8), props);

        // Act
        rabbitTemplate.send(KEYCLOAK_EXCHANGE, KEYCLOAK_ROUTING_KEY, message);

        // Assert — Nenhuma carteira deve ser criada para este userId
        await()
                .during(3, TimeUnit.SECONDS)
                .atMost(4, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() ->
                        assertThat(walletRepository.findByUserId(userId)).isEmpty()
                );
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Monta o payload JSON no formato do plugin {@code aznamier/keycloak-event-listener-rabbitmq}.
     * O campo {@code type} distingue REGISTER de LOGIN, LOGOUT, etc.
     */
    private String buildKeycloakRegisterPayload(UUID userId, String type) {
        return """
                {
                  "id": "%s",
                  "time": %d,
                  "type": "%s",
                  "realmId": "vibranium-realm",
                  "clientId": "vibranium-app",
                  "userId": "%s",
                  "ipAddress": "127.0.0.1",
                  "details": {
                    "username": "testuser@vibranium.io"
                  }
                }
                """.formatted(UUID.randomUUID(), System.currentTimeMillis(), type, userId);
    }
}
