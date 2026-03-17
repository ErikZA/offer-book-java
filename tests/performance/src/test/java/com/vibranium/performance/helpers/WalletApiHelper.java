package com.vibranium.performance.helpers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Helper para operações REST na Wallet Service API.
 *
 * <p>Utilizado na fase de setup (before hook) da simulação para:</p>
 * <ul>
 *   <li>Aguardar criação automática de carteiras (Keycloak → RabbitMQ → wallet-service)</li>
 *   <li>Depositar saldo inicial via PATCH /balance</li>
 *   <li>Consultar saldos para validação</li>
 * </ul>
 *
 * <p>Suporta múltiplas instâncias de wallet-service com distribuição round-robin.</p>
 *
 * <p>Inclui método para publicar eventos REGISTER via RabbitMQ Management API,
 * necessário porque usuários criados via Keycloak Admin API <strong>não</strong>
 * geram eventos {@code KK.EVENT.CLIENT.*.REGISTER} automaticamente.</p>
 */
public final class WalletApiHelper {

    private static final Logger logger = LoggerFactory.getLogger(WalletApiHelper.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private final String[] walletServiceUrls;
    private final AtomicInteger urlCounter = new AtomicInteger(0);

    private final String rabbitmqManagementUrl;
    private final String rabbitmqAuthHeader;

    public WalletApiHelper(String walletServiceUrl) {
        this(new String[]{ walletServiceUrl });
    }

    public WalletApiHelper(String[] walletServiceUrls) {
        this(walletServiceUrls,
             env("RABBITMQ_MANAGEMENT_URL", "http://rabbitmq-1:15672"),
             env("RABBITMQ_USER", "guest"),
             env("RABBITMQ_PASS", "guest"));
    }

    public WalletApiHelper(String[] walletServiceUrls,
                           String rabbitmqManagementUrl,
                           String rabbitmqUser,
                           String rabbitmqPass) {
        if (walletServiceUrls == null || walletServiceUrls.length == 0) {
            throw new IllegalArgumentException("At least one wallet service URL is required");
        }
        this.walletServiceUrls = walletServiceUrls.clone();
        this.rabbitmqManagementUrl = rabbitmqManagementUrl;
        this.rabbitmqAuthHeader = "Basic " + java.util.Base64.getEncoder()
                .encodeToString((rabbitmqUser + ":" + rabbitmqPass).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        logger.info("WalletApiHelper initialized with {} wallet instances and RabbitMQ at {}",
                this.walletServiceUrls.length, rabbitmqManagementUrl);
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }

    /**
     * Seleciona a próxima URL via round-robin para distribuição uniforme.
     */
    private String nextUrl() {
        int idx = Math.abs(urlCounter.getAndIncrement() % walletServiceUrls.length);
        return walletServiceUrls[idx];
    }

    /**
     * Publica um evento REGISTER na exchange {@code amq.topic} via RabbitMQ Management API.
     *
     * <p>Necessário porque usuários criados via Keycloak Admin API não geram eventos
     * {@code KK.EVENT.CLIENT.<realm>.<SUCCESS|ERROR>.<clientId>.REGISTER}. Este método simula o payload publicado
     * pelo plugin {@code aznamier/keycloak-event-listener-rabbitmq}.</p>
     *
     * @param user  Usuário de teste (deve ter keycloakId preenchido).
     * @param realm Nome do realm Keycloak.
     */
    public void publishRegisterEvent(TestUser user, String realm) {
        String eventId = UUID.randomUUID().toString();
        String clientId = "order-client";
        String routingKey = "KK.EVENT.CLIENT." + realm + ".SUCCESS." + clientId + ".REGISTER";

        // Payload no formato exato do plugin aznamier
        String eventPayload = String.format("""
                {
                  "id": "%s",
                  "time": %d,
                  "type": "REGISTER",
                  "realmId": "%s",
                  "clientId": "%s",
                  "userId": "%s",
                  "ipAddress": "127.0.0.1",
                  "details": {
                    "username": "%s",
                    "email": "%s@vibranium-perf.com"
                  }
                }""", eventId, System.currentTimeMillis(), realm, clientId, user.getKeycloakId(),
                user.getUsername(), user.getUsername());

        // Envelope da Management API: /api/exchanges/{vhost}/{exchange}/publish
        // payload_encoding="string" faz o Management API usar o valor literal como body.
        // O payload DEVE ser o JSON cru do evento (não re-serializado via writeValueAsString),
        // senão o body chega double-encoded ("{\"type\":\"REGISTER\",...}") e o consumer
        // falha com "no String-argument constructor/factory method".
        ObjectNode publishNode = MAPPER.createObjectNode();
        ObjectNode propsNode = publishNode.putObject("properties");
        propsNode.put("content_type", "application/json");
        ObjectNode headersNode = propsNode.putObject("headers");
        headersNode.put("x-event-id", eventId);
        headersNode.put("__TypeId__", "com.github.aznamier.keycloak.event.provider.EventClientNotificationMqMsg");
        publishNode.put("routing_key", routingKey);
        publishNode.put("payload", eventPayload);
        publishNode.put("payload_encoding", "string");
        String publishBody = publishNode.toString();

        String url = rabbitmqManagementUrl + "/api/exchanges/%2F/amq.topic/publish";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", rabbitmqAuthHeader)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(publishBody))
                .timeout(Duration.ofSeconds(10))
                .build();

        try {
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("Failed to publish REGISTER event for " + user.getUsername()
                        + " (HTTP " + response.statusCode() + "): " + response.body());
            }
            logger.info("Published REGISTER event for user {} (eventId={}, routingKey={})",
                    user.getUsername(), eventId, routingKey);
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to publish REGISTER event for " + user.getUsername(), e);
        }
    }

    /**
     * Aguarda a criação da carteira por polling no endpoint GET /api/v1/wallets/{userId}.
     *
     * <p>A carteira é criada automaticamente quando o Keycloak envia o evento REGISTER
     * via RabbitMQ para o wallet-service. Este método realiza polling até a carteira
     * estar disponível.</p>
     *
     * @param user       Usuário de teste (deve ter keycloakId e token válidos).
     * @param maxRetries Número máximo de tentativas.
     * @param intervalMs Intervalo entre tentativas em milissegundos.
     * @return UUID da carteira criada.
     */
    public UUID waitForWallet(TestUser user, int maxRetries, int intervalMs) {
        for (int i = 0; i < maxRetries; i++) {
            try {
                String url = nextUrl();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url + "/api/v1/wallets/" + user.getKeycloakId()))
                        .header("Authorization", user.getBearerToken())
                        .header("Accept", "application/json")
                        .GET()
                        .timeout(Duration.ofSeconds(15))
                        .build();

                HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    JsonNode json = MAPPER.readTree(response.body());
                    UUID walletId = UUID.fromString(json.get("walletId").asText());
                    logger.info("Wallet found for user {}: walletId={}", user.getUsername(), walletId);
                    return walletId;
                }

                logger.info("Wallet not ready for user {} (attempt {}/{}), waiting {}ms...",
                        user.getUsername(), i + 1, maxRetries, intervalMs);
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for wallet of " + user.getUsername(), e);
            } catch (IOException e) {
                logger.warn("Error polling wallet for user {} (attempt {}): {}",
                        user.getUsername(), i + 1, e.getMessage());
                try {
                    Thread.sleep(intervalMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while waiting for wallet", ie);
                }
            }
        }

