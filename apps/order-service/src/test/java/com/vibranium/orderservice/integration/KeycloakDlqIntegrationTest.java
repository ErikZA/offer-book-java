package com.vibranium.orderservice.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibranium.orderservice.config.RabbitMQConfig;
import com.vibranium.orderservice.domain.repository.UserRegistryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

/**
 * Testes de integração que verificam o roteamento para DLQ da fila
 * {@code order.keycloak.events} quando mensagens não processáveis são recebidas.
 *
 * <h3>Estratégia</h3>
 * <p>{@code UserRegistryRepository} é mockado via {@code @MockBean} para lançar
 * {@link RuntimeException} em {@code existsByKeycloakId}, simulando uma falha
 * permanente de infraestrutura. Isso aciona o fluxo de NACK do listener, que
 * roteia para a DLQ {@code order.keycloak.events.dlq}.</p>
 *
 * <h3>TC-ORDER-DLQ-1</h3>
 * <p>Validação ESTRUTURAL: verifica que a fila principal foi declarada
 * com {@code x-dead-letter-exchange=vibranium.dlq}.</p>
 *
 * <h3>TC-ORDER-DLQ-2</h3>
 * <p>Validação COMPORTAMENTAL: mensagem REGISTER com falha permanente deve
 * aparecer na DLQ com header {@code x-death}.</p>
 */
@DisplayName("DLQ — order.keycloak.events: mensagens com falha roteadas para DLQ")
class KeycloakDlqIntegrationTest extends AbstractIntegrationTest {

    private static final String QUEUE_KEYCLOAK_EVENTS     = RabbitMQConfig.QUEUE_KEYCLOAK_EVENTS;
    private static final String QUEUE_KEYCLOAK_EVENTS_DLQ = RabbitMQConfig.QUEUE_KEYCLOAK_EVENTS_DLQ;
    private static final String DLQ_EXCHANGE              = RabbitMQConfig.DLQ_EXCHANGE;
    private static final String KEYCLOAK_EXCHANGE         = "amq.topic";
    private static final String KEYCLOAK_ROUTING_KEY      = RabbitMQConfig.RK_KEYCLOAK_REGISTER_SUCCESS;

    @MockBean
    private UserRegistryRepository userRegistryRepository;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void purgeDlqBeforeEach() {
        try { rabbitAdmin.purgeQueue(QUEUE_KEYCLOAK_EVENTS, false); }     catch (Exception ignored) {}
        try { rabbitAdmin.purgeQueue(QUEUE_KEYCLOAK_EVENTS_DLQ, false); } catch (Exception ignored) {}
    }

    // =========================================================================
    // TC-ORDER-DLQ-1 — Validação ESTRUTURAL: x-dead-letter-exchange na fila
    // =========================================================================

    @Test
    @DisplayName("TC-ORDER-DLQ-1: fila order.keycloak.events deve ter x-dead-letter-exchange configurado")
    void keycloakEventsQueue_shouldHaveDeadLetterExchangeArguments() throws Exception {
        String queueEncoded = URLEncoder.encode(QUEUE_KEYCLOAK_EVENTS, StandardCharsets.UTF_8);
        String url = String.format("http://%s:%d/api/queues/%%2F/%s",
                RABBITMQ.getHost(),
                RABBITMQ.getMappedPort(15672),
                queueEncoded);

        String credentials = RABBITMQ.getAdminUsername() + ":" + RABBITMQ.getAdminPassword();
        String basicAuth = Base64.getEncoder().encodeToString(
                credentials.getBytes(StandardCharsets.UTF_8));

        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Basic " + basicAuth)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode())
                .as("Management API deve retornar 200 para fila '%s'", QUEUE_KEYCLOAK_EVENTS)
                .isEqualTo(200);

        JsonNode queueJson = objectMapper.readTree(response.body());
        JsonNode arguments = queueJson.path("arguments");

        assertThat(arguments.isMissingNode() || arguments.isNull())
                .as("Fila '%s' deve ter argumentos configurados", QUEUE_KEYCLOAK_EVENTS)
                .isFalse();

        assertThat(arguments.path("x-dead-letter-exchange").asText(""))
                .as("x-dead-letter-exchange deve ser '%s'", DLQ_EXCHANGE)
                .isEqualTo(DLQ_EXCHANGE);

        assertThat(arguments.path("x-dead-letter-routing-key").asText(""))
                .as("x-dead-letter-routing-key deve ser '%s'", QUEUE_KEYCLOAK_EVENTS_DLQ)
                .isEqualTo(QUEUE_KEYCLOAK_EVENTS_DLQ);
    }

