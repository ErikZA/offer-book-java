package com.vibranium.performance.helpers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Obtém e mantém em cache um JWT Bearer token do Keycloak via Resource Owner Password Credentials Grant.
 *
 * <p>Thread-safe: utiliza lock para evitar múltiplas requisições simultâneas ao token endpoint
 * quando o token expira durante um teste de carga com muitas virtual users.</p>
 *
 * <p>O token é renovado automaticamente 30s antes da expiração real para evitar
 * erros 401 durante o benchmark.</p>
 */
public final class KeycloakTokenFeeder {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // Margem de segurança: renovar o token 30s antes de expirar
    private static final long EXPIRY_MARGIN_SECONDS = 30;

    private final String tokenEndpoint;
    private final String clientId;
    private final String username;
    private final String password;

    private final ReentrantLock lock = new ReentrantLock();
    private volatile String cachedToken;
    private volatile Instant tokenExpiry = Instant.EPOCH;

    /**
     * Cria o feeder com configurações explícitas.
     *
     * @param keycloakBaseUrl URL base do Keycloak (ex: http://localhost:8180)
     * @param realm           Nome do realm (ex: orderbook-realm)
     * @param clientId        Client ID do Keycloak (ex: order-client)
     * @param username        Usuário para autenticação
     * @param password        Senha do usuário
     */
    public KeycloakTokenFeeder(String keycloakBaseUrl, String realm,
                               String clientId, String username, String password) {
        this.tokenEndpoint = keycloakBaseUrl + "/realms/" + realm + "/protocol/openid-connect/token";
        this.clientId = clientId;
        this.username = username;
        this.password = password;
    }

    /**
     * Cria o feeder usando variáveis de ambiente com defaults para staging.
     *
     * <p>Variáveis de ambiente consultadas:</p>
     * <ul>
     *   <li>{@code KEYCLOAK_BASE_URL} — default: {@code http://keycloak:8080}</li>
     *   <li>{@code KEYCLOAK_REALM} — default: {@code orderbook-realm}</li>
     *   <li>{@code KEYCLOAK_CLIENT_ID} — default: {@code order-client}</li>
     *   <li>{@code KEYCLOAK_USERNAME} — default: {@code tester}</li>
     *   <li>{@code KEYCLOAK_PASSWORD} — default: {@code test-password}</li>
     * </ul>
     */
    public static KeycloakTokenFeeder fromEnv() {
        return new KeycloakTokenFeeder(
                env("KEYCLOAK_BASE_URL", "http://keycloak:8080"),
                env("KEYCLOAK_REALM", "orderbook-realm"),
                env("KEYCLOAK_CLIENT_ID", "order-client"),
                env("KEYCLOAK_USERNAME", "tester"),
                env("KEYCLOAK_PASSWORD", "test-password")
        );
    }

    /**
     * Retorna um Bearer token válido. Renova automaticamente se expirado.
     *
     * @return Token JWT no formato "Bearer eyJ..."
     * @throws RuntimeException se não for possível obter o token
     */
    public String getBearerToken() {
        if (isTokenValid()) {
            return "Bearer " + cachedToken;
        }

        lock.lock();
        try {
            // Double-check após obter o lock
            if (isTokenValid()) {
                return "Bearer " + cachedToken;
            }
            refreshToken();
            return "Bearer " + cachedToken;
        } finally {
            lock.unlock();
        }
    }

    private boolean isTokenValid() {
        return cachedToken != null && Instant.now().isBefore(tokenExpiry);
    }

    private void refreshToken() {
        String formBody = "grant_type=" + encode("password")
                + "&client_id=" + encode(clientId)
                + "&username=" + encode(username)
                + "&password=" + encode(password);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(tokenEndpoint))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .timeout(Duration.ofSeconds(15))
                .build();

        try {
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException(
                        "Keycloak token request failed (HTTP " + response.statusCode() + "): " + response.body());
            }

            JsonNode json = MAPPER.readTree(response.body());
            cachedToken = json.get("access_token").asText();

            long expiresIn = json.get("expires_in").asLong();
            tokenExpiry = Instant.now().plusSeconds(expiresIn - EXPIRY_MARGIN_SECONDS);

        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to obtain Keycloak token from " + tokenEndpoint, e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }
}
