package com.vibranium.walletservice.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.vibranium.walletservice.AbstractIntegrationTest;
import com.vibranium.walletservice.application.service.WalletService;
import com.vibranium.walletservice.config.RabbitMQConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.QueueInformation;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

/**
 * AT-2.2.2 — Testes de integração que verificam o roteamento para DLQ da fila
 * {@code wallet.keycloak.events} quando mensagens de registro com falha permanente
 * são publicadas.
 *
 * <h3>Problema (FASE RED)</h3>
 * <p>A fila {@code wallet.keycloak.events} não possui {@code x-dead-letter-exchange}
 * configurado. O listener ({@link com.vibranium.walletservice.infrastructure.messaging.KeycloakRabbitListener})
 * executa {@code channel.basicNack(deliveryTag, false, false)} em falhas permanentes,
 * mas sem DLX a mensagem é silenciosamente descartada pelo broker, impedindo auditoria
 * e recuperação de eventos de criação de wallet.</p>
 *
 * <h3>Solução (FASE GREEN)</h3>
 * <ul>
 *   <li>{@code walletKeycloakEventsQueue()} recebe {@code x-dead-letter-exchange=vibranium.dlq}
 *       e {@code x-dead-letter-routing-key=wallet.keycloak.events.dlq}.</li>
 *   <li>Fila DLQ durable {@code wallet.keycloak.events.dlq} declarada.</li>
 *   <li>Binding no exchange {@code vibranium.dlq} para a DLQ.</li>
 * </ul>
 *
 * <h3>Mecanismo de roteamento</h3>
 * <p>O {@code KeycloakRabbitListener} usa ACK manual e executa
 * {@code channel.basicNack(tag, false, false)} nas seguintes situações:
 * <ul>
 *   <li>Mensagem sem identificador do evento ({@code x-event-id/id/messageId}).</li>
 *   <li>Exceção inesperada no processamento (ex: falha de banco, estado inválido).</li>
 * </ul>
 * Com DLX configurado, o RabbitMQ encaminha automaticamente para {@code vibranium.dlq}
 * com a routing key {@code wallet.keycloak.events.dlq}, sem ação extra do código.</p>
 *
 * <h3>Estratégia de teste</h3>
 * <p>{@code WalletService} é mockado via {@code @MockBean} para lançar
 * {@link RuntimeException} em todo {@code createWallet}, simulando uma falha permanente
 * (poison pill). Isso aciona o fluxo de NACK do listener, que roteia para a DLQ.</p>
 *
 * <p>A verificação structural usa a <strong>RabbitMQ Management HTTP API</strong>
 * ({@code /api/queues/%2F/{queue}}) para inspecionar os argumentos da fila, pois
 * {@link org.springframework.amqp.core.QueueInformation} não expõe esses metadados.</p>
 *
 * <p><b>FASE RED:</b> ambos os testes falham porque:</p>
 * <ol>
 *   <li>{@code wallet.keycloak.events} não tem {@code x-dead-letter-exchange}.</li>
 *   <li>{@code wallet.keycloak.events.dlq} não está declarada no broker.</li>
 * </ol>
 * <p>Após a implementação em {@link RabbitMQConfig} (FASE GREEN), ambos passam.</p>
 */
// Esta classe usa @MockBean, que já force um novo ApplicationContext.
// O @DirtiesContext(AFTER_CLASS) herdado da AbstractIntegrationTest fecha o contexto
// após esta classe, garantindo que os listeners parem antes da próxima classe iniciar.
@DisplayName("AT-2.2.2 — DLX na fila wallet.keycloak.events: mensagens com falha roteadas para DLQ")
class KeycloakDlqIntegrationTest extends AbstractIntegrationTest {

    // -------------------------------------------------------------------------
    // Constantes de topologia (espelham o que será declarado em RabbitMQConfig)
    // -------------------------------------------------------------------------

    private static final String QUEUE_KEYCLOAK_EVENTS     = "wallet.keycloak.events";
    private static final String QUEUE_KEYCLOAK_EVENTS_DLQ = "wallet.keycloak.events.dlq";
    private static final String DLQ_EXCHANGE              = "vibranium.dlq";
    private static final String KEYCLOAK_EXCHANGE         = "amq.topic";
    private static final String KEYCLOAK_ROUTING_KEY      =
            RabbitMQConfig.RK_KEYCLOAK_REGISTER_SUCCESS;

    /**
     * Mock do WalletService para simular falha permanente no processamento.
     * O {@code @MockBean} força um ApplicationContext dedicado a esta classe,
     * garantindo que o mock não vaze para outros testes.
     */
    @MockBean
    private WalletService walletService;

