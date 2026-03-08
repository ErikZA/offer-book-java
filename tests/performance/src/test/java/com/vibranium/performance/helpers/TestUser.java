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
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Representa um usuário de teste com credenciais Keycloak e dados de carteira.
 *
 * <p>Gerencia o ciclo de vida do JWT Bearer token com renovação automática
 * thread-safe. Cada instância é associada a um usuário Keycloak único.</p>
 */
public final class TestUser {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final long EXPIRY_MARGIN_SECONDS = 30;

    private final String tokenEndpoint;
    private final String clientId;
    private final String username;
    private final String password;

    private String keycloakId;
    private UUID walletId;

    private final ReentrantLock lock = new ReentrantLock();
    private volatile String cachedToken;
    private volatile Instant tokenExpiry = Instant.EPOCH;

    public TestUser(String keycloakBaseUrl, String realm, String clientId,
                    String username, String password) {
        this.tokenEndpoint = keycloakBaseUrl + "/realms/" + realm + "/protocol/openid-connect/token";
        this.clientId = clientId;
        this.username = username;
        this.password = password;
    }

    public String getKeycloakId() { return keycloakId; }
    public void setKeycloakId(String keycloakId) { this.keycloakId = keycloakId; }
    public UUID getWalletId() { return walletId; }
    public void setWalletId(UUID walletId) { this.walletId = walletId; }
    public String getUsername() { return username; }

    /**
     * Retorna Bearer token válido, renovando automaticamente se expirado.
     *
     * @return Token no formato "Bearer eyJ..."
     */
    public String getBearerToken() {
        if (isValid()) {
            return "Bearer " + cachedToken;
        }
        lock.lock();
        try {
            if (isValid()) {
                return "Bearer " + cachedToken;
            }
            refreshToken();
            return "Bearer " + cachedToken;
        } finally {
            lock.unlock();
        }
    }

    private boolean isValid() {
        return cachedToken != null && Instant.now().isBefore(tokenExpiry);
    }

    private void refreshToken() {
        String form = "grant_type=password"
                + "&client_id=" + encode(clientId)
                + "&username=" + encode(username)
                + "&password=" + encode(password);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(tokenEndpoint))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .timeout(Duration.ofSeconds(15))
                .build();

        try {
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("Token request failed for user " + username
                        + " (HTTP " + response.statusCode() + "): " + response.body());
            }
            JsonNode json = MAPPER.readTree(response.body());
            cachedToken = json.get("access_token").asText();
            long expiresIn = json.get("expires_in").asLong();
            tokenExpiry = Instant.now().plusSeconds(expiresIn - EXPIRY_MARGIN_SECONDS);
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to obtain token for user " + username, e);
        }
    }

    private static String encode(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