        throw new RuntimeException("Wallet not created for user " + user.getUsername()
                + " after " + maxRetries + " attempts (" + (maxRetries * intervalMs / 1000) + "s)");
    }

    /**
     * Ajusta o saldo da carteira (crédito de BRL e/ou VIB).
     *
     * <p>Utiliza o endpoint PATCH /api/v1/wallets/{walletId}/balance com delta positivo
     * para depositar fundos. A autorização é feita pelo JWT do próprio usuário.</p>
     *
     * @param user      Usuário dono da carteira.
     * @param brlAmount Delta de BRL (null para não alterar).
     * @param vibAmount Delta de VIB (null para não alterar).
     */
    public void adjustBalance(TestUser user, BigDecimal brlAmount, BigDecimal vibAmount) {
        StringBuilder bodyBuilder = new StringBuilder("{");
        boolean hasField = false;

        if (brlAmount != null) {
            bodyBuilder.append("\"brlAmount\":").append(brlAmount.toPlainString());
            hasField = true;
        }
        if (vibAmount != null) {
            if (hasField) bodyBuilder.append(",");
            bodyBuilder.append("\"vibAmount\":").append(vibAmount.toPlainString());
        }
        bodyBuilder.append("}");

        String url = nextUrl();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url + "/api/v1/wallets/" + user.getWalletId() + "/balance"))
                .header("Authorization", user.getBearerToken())
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(bodyBuilder.toString()))
                .timeout(Duration.ofSeconds(15))
                .build();

        try {
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("Balance adjustment failed for wallet " + user.getWalletId()
                        + " (HTTP " + response.statusCode() + "): " + response.body());
            }
            logger.info("Balance adjusted for user {}: brl={}, vib={}",
                    user.getUsername(), brlAmount, vibAmount);
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to adjust balance for " + user.getUsername(), e);
        }
    }

    /**
     * Consulta o saldo atual da carteira do usuário.
     *
     * @param user Usuário de teste.
     * @return Array [brlAvailable, brlLocked, vibAvailable, vibLocked].
     */
    public BigDecimal[] getBalance(TestUser user) {
        String url = nextUrl();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url + "/api/v1/wallets/" + user.getKeycloakId()))
                .header("Authorization", user.getBearerToken())
                .header("Accept", "application/json")
                .GET()
                .timeout(Duration.ofSeconds(15))
                .build();

        try {
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("Failed to get balance for user " + user.getUsername()
                        + " (HTTP " + response.statusCode() + "): " + response.body());
            }
            JsonNode json = MAPPER.readTree(response.body());
            return new BigDecimal[]{
                    new BigDecimal(json.get("brlAvailable").asText()),
                    new BigDecimal(json.get("brlLocked").asText()),
                    new BigDecimal(json.get("vibAvailable").asText()),
                    new BigDecimal(json.get("vibLocked").asText())
            };
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to get balance for " + user.getUsername(), e);
        }
    }
}