    @BeforeEach
    void purgeDlqBeforeEach() {
        // Purga a DLQ antes de cada teste para garantir isolamento.
        // Em FASE RED a fila não existe — a tentativa falha silenciosamente.
        try {
            rabbitAdmin.purgeQueue(QUEUE_KEYCLOAK_EVENTS, false);
        } catch (Exception ignored) {
            // Pode não existir ainda em FASE RED
        }
        try {
            rabbitAdmin.purgeQueue(QUEUE_KEYCLOAK_EVENTS_DLQ, false);
        } catch (Exception ignored) {
            // Fila DLQ não existe até FASE GREEN
        }
    }

    // =========================================================================
    // TC-KC-DLQ-1 — Validação ESTRUTURAL: x-dead-letter-exchange na fila
    // =========================================================================

    /**
     * [RED → GREEN] Verifica que a fila {@code wallet.keycloak.events} foi declarada
     * com os argumentos de DLX obrigatórios via RabbitMQ Management HTTP API.
     *
     * <p><b>Falha em FASE RED:</b> fila existe sem argumentos de DLX — as assertions
     * sobre {@code x-dead-letter-exchange} e {@code x-dead-letter-routing-key} falham.</p>
     *
     * <p><b>Passa em FASE GREEN:</b> após adicionar os
     * {@code .withArgument()} no {@code QueueBuilder}, a Management API retorna
     * os argumentos corretamente.</p>
     */
    @Test
    @DisplayName("TC-KC-DLQ-1: fila wallet.keycloak.events deve ter x-dead-letter-exchange configurado")
    void keycloakEventsQueue_shouldHaveDeadLetterExchangeArguments() throws Exception {
        // Arrange — monta URI da Management HTTP API
        String queueEncoded = URLEncoder.encode(QUEUE_KEYCLOAK_EVENTS, StandardCharsets.UTF_8);
        String url = String.format("http://%s:%d/api/queues/%%2F/%s",
                RABBIT.getHost(),
                RABBIT.getMappedPort(15672),
                queueEncoded);

        // Credenciais básicas do container RabbitMQ
        String credentials = RABBIT.getAdminUsername() + ":" + RABBIT.getAdminPassword();
        String basicAuth = Base64.getEncoder().encodeToString(
                credentials.getBytes(StandardCharsets.UTF_8));

        // Act — chama a Management HTTP API via java.net.http.HttpClient (Java 11+)
        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Basic " + basicAuth)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

        // Assert — fila deve existir no broker (HTTP 200)
        assertThat(response.statusCode())
                .as("Management API deve retornar 200 para fila '%s'. " +
                    "Status %d indica que a fila não está declarada.",
                    QUEUE_KEYCLOAK_EVENTS, response.statusCode())
                .isEqualTo(200);

        // Parse do JSON de resposta para inspecionar argumentos da fila
        JsonNode queueJson = objectMapper.readTree(response.body());
        JsonNode arguments = queueJson.path("arguments");

        assertThat(arguments.isMissingNode() || arguments.isNull())
                .as("Fila '%s' deve ter argumentos configurados no broker", QUEUE_KEYCLOAK_EVENTS)
                .isFalse();

        // Verifica DLX configurado
        assertThat(arguments.path("x-dead-letter-exchange").asText(""))
                .as("x-dead-letter-exchange deve ser '%s' mas a fila não possui DLX. " +
                    "Adicione .withArgument(\"x-dead-letter-exchange\", \"%s\") no QueueBuilder.",
                    DLQ_EXCHANGE, DLQ_EXCHANGE)
                .isEqualTo(DLQ_EXCHANGE);

        // Verifica routing key DLQ configurada
        assertThat(arguments.path("x-dead-letter-routing-key").asText(""))
                .as("x-dead-letter-routing-key deve ser '%s' (DLQ de destino).",
                    QUEUE_KEYCLOAK_EVENTS_DLQ)
                .isEqualTo(QUEUE_KEYCLOAK_EVENTS_DLQ);
    }

    // =========================================================================
    // TC-KC-DLQ-2 — Validação COMPORTAMENTAL: mensagem com falha vai para DLQ
    // =========================================================================

