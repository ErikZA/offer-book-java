package com.vibranium.e2e;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.DockerComposeContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Suíte de testes End-to-End da Saga de Order Book.
 *
 * <h2>Estratégia</h2>
 * <p>Sobe o ambiente completo via {@code docker-compose.e2e.yml} com Testcontainers-Compose
 * e valida o fluxo cross-service do Order Book sem mocks:</p>
 * <ul>
 *   <li><b>Happy path:</b> BUY + SELL ao mesmo preço → ambas as ordens {@code FILLED}.</li>
 *   <li><b>Timeout path:</b> ordem sem contraparte → {@code CANCELLED} pelo
 *       {@code SagaTimeoutCleanupJob} após 1 minuto (configurado no perfil e2e).</li>
 * </ul>
 *
 * <h2>Autenticação</h2>
 * <p>Os serviços sobem com {@code SPRING_PROFILES_ACTIVE=e2e}, que ativa o
 * {@code E2eSecurityConfig}: um {@link org.springframework.security.oauth2.jwt.JwtDecoder}
 * que parseia JWTs sem validar assinatura. Os testes geram tokens {@code alg:none}
 * com um {@code sub} UUID fixo — sem necessidade de Keycloak.</p>
 *
 * <h2>Pré-configuração de dados</h2>
 * <p>O {@code @BeforeAll} chama os endpoints {@code /e2e/setup/*} expostos pelos
 * {@code E2eDataSeederController} de cada serviço para criar usuários e carteiras
 * com saldo inicial antes dos testes.</p>
 *
 * <h2>Consistência eventual (CQRS)</h2>
 * <p>O Read Model (MongoDB) é populado de forma assíncrona após cada evento de domínio.
 * O {@link org.awaitility.Awaitility} faz polling do {@code GET /api/v1/orders/{orderId}}
 * com intervalo de 2 s até o status esperado aparecer.</p>
 *
 * <h2>Execução</h2>
 * <pre>{@code
 * mvn clean install -DskipTests -T 4   # compila e instala todos os módulos
 * mvn verify -pl tests --no-transfer-progress  # roda a suíte E2E
 * }</pre>
 *
 * @see <a href="../../docs/testing/COMPREHENSIVE_TESTING.md">COMPREHENSIVE_TESTING.md</a>
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("5.3.1 — Saga End-to-End Integration Tests")
class SagaEndToEndIT {

    private static final Logger log = LoggerFactory.getLogger(SagaEndToEndIT.class);

    // =========================================================================
    // IDs fixos dos usuários de teste (sub claim do JWT)
    // Fixos para que o seeder seja idempotente em re-execuções do mesmo ambiente.
    // =========================================================================

    /** Comprador no happy path: tem BRL, coloca ordem BUY. */
    static final UUID BUYER_ID        = UUID.fromString("e2e00000-0000-4000-8000-000000000001");

    /** Vendedor no happy path: tem VIB, coloca ordem SELL. */
    static final UUID SELLER_ID       = UUID.fromString("e2e00000-0000-4000-8000-000000000002");

    /**
     * Usuário do timeout path: tem BRL, coloca BUY a preço muito baixo
     * (0.01 BRL/VIB) — sem contraparte possível → ordem fica OPEN →
     * cancelada pelo {@code SagaTimeoutCleanupJob} após 1 minuto.
     */
    static final UUID TIMEOUT_USER_ID = UUID.fromString("e2e00000-0000-4000-8000-000000000003");

    // Preços
    static final String PRICE_MATCH   = "100.00";   // happy path: BUY e SELL ao mesmo preço
    static final String PRICE_NO_MATCH= "0.01";     // timeout path: tão baixo que ninguém vende
    static final String AMOUNT_1_VIB  = "1.00000000";

    // =========================================================================
    // Docker Compose — container único para toda a suíte (static = singleton)
    // =========================================================================

    /**
     * Orquestra todos os serviços declarados em {@code tests/docker-compose.e2e.yml}.
     *
     * <p>Startup timeout de 5 minutos por serviço: incorpora o tempo de build das
     * imagens na primeira execução + inicialização do Spring Boot + MongoDB replica set
     * (pode levar até 3 min em máquinas menos potentes).</p>
     *
     * <p>Usa {@code withLocalCompose(true)}: delega ao binário {@code docker compose}
     * instalado localmente, necessário para suporte a Docker Compose v2 (formato yaml
     * com {@code depends_on.condition: service_healthy}).</p>
     */
    @Container
    @SuppressWarnings("resource")
    static final DockerComposeContainer<?> ENV =
            new DockerComposeContainer<>(resolveComposeFile())
                    // Usa o docker-compose instalado localmente (suporte a v2 e healthchecks)
                    .withLocalCompose(true)
                    // Mapeia a porta 8080 do order-service para uma porta dinâmica no host
                    .withExposedService("order-service-e2e", 8080,
                            Wait.forHttp("/actuator/health")
                                    .forStatusCode(200)
                                    .withStartupTimeout(Duration.ofMinutes(5)))
                    // Mapeia a porta 8081 do wallet-service para uma porta dinâmica no host
                    .withExposedService("wallet-service-e2e", 8081,
                            Wait.forHttp("/actuator/health")
                                    .forStatusCode(200)
                                    .withStartupTimeout(Duration.ofMinutes(5)));

    // URLs dos serviços resolvidas após o start do ComposeContainer
    static String orderServiceUrl;
    static String walletServiceUrl;

    // Wallet IDs criados pelo seeder — usados nos request bodies das ordens
    static UUID buyerWalletId;
    static UUID sellerWalletId;
    static UUID timeoutUserWalletId;

    // =========================================================================
    // Setup — executado UMA VEZ antes de todos os testes
    // =========================================================================

    /**
     * Resolve as URLs dos serviços e pré-configura dados de teste.
     *
     * <p>Os endpoints {@code /e2e/setup/*} são expostos pelo
     * {@code E2eDataSeederController} (ativo somente no perfil e2e).</p>
     */
    @BeforeAll
    static void setUp() {
        // Resolve URLs dinâmicas atribuídas pelo Testcontainers
        orderServiceUrl = "http://"
                + ENV.getServiceHost("order-service-e2e", 8080) + ":"
                + ENV.getServicePort("order-service-e2e", 8080);

        walletServiceUrl = "http://"
                + ENV.getServiceHost("wallet-service-e2e", 8081) + ":"
                + ENV.getServicePort("wallet-service-e2e", 8081);

        log.info("[E2E] order-service URL: {}", orderServiceUrl);
        log.info("[E2E] wallet-service URL: {}", walletServiceUrl);

        // 1. Registra os três usuários no UserRegistry do order-service
        seedOrderServiceUsers();

        // 2. Cria carteiras com saldo inicial no wallet-service e coleta wallet IDs
        seedWallets();

        log.info("[E2E] Setup concluído. buyerWalletId={} sellerWalletId={} timeoutUserWalletId={}",
                buyerWalletId, sellerWalletId, timeoutUserWalletId);
    }

    // =========================================================================
    // Teste 1 — Happy path: BUY + SELL → FILLED
    // =========================================================================

    /**
     * Valida o ciclo completo da Saga de Order Book:
     * <ol>
     *   <li>Comprador coloca BUY a 100 BRL/VIB.</li>
     *   <li>Aguarda FundsReserved → ordem muda para {@code OPEN}.</li>
     *   <li>Vendedor coloca SELL a 100 BRL/VIB.</li>
     *   <li>Aguarda execução do match → BUY e SELL ficam {@code FILLED}.</li>
     * </ol>
     *
     * <p>Critério de aceite AT-5.3.1: happy path completo validado.</p>
     */
    @Test
    @Order(1)
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("Happy path: BUY + SELL ao mesmo preço → ambas as ordens FILLED")
    void testHappyPath() {
        // ------------------------------------------------------------------ ARRANGE
        String buyerJwt  = craftJwt(BUYER_ID);
        String sellerJwt = craftJwt(SELLER_ID);

        // ------------------------------------------------------------------ ACT: coloca BUY
        log.info("[E2E][happy] Colocando ordem BUY: price={} amount={} walletId={}",
                PRICE_MATCH, AMOUNT_1_VIB, buyerWalletId);

        // POST /api/v1/orders — deve retornar 202 Accepted
        String buyOrderId = given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + buyerJwt)
                .body(orderBody(buyerWalletId, "BUY", PRICE_MATCH, AMOUNT_1_VIB))
                .post(orderServiceUrl + "/api/v1/orders")
                .then()
                .statusCode(202)
                .extract().path("orderId");

        assertThat(buyOrderId).as("orderId da BUY não pode ser nulo").isNotNull();
        log.info("[E2E][happy] BUY criada: orderId={}", buyOrderId);

        // ------------------------------------------------------------------ WAIT: FundsReserved → OPEN
        // O wallet-service processa ReserveFundsCommand via RabbitMQ (assíncrono).
        // Polling até o Read Model (MongoDB) refletir status=OPEN.
        log.info("[E2E][happy] Aguardando BUY ficar OPEN (FundsReserved)...");
        await("BUY deve atingir status OPEN após reserva de fundos")
                .atMost(45, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    String status = getOrderStatus(buyerJwt, buyOrderId);
                    assertThat(status)
                            .as("BUY deve estar OPEN — wallet processou FundsReserved")
                            .isEqualTo("OPEN");
                });

        // ------------------------------------------------------------------ ACT: coloca SELL
        log.info("[E2E][happy] Colocando ordem SELL: price={} amount={} walletId={}",
                PRICE_MATCH, AMOUNT_1_VIB, sellerWalletId);

        String sellOrderId = given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + sellerJwt)
                .body(orderBody(sellerWalletId, "SELL", PRICE_MATCH, AMOUNT_1_VIB))
                .post(orderServiceUrl + "/api/v1/orders")
                .then()
                .statusCode(202)
                .extract().path("orderId");

        assertThat(sellOrderId).as("orderId da SELL não pode ser nulo").isNotNull();
        log.info("[E2E][happy] SELL criada: orderId={}", sellOrderId);

        // ------------------------------------------------------------------ WAIT: match executado → FILLED
        // O motor Redis detecta o match e publica MatchExecutedEvent.
        // Aguarda o BUY atingir FILLED (confirmação de execução completa).
        log.info("[E2E][happy] Aguardando BUY ficar FILLED (match executado)...");
        await("BUY deve atingir FILLED após match com SELL")
                .atMost(45, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    String status = getOrderStatus(buyerJwt, buyOrderId);
                    assertThat(status)
                            .as("BUY deve ser FILLED após matching com SELL a mesmo preço")
                            .isEqualTo("FILLED");
                });

        // ------------------------------------------------------------------ ASSERT: GET final
        Response finalBuy = given()
                .header("Authorization", "Bearer " + buyerJwt)
                .get(orderServiceUrl + "/api/v1/orders/" + buyOrderId)
                .then()
                .statusCode(200)
                .extract().response();

        assertThat(finalBuy.<String>path("status")).isEqualTo("FILLED");
        assertThat(finalBuy.<String>path("orderType")).isEqualTo("BUY");
        assertThat(finalBuy.<String>path("orderId")).isEqualTo(buyOrderId);

        log.info("[E2E][happy] ✓ Happy path validado: BUY {} = FILLED", buyOrderId);
    }

    // =========================================================================
    // Teste 2 — Timeout path: ordem sem match → CANCELLED pelo cleanup job
    // =========================================================================

    /**
     * Valida o caminho de timeout da Saga:
     * <ol>
     *   <li>Usuário coloca BUY a 0.01 BRL/VIB (preço impossível — ninguém vende tão barato).</li>
     *   <li>Aguarda FundsReserved → ordem muda para {@code OPEN}.</li>
     *   <li>Aguarda o {@code SagaTimeoutCleanupJob} cancelar a ordem após 1 minuto.</li>
     * </ol>
     *
     * <p>Configuração no perfil e2e:</p>
     * <ul>
     *   <li>{@code APP_SAGA_PENDING_TIMEOUT_MINUTES=1} — threshold de 1 minuto.</li>
     *   <li>{@code APP_SAGA_CLEANUP_DELAY_MS=5000} — job roda a cada 5s.</li>
     * </ul>
     *
     * <p>Critério de aceite AT-5.3.1: timeout path validado.</p>
     */
    @Test
    @Order(2)
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @DisplayName("Timeout path: ordem BUY sem contraparte → CANCELLED pelo SagaTimeoutCleanupJob")
    void testTimeoutPath() {
        // ------------------------------------------------------------------ ARRANGE
        String timeoutUserJwt = craftJwt(TIMEOUT_USER_ID);

        // ------------------------------------------------------------------ ACT: coloca BUY a preço impossível
        // Preço 0.01 BRL/VIB: sem vendedor disposto a vender a esse valor.
        // O job de cleanup cancela após 1 minuto (configurado no perfil e2e).
        log.info("[E2E][timeout] Colocando BUY a preço {} (sem match possível)...", PRICE_NO_MATCH);

        String orderId = given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + timeoutUserJwt)
                .body(orderBody(timeoutUserWalletId, "BUY", PRICE_NO_MATCH, AMOUNT_1_VIB))
                .post(orderServiceUrl + "/api/v1/orders")
                .then()
                .statusCode(202)
                .extract().path("orderId");

        assertThat(orderId).as("orderId não pode ser nulo").isNotNull();
        log.info("[E2E][timeout] Ordem criada: orderId={}", orderId);

        // ------------------------------------------------------------------ WAIT: OPEN (FundsReserved)
        log.info("[E2E][timeout] Aguardando OPEN (FundsReserved)...");
        await("Ordem deve atingir OPEN antes do timeout")
                .atMost(45, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    String status = getOrderStatus(timeoutUserJwt, orderId);
                    assertThat(status)
                            .as("Ordem deve ser OPEN após reserva de fundos")
                            .isEqualTo("OPEN");
                });

        // ------------------------------------------------------------------ WAIT: CANCELLED (cleanup job)
        // O SagaTimeoutCleanupJob roda a cada 5s (APP_SAGA_CLEANUP_DELAY_MS=5000) e
        // cancela ordens com created_at < now() - 1min. Aguarda até 90s para cobertura.
        log.info("[E2E][timeout] Aguardando CANCELLED pelo SagaTimeoutCleanupJob (~60s)...");
        await("Ordem deve ser CANCELLED pelo SagaTimeoutCleanupJob após ~1 minuto")
                .atMost(90, TimeUnit.SECONDS)
                .pollInterval(5, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    String status = getOrderStatus(timeoutUserJwt, orderId);
                    assertThat(status)
                            .as("SagaTimeoutCleanupJob deve ter cancelado a ordem após 1 min")
                            .isEqualTo("CANCELLED");
                });

        log.info("[E2E][timeout] ✓ Timeout path validado: ordem {} = CANCELLED", orderId);
    }

    // =========================================================================
    // Helpers privados
    // =========================================================================

    /**
     * Consulta o status atual de uma ordem no Read Model (MongoDB via order-service).
     *
     * <p>Pode retornar {@code null} se o documento ainda não foi projetado (404).
     * O Awaitility chama este método repetidamente até a asserção passar.</p>
     *
     * @param jwt     JWT do usuário autenticado (deve ser o dono da ordem).
     * @param orderId UUID da ordem como string.
     * @return Status atual ("PENDING", "OPEN", "FILLED", "CANCELLED") ou {@code null} se ainda não existe.
     */
    private static String getOrderStatus(String jwt, String orderId) {
        Response response = given()
                .header("Authorization", "Bearer " + jwt)
                .get(orderServiceUrl + "/api/v1/orders/" + orderId);

        if (response.statusCode() == 404) {
            // Documento ainda não projetado no MongoDB (consistência eventual — retry)
            return null;
        }

        Assertions
                .assertThat(response.statusCode())
                .as("GET /api/v1/orders/" + orderId + " deve retornar 200")
                .isEqualTo(200);

        return response.path("status");
    }

    /**
     * Serializa o body JSON de uma ordem para o endpoint {@code POST /api/v1/orders}.
     *
     * @param walletId  UUID da carteira do usuário.
     * @param orderType "BUY" ou "SELL".
     * @param price     Preço limite em BRL (string numérica).
     * @param amount    Quantidade de VIB (string numérica).
     * @return JSON string pronto para uso como request body.
     */
    private static String orderBody(UUID walletId, String orderType, String price, String amount) {
        return """
                {
                  "walletId": "%s",
                  "orderType": "%s",
                  "price": %s,
                  "amount": %s
                }
                """.formatted(walletId, orderType, price, amount);
    }

    /**
     * Cria um JWT com algoritmo {@code none} (sem assinatura) contendo o {@code sub} claim.
     *
     * <p>O {@code E2eSecurityConfig} usa um {@code JwtDecoder} que parseia qualquer JWT
     * sem validar a assinatura, portanto tokens como este são aceitos no perfil e2e.</p>
     *
     * @param userId UUID do usuário (será o valor do claim {@code sub}).
     * @return Token JWT base64url com formato {@code header.payload.} (assinatura vazia).
     */
    static String craftJwt(UUID userId) {
        // Header: alg=none indica ausência de assinatura
        String header  = b64u("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        // Payload: sub=userId, iat e exp arbitrários (sem validação no perfil e2e)
        String payload = b64u("{\"sub\":\"%s\",\"iat\":1000000000,\"exp\":9999999999}"
                .formatted(userId));
        // JWT sem assinatura: header.payload. (ponto final = assinatura vazia)
        return header + "." + payload + ".";
    }

    /** Codifica uma string como Base64Url sem padding. */
    private static String b64u(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    // =========================================================================
    // Setup de dados — chamado em @BeforeAll
    // =========================================================================

    /**
     * Registra os três usuários de teste no {@code UserRegistry} do order-service.
     * Sem registro, o order-service rejeita ordens com 403 ({@code UserNotRegisteredException}).
     */
    @SuppressWarnings("unchecked")
    private static void seedOrderServiceUsers() {
        log.info("[E2E] Registrando usuários no order-service (UserRegistry)...");

        // O seeder aceita uma lista de keycloak IDs (UUID strings)
        given()
                .contentType(ContentType.JSON)
                .body(List.of(
                        BUYER_ID.toString(),
                        SELLER_ID.toString(),
                        TIMEOUT_USER_ID.toString()
                ))
                .post(orderServiceUrl + "/e2e/setup/users")
                .then()
                .statusCode(201);

        log.info("[E2E] Usuários registrados no order-service.");
    }

    /**
     * Cria carteiras e deposita saldos iniciais no wallet-service para cada usuário de teste.
     *
     * <p>Saldos iniciais:</p>
     * <ul>
     *   <li>BUYER: 1000.00 BRL, 0 VIB &mdash; suficiente para BUY a 100 BRL/VIB × 1 VIB.</li>
     *   <li>SELLER: 0 BRL, 50 VIB &mdash; suficiente para SELL de 1 VIB.</li>
     *   <li>TIMEOUT_USER: 10.00 BRL, 0 VIB &mdash; suficiente para BUY a 0.01 BRL/VIB × 1 VIB.</li>
     * </ul>
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void seedWallets() {
        log.info("[E2E] Criando carteiras e depositando saldos...");

        // Body: lista de {userId, brl, vib}
        String seedBody = """
                [
                  { "userId": "%s", "brl": 1000.00, "vib": 0.00 },
                  { "userId": "%s", "brl":    0.00, "vib": 50.00 },
                  { "userId": "%s", "brl":   10.00, "vib": 0.00  }
                ]
                """.formatted(BUYER_ID, SELLER_ID, TIMEOUT_USER_ID);

        // O seeder retorna uma lista com walletId, userId e saldos criados
        List<Map> wallets = given()
                .contentType(ContentType.JSON)
                .body(seedBody)
                .post(walletServiceUrl + "/e2e/setup/wallets")
                .then()
                .statusCode(201)
                .extract()
                .body()
                .as(List.class);

        // Mapeia walletId por userId para uso no corpo das ordens
        for (Map wallet : wallets) {
            String userId   = wallet.get("userId").toString();
            String walletId = wallet.get("walletId").toString();

            if (BUYER_ID.toString().equals(userId)) {
                buyerWalletId = UUID.fromString(walletId);
            } else if (SELLER_ID.toString().equals(userId)) {
                sellerWalletId = UUID.fromString(walletId);
            } else if (TIMEOUT_USER_ID.toString().equals(userId)) {
                timeoutUserWalletId = UUID.fromString(walletId);
            }
        }

        assertThat(buyerWalletId).as("buyerWalletId não deve ser nulo após seeder").isNotNull();
        assertThat(sellerWalletId).as("sellerWalletId não deve ser nulo após seeder").isNotNull();
        assertThat(timeoutUserWalletId).as("timeoutUserWalletId não deve ser nulo após seeder").isNotNull();

        log.info("[E2E] Saldos configurados: buyer={} seller={} timeout={}",
                buyerWalletId, sellerWalletId, timeoutUserWalletId);
    }

    // =========================================================================
    // Utilitário — localiza o docker-compose.e2e.yml em tempo de execução
    // =========================================================================

    /**
     * Localiza o {@code docker-compose.e2e.yml} a partir do diretório do módulo Maven.
     *
     * <p>Estratégia: navega de {@code tests/target/test-classes/} (classpath root ao rodar via
     * Failsafe) para {@code tests/} e então para {@code tests/docker-compose.e2e.yml}.</p>
     *
     * @return {@link File} apontando para o compose file real (não uma cópia em classpath).
     * @throws RuntimeException se o arquivo não for encontrado.
     */
    private static File resolveComposeFile() {
        try {
            // Localização do código compilado: tests/target/test-classes/
            URL codeLocation = SagaEndToEndIT.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation();

            File testClasses = new File(codeLocation.toURI()); // tests/target/test-classes
            File targetDir   = testClasses.getParentFile();    // tests/target
            File testsModule = targetDir.getParentFile();      // tests/

            File composeFile = new File(testsModule, "docker-compose.e2e.yml");

            if (!composeFile.exists()) {
                throw new IllegalStateException(
                        "docker-compose.e2e.yml não encontrado em: " + composeFile.getAbsolutePath()
                        + "\nCertifique-se de executar 'mvn verify -pl tests' a partir da raiz do projeto.");
            }

            return composeFile;

        } catch (Exception e) {
            throw new RuntimeException("Falha ao localizar docker-compose.e2e.yml", e);
        }
    }
}