    // =========================================================================
    // TC-ORDER-DLQ-2 — Validação COMPORTAMENTAL: NACK sem requeue → DLQ
    // =========================================================================

    @Test
    @DisplayName("TC-ORDER-DLQ-2: evento REGISTER com falha permanente deve ser roteado para order.keycloak.events.dlq")
    void keycloakRegisterEvent_whenRepositoryThrows_shouldRouteMessageToDlq() throws Exception {
        // Simula falha permanente de infraestrutura no repositório
        doThrow(new RuntimeException("Simulated DB failure"))
                .when(userRegistryRepository)
                .existsByKeycloakId(anyString());

        UUID userId    = UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();
        String payload = """
                {
                  "id": "%s",
                  "time": %d,
                  "type": "REGISTER",
                  "realmId": "orderbook-realm",
                  "clientId": "order-client",
                  "userId": "%s",
                  "ipAddress": "127.0.0.1",
                  "details": { "username": "testuser@vibranium.io" }
                }
                """.formatted(eventId, System.currentTimeMillis(), userId);

        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        Message message = new Message(payload.getBytes(StandardCharsets.UTF_8), props);
        rabbitTemplate.send(KEYCLOAK_EXCHANGE, KEYCLOAK_ROUTING_KEY, message);

        // Assert 1: fila principal deve esvaziar após NACK
        await()
                .atMost(8, TimeUnit.SECONDS)
                .pollInterval(300, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    QueueInformation mainQueueInfo = rabbitAdmin.getQueueInfo(QUEUE_KEYCLOAK_EVENTS);
                    assertThat(mainQueueInfo).as("Fila '%s' deve existir", QUEUE_KEYCLOAK_EVENTS).isNotNull();
                    assertThat(mainQueueInfo.getMessageCount())
                            .as("Fila principal deve estar vazia após NACK sem requeue")
                            .isZero();
                });

        // Assert 2: DLQ deve conter a mensagem rejeitada
        await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(300, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    QueueInformation dlqInfo = rabbitAdmin.getQueueInfo(QUEUE_KEYCLOAK_EVENTS_DLQ);
                    assertThat(dlqInfo)
                            .as("DLQ '%s' deve estar declarada no broker", QUEUE_KEYCLOAK_EVENTS_DLQ)
                            .isNotNull();
                    assertThat(dlqInfo.getMessageCount())
                            .as("DLQ deve conter pelo menos 1 mensagem rejeitada")
                            .isGreaterThanOrEqualTo(1);
                });

        // Assert 3: header x-death deve estar presente na mensagem na DLQ
        Message dlqMessage = rabbitTemplate.receive(QUEUE_KEYCLOAK_EVENTS_DLQ, 3_000);
        assertThat(dlqMessage)
                .as("Deve ser possível consumir a mensagem da DLQ '%s'", QUEUE_KEYCLOAK_EVENTS_DLQ)
                .isNotNull();

        Map<String, Object> headers = dlqMessage.getMessageProperties().getHeaders();
        assertThat(headers)
                .as("Mensagem na DLQ deve conter o header 'x-death' adicionado pelo RabbitMQ")
                .containsKey("x-death");
    }
}