    /**
     * [RED → GREEN] Verifica o fluxo completo de dead-letter para o evento Keycloak:
     * <ol>
     *   <li>Publica evento REGISTER válido com {@code x-event-id} na exchange do Keycloak.</li>
     *   <li>O {@code WalletService} mockado lança {@link RuntimeException} — simula poison pill.</li>
     *   <li>O listener executa {@code basicNack(tag, false, false)} — sem requeue.</li>
     *   <li>O RabbitMQ roteia para {@code vibranium.dlq} com routing key {@code wallet.keycloak.events.dlq}.</li>
     *   <li>A mensagem aparece em {@code wallet.keycloak.events.dlq}.</li>
     * </ol>
     *
     * <p><b>Falha em FASE RED:</b> fila sem DLX — mensagem é descartada silenciosamente;
     * a DLQ não existe ou permanece vazia; o {@code await()} expira.</p>
     *
     * <p><b>Passa em FASE GREEN:</b> DLX + DLQ declarados → mensagem roteada →
     * {@code messageCount >= 1} na DLQ e header {@code x-death} presente.</p>
     */
    @Test
    @DisplayName("TC-KC-DLQ-2: evento REGISTER com falha permanente deve ser roteado para wallet.keycloak.events.dlq")
    void keycloakRegisterEvent_whenWalletServiceThrows_shouldRouteMessageToDlq() throws Exception {
        // Arrange — configura o mock para simular falha permanente de infraestrutura.
        // WalletService lança RuntimeException em qualquer chamada de createWallet.
        // O listener captura a exceção e executa basicNack(tag, false, false).
        doThrow(new RuntimeException("Simulated permanent failure — wallet creation failed"))
                .when(walletService)
                .createWallet(any(UUID.class), any(UUID.class), anyString());

        // Monta payload no formato do plugin aznamier/keycloak-event-listener-rabbitmq
        UUID userId    = UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();
        String payload = """
                {
                  "id": "%s",
                  "time": %d,
                  "type": "REGISTER",
                  "realmId": "orderbook-realm",
                  "clientId": "orderbook-ui",
                  "userId": "%s",
                  "ipAddress": "127.0.0.1",
                  "details": { "username": "testuser@vibranium.io" }
                }
                """.formatted(eventId, System.currentTimeMillis(), userId);

        // Mensagem com x-event-id presente — o listener consegue garantir idempotência
        // e segue até createWallet, onde o mock força o RuntimeException → NACK
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setHeader("x-event-id", eventId);
        Message message = new Message(payload.getBytes(StandardCharsets.UTF_8), props);

        // Act — publica na exchange do Keycloak como o plugin faria
        rabbitTemplate.send(KEYCLOAK_EXCHANGE, KEYCLOAK_ROUTING_KEY, message);

        // Assert Parte 1: fila principal deve ficar vazia após o listener rejeitar
        await()
                .atMost(8, TimeUnit.SECONDS)
                .pollInterval(300, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    QueueInformation mainQueueInfo = rabbitAdmin.getQueueInfo(QUEUE_KEYCLOAK_EVENTS);
                    assertThat(mainQueueInfo)
                            .as("Fila '%s' deve existir", QUEUE_KEYCLOAK_EVENTS)
                            .isNotNull();
                    assertThat(mainQueueInfo.getMessageCount())
                            .as("Fila '%s' deve estar vazia após NACK sem requeue", QUEUE_KEYCLOAK_EVENTS)
                            .isZero();
                });

        // Assert Parte 2: DLQ deve conter a mensagem rejeitada
        // RED  → DLQ não existe → getQueueInfo retorna null → assertion falha
        // GREEN → DLQ declarada → mensagem roteada → messageCount >= 1
        await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(300, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    QueueInformation dlqInfo = rabbitAdmin.getQueueInfo(QUEUE_KEYCLOAK_EVENTS_DLQ);
                    assertThat(dlqInfo)
                            .as("DLQ '%s' deve estar declarada no broker. " +
                                "Em FASE RED a fila não existe — adicione declaração em RabbitMQConfig.",
                                QUEUE_KEYCLOAK_EVENTS_DLQ)
                            .isNotNull();
                    assertThat(dlqInfo.getMessageCount())
                            .as("DLQ '%s' deve conter pelo menos 1 mensagem rejeitada",
                                QUEUE_KEYCLOAK_EVENTS_DLQ)
                            .isGreaterThanOrEqualTo(1);
                });

        // Assert Parte 3: valida que a mensagem na DLQ possui o header 'x-death' do RabbitMQ
        // O broker adiciona este header automaticamente em todo dead-letter routing
        Message dlqMessage = rabbitTemplate.receive(QUEUE_KEYCLOAK_EVENTS_DLQ, 3_000 /* ms */);
        assertThat(dlqMessage)
                .as("Deve ser possível consumir a mensagem da DLQ '%s'", QUEUE_KEYCLOAK_EVENTS_DLQ)
                .isNotNull();

        Map<String, Object> headers = dlqMessage.getMessageProperties().getHeaders();
        assertThat(headers)
                .as("Mensagem na DLQ deve conter o header 'x-death' adicionado automaticamente pelo RabbitMQ")
                .containsKey("x-death");
    }
}
