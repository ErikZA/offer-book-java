package com.vibranium.performance.helpers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Helper para operações na Keycloak Admin REST API.
 *
 * <p>Cria usuários de teste, atribui roles e obtém Keycloak IDs.
 * Todas as operações são idempotentes — re-execuções não falham.</p>
 */
public final class KeycloakAdminHelper {

    private static final Logger logger = LoggerFactory.getLogger(KeycloakAdminHelper.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String keycloakBaseUrl;
    private final String realm;
    private final String clientId;
    private final String adminUser;
    private final String adminPassword;

    private String adminToken;

    public KeycloakAdminHelper(String keycloakBaseUrl, String realm, String clientId,
                               String adminUser, String adminPassword) {
        this.keycloakBaseUrl = keycloakBaseUrl;
        this.realm = realm;
        this.clientId = clientId;
        this.adminUser = adminUser;
        this.adminPassword = adminPassword;
    }

    /**
     * Cria {@code count} usuários de teste no Keycloak e retorna instâncias de {@link TestUser}.
     *
     * <p>Cada usuário recebe o role {@code USER} do realm e tem credenciais
     * no formato {@code perf-user-N / perf-test-N}.</p>
     *
     * @param count Quantidade de usuários a criar (mínimo 1).
     * @return Lista de TestUser com keycloakId preenchido.
     */
    public List<TestUser> createTestUsers(int count) {
        refreshAdminToken();

        List<TestUser> users = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            String username = "perf-user-" + i;
            String password = "perf-test-" + i;
            String email = username + "@vibranium-perf.com";

            createUser(username, password, email);
            String keycloakId = getUserId(username);
            assignUserRole(keycloakId);

            TestUser user = new TestUser(keycloakBaseUrl, realm, clientId, username, password);
            user.setKeycloakId(keycloakId);
            users.add(user);

            logger.info("Test user ready: username={}, keycloakId={}", username, keycloakId);
        }

        return users;
    }

    private void refreshAdminToken() {
        String form = "grant_type=password"
                + "&client_id=admin-cli"
                + "&username=" + encode(adminUser)
                + "&password=" + encode(adminPassword);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(keycloakBaseUrl + "/realms/master/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .timeout(Duration.ofSeconds(15))
                .build();

        try {
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("Admin token request failed (HTTP "
                        + response.statusCode() + "): " + response.body());
            }
            adminToken = MAPPER.readTree(response.body()).get("access_token").asText();
            logger.info("Keycloak admin token obtained successfully");
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to get Keycloak admin token", e);
        }
    }

    private void createUser(String username, String password, String email) {
        String body = """
                {
                  "username": "%s",
                  "email": "%s",
                  "emailVerified": true,
                  "enabled": true,
                  "credentials": [{
                    "type": "password",
                    "value": "%s",
                    "temporary": false
                  }]
                }
                """.formatted(username, email, password);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(keycloakBaseUrl + "/admin/realms/" + realm + "/users"))
                .header("Authorization", "Bearer " + adminToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(15))
                .build();

        try {
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 201) {
                logger.info("User '{}' created in Keycloak", username);
            } else if (response.statusCode() == 409) {
                logger.info("User '{}' already exists in Keycloak (idempotent)", username);
            } else {
                throw new RuntimeException("Failed to create user " + username
                        + " (HTTP " + response.statusCode() + "): " + response.body());
            }
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to create user " + username, e);
        }
    }

    private String getUserId(String username) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(keycloakBaseUrl + "/admin/realms/" + realm
                        + "/users?username=" + encode(username) + "&exact=true"))
                .header("Authorization", "Bearer " + adminToken)
                .GET()
                .timeout(Duration.ofSeconds(15))
                .build();

        try {
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("Failed to search user " + username
                        + " (HTTP " + response.statusCode() + "): " + response.body());
            }
            JsonNode users = MAPPER.readTree(response.body());
            if (!users.isArray() || users.isEmpty()) {
                throw new RuntimeException("User not found in Keycloak: " + username);
            }
            return users.get(0).get("id").asText();
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to search user " + username, e);
        }
    }

    private void assignUserRole(String userId) {
        // Busca a representação do role "USER" para obter o ID necessário no mapping
        String roleId = getRoleId("USER");

        String body = """
                [{"id": "%s", "name": "USER"}]
                """.formatted(roleId);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(keycloakBaseUrl + "/admin/realms/" + realm
                        + "/users/" + userId + "/role-mappings/realm"))
                .header("Authorization", "Bearer " + adminToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(15))
                .build();

        try {
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            // 204 = atribuído, outros códigos podem indicar que já possui o role
            if (response.statusCode() != 204) {
                logger.debug("Role assignment for user {} returned HTTP {}", userId, response.statusCode());
            }
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to assign role to user " + userId, e);
        }
    }

    private String getRoleId(String roleName) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(keycloakBaseUrl + "/admin/realms/" + realm + "/roles/" + roleName))
                .header("Authorization", "Bearer " + adminToken)
                .GET()
                .timeout(Duration.ofSeconds(15))
                .build();

        try {
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("Failed to get role " + roleName
                        + " (HTTP " + response.statusCode() + "): " + response.body());
            }
            return MAPPER.readTree(response.body()).get("id").asText();
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to get role " + roleName, e);
        }
    }

    private static String encode(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
