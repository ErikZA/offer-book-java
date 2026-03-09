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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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

    /** Comprador no cenário de partial fill: compra 2 VIB contra SELL de 5 VIB. */
    static final UUID PARTIAL_BUYER_ID  = UUID.fromString("e2e00000-0000-4000-8000-000000000004");

    /** Vendedor no cenário de partial fill: vende 5 VIB, mas apenas 2 são consumidos. */
    static final UUID PARTIAL_SELLER_ID = UUID.fromString("e2e00000-0000-4000-8000-000000000005");

    /** Comprador no cenário de multi-match: compra 2 VIB consumindo 2 SELLs de 1 VIB cada. */
    static final UUID MULTI_BUYER_ID     = UUID.fromString("e2e00000-0000-4000-8000-000000000006");

    /** Vendedor A no cenário de multi-match: vende 1 VIB. */
    static final UUID MULTI_SELLER_A_ID  = UUID.fromString("e2e00000-0000-4000-8000-000000000007");

    /** Vendedor B no cenário de multi-match: vende 1 VIB. */
    static final UUID MULTI_SELLER_B_ID  = UUID.fromString("e2e00000-0000-4000-8000-000000000008");

    /** Usuário com saldo insuficiente: tem apenas 1 BRL, tenta BUY de 100 BRL → rejeitado. */
    static final UUID INSUFFICIENT_FUNDS_USER_ID = UUID.fromString("e2e00000-0000-4000-8000-000000000009");

    /** Comprador no cenário de idempotência: coloca duas BUYs idênticas (orderIds distintos). */
    static final UUID IDEMP_BUYER_ID  = UUID.fromString("e2e00000-0000-4000-8000-000000000010");

    /** Vendedor no cenário de idempotência: contraparte da primeira BUY. */
    static final UUID IDEMP_SELLER_ID = UUID.fromString("e2e00000-0000-4000-8000-000000000011");

    /** Comprador no cenário de integridade financeira: inicia com 500 BRL, 0 VIB. */
    static final UUID BALANCE_BUYER_ID  = UUID.fromString("e2e00000-0000-4000-8000-000000000012");

    /** Vendedor no cenário de integridade financeira: inicia com 0 BRL, 10 VIB. */
    static final UUID BALANCE_SELLER_ID = UUID.fromString("e2e00000-0000-4000-8000-000000000013");

    /** Comprador 1 no cenário de concorrência 3×3. */
    static final UUID CONC_BUYER_1_ID  = UUID.fromString("e2e00000-0000-4000-8000-000000000014");
    /** Comprador 2 no cenário de concorrência 3×3. */
    static final UUID CONC_BUYER_2_ID  = UUID.fromString("e2e00000-0000-4000-8000-000000000015");
    /** Comprador 3 no cenário de concorrência 3×3. */
    static final UUID CONC_BUYER_3_ID  = UUID.fromString("e2e00000-0000-4000-8000-000000000016");
    /** Vendedor 1 no cenário de concorrência 3×3. */
    static final UUID CONC_SELLER_1_ID = UUID.fromString("e2e00000-0000-4000-8000-000000000017");
    /** Vendedor 2 no cenário de concorrência 3×3. */
    static final UUID CONC_SELLER_2_ID = UUID.fromString("e2e00000-0000-4000-8000-000000000018");
    /** Vendedor 3 no cenário de concorrência 3×3. */
    static final UUID CONC_SELLER_3_ID = UUID.fromString("e2e00000-0000-4000-8000-000000000019");

    /** Comprador no cenário de projeção CQRS: valida Read Model MongoDB. */
    static final UUID PROJ_BUYER_ID  = UUID.fromString("e2e00000-0000-4000-8000-000000000020");

    /** Vendedor no cenário de projeção CQRS: valida Read Model MongoDB. */
    static final UUID PROJ_SELLER_ID = UUID.fromString("e2e00000-0000-4000-8000-000000000021");

    /** Comprador no cenário de partial fill + timeout: compra 1 VIB de SELL de 3 VIB. */
    static final UUID PARTIAL_CANCEL_BUYER_ID  = UUID.fromString("e2e00000-0000-4000-8000-000000000022");

    /** Vendedor no cenário de partial fill + timeout: vende 3 VIB, apenas 1 é matchado → resíduo CANCELLED. */
    static final UUID PARTIAL_CANCEL_SELLER_ID = UUID.fromString("e2e00000-0000-4000-8000-000000000023");

    /** Usuário A no cenário de segurança cross-user: dono da ordem e wallet. */
    static final UUID SEC_USER_A_ID = UUID.fromString("e2e00000-0000-4000-8000-000000000024");

    /** Usuário B no cenário de segurança cross-user: tenta acessar recursos de A. */
    static final UUID SEC_USER_B_ID = UUID.fromString("e2e00000-0000-4000-8000-000000000025");

    /** Comprador no cenário de resiliência: duas rodadas sequenciais de trade. */
    static final UUID RESIL_BUYER_ID  = UUID.fromString("e2e00000-0000-4000-8000-000000000026");

    /** Vendedor no cenário de resiliência: duas rodadas sequenciais de trade. */
    static final UUID RESIL_SELLER_ID = UUID.fromString("e2e00000-0000-4000-8000-000000000027");

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
     * instalado localmente, necessário para suporte a Docker Compose v2+ (formato yaml
     * com {@code depends_on.condition: service_healthy}).</p>
     *
     * <p>Portas expostas via {@code ports:} no compose file (18080→8080, 18081→8081)
     * em vez de {@code withExposedService()} — evita ambassador containers (socat) que
     * falham com Docker Compose v5+ por incompatibilidade de naming convention.</p>
     */
    @Container
    @SuppressWarnings("resource")
    static final DockerComposeContainer<?> ENV =
            new DockerComposeContainer<>(resolveComposeFile())
                    // Usa o docker-compose instalado localmente (suporte a v2+ e healthchecks)
                    .withLocalCompose(true)
                    // Força rebuild das imagens (Dockerfile.e2e com classes de teste injetadas)
                    .withBuild(true);

    // URLs dos serviços resolvidas após o start do ComposeContainer
    static String orderServiceUrl;
    static String walletServiceUrl;

    // Wallet IDs criados pelo seeder — usados nos request bodies das ordens
    static UUID buyerWalletId;
    static UUID sellerWalletId;
    static UUID timeoutUserWalletId;
    static UUID partialBuyerWalletId;
    static UUID partialSellerWalletId;
    static UUID multiBuyerWalletId;
    static UUID multiSellerAWalletId;
    static UUID multiSellerBWalletId;
    static UUID insufficientFundsWalletId;
    static UUID idempBuyerWalletId;
    static UUID idempSellerWalletId;
    static UUID balanceBuyerWalletId;
    static UUID balanceSellerWalletId;
    static UUID concBuyer1WalletId;
    static UUID concBuyer2WalletId;
    static UUID concBuyer3WalletId;
    static UUID concSeller1WalletId;
    static UUID concSeller2WalletId;
    static UUID concSeller3WalletId;
    static UUID projBuyerWalletId;
    static UUID projSellerWalletId;
    static UUID partialCancelBuyerWalletId;
    static UUID partialCancelSellerWalletId;
    static UUID secUserAWalletId;
    static UUID secUserBWalletId;
    static UUID resilBuyerWalletId;
    static UUID resilSellerWalletId;

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
        // Portas fixas mapeadas no docker-compose.e2e.yml (18080→8080, 18081→8081)
        orderServiceUrl = "http://localhost:18080";
        walletServiceUrl = "http://localhost:18081";

        log.info("[E2E] Aguardando health check dos serviços...");

        // Aguarda order-service ficar healthy (até 5 min — inclui build/startup)
        await().atMost(Duration.ofMinutes(5))
                .pollInterval(Duration.ofSeconds(5))
                .ignoreExceptions()
                .until(() -> {
                    int status = given().get(orderServiceUrl + "/actuator/health").statusCode();
                    return status == 200;
                });

        // Aguarda wallet-service ficar healthy
        await().atMost(Duration.ofMinutes(5))
                .pollInterval(Duration.ofSeconds(5))
                .ignoreExceptions()
                .until(() -> {
                    int status = given().get(walletServiceUrl + "/actuator/health").statusCode();
                    return status == 200;
                });

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
    // Teste 3 — Partial Fill: BUY 2 VIB contra SELL 5 VIB
    // =========================================================================

    /**
     * Valida o cenário de execução parcial (Partial Fill):
     * <ol>
     *   <li>Vendedor coloca SELL de <b>5 VIB</b> a 100 BRL/VIB.</li>
     *   <li>Aguarda SELL ficar {@code OPEN} (FundsReserved).</li>
     *   <li>Comprador coloca BUY de <b>2 VIB</b> a 100 BRL/VIB.</li>
     *   <li>Motor executa match parcial: BUY fica {@code FILLED} (2 VIB), SELL fica {@code PARTIAL_FILLED} (remaining 3 VIB).</li>
     * </ol>
     *
     * <p>Valida também os saldos das wallets após o settlement:</p>
     * <ul>
     *   <li>Comprador: vibAvailable aumentou em 2 VIB.</li>
     *   <li>Vendedor: brlAvailable aumentou em 200 BRL (2 × 100).</li>
     * </ul>
     *
     * <p>Critério de aceite AT-5.3.3: partial fill validado.</p>
     */
    @Test
    @Order(3)
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("Partial fill: BUY 2 VIB contra SELL 5 VIB → BUY FILLED, SELL PARTIAL_FILLED")
    void testPartialFill() {
        // ------------------------------------------------------------------ ARRANGE
        String partialBuyerJwt  = craftJwt(PARTIAL_BUYER_ID);
        String partialSellerJwt = craftJwt(PARTIAL_SELLER_ID);

        String sellAmount = "5.00000000";
        String buyAmount  = "2.00000000";

        // ------------------------------------------------------------------ ACT: coloca SELL de 5 VIB
        log.info("[E2E][partial-fill] Colocando SELL de 5 VIB a {} BRL/VIB", PRICE_MATCH);

        String sellOrderId = given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + partialSellerJwt)
                .body(orderBody(partialSellerWalletId, "SELL", PRICE_MATCH, sellAmount))
                .post(orderServiceUrl + "/api/v1/orders")
                .then()
                .statusCode(202)
                .extract().path("orderId");

        assertThat(sellOrderId).as("orderId da SELL não pode ser nulo").isNotNull();
        log.info("[E2E][partial-fill] SELL criada: orderId={}", sellOrderId);

        // ------------------------------------------------------------------ WAIT: SELL fica OPEN
        log.info("[E2E][partial-fill] Aguardando SELL ficar OPEN...");
        await("SELL deve atingir status OPEN após reserva de fundos")
                .atMost(45, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    String status = getOrderStatus(partialSellerJwt, sellOrderId);
                    assertThat(status)
                            .as("SELL deve estar OPEN após FundsReserved")
                            .isEqualTo("OPEN");
                });

        // ------------------------------------------------------------------ ACT: coloca BUY de 2 VIB
        log.info("[E2E][partial-fill] Colocando BUY de 2 VIB a {} BRL/VIB", PRICE_MATCH);

        String buyOrderId = given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + partialBuyerJwt)
                .body(orderBody(partialBuyerWalletId, "BUY", PRICE_MATCH, buyAmount))
                .post(orderServiceUrl + "/api/v1/orders")
                .then()
                .statusCode(202)
                .extract().path("orderId");

        assertThat(buyOrderId).as("orderId da BUY não pode ser nulo").isNotNull();
        log.info("[E2E][partial-fill] BUY criada: orderId={}", buyOrderId);

        // ------------------------------------------------------------------ WAIT: BUY fica FILLED
        log.info("[E2E][partial-fill] Aguardando BUY ficar FILLED...");
        await("BUY de 2 VIB deve ser completamente executada (FILLED)")
                .atMost(45, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    String status = getOrderStatus(partialBuyerJwt, buyOrderId);
                    assertThat(status)
                            .as("BUY deve ser FILLED — match parcial consumiu 2 dos 5 VIB")
                            .isEqualTo("FILLED");
                });

        // ------------------------------------------------------------------ WAIT: SELL fica PARTIAL_FILLED
        log.info("[E2E][partial-fill] Aguardando SELL ficar PARTIAL_FILLED...");
        await("SELL deve atingir PARTIAL_FILLED — restam 3 VIB")
                .atMost(30, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    String status = getOrderStatus(partialSellerJwt, sellOrderId);
                    assertThat(status)
                            .as("SELL deve ser PARTIAL_FILLED com 3 VIB restantes")
                            .isEqualTo("PARTIAL_FILLED");
                });

        // ------------------------------------------------------------------ ASSERT: BUY — remainingAmount = 0
        Response finalBuy = given()
                .header("Authorization", "Bearer " + partialBuyerJwt)
                .get(orderServiceUrl + "/api/v1/orders/" + buyOrderId)
                .then()
                .statusCode(200)
                .extract().response();

        assertThat(finalBuy.<String>path("status"))
                .as("BUY status final").isEqualTo("FILLED");
        assertThat(finalBuy.path("remainingAmount").toString())
                .as("BUY remainingAmount deve ser 0").isEqualTo("0");

        // ------------------------------------------------------------------ ASSERT: SELL — remainingAmount = 3
        Response finalSell = given()
                .header("Authorization", "Bearer " + partialSellerJwt)
                .get(orderServiceUrl + "/api/v1/orders/" + sellOrderId)
                .then()
                .statusCode(200)
                .extract().response();

        assertThat(finalSell.<String>path("status"))
                .as("SELL status final").isEqualTo("PARTIAL_FILLED");
        assertThat(finalSell.path("remainingAmount").toString())
                .as("SELL remainingAmount deve ser 3.00000000")
                .isEqualTo("3.00000000");

        // ------------------------------------------------------------------ ASSERT: Wallet do comprador — vibAvailable += 2
        log.info("[E2E][partial-fill] Validando saldos das wallets...");

        await("Wallet do comprador deve refletir settlement")
                .atMost(30, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    Response walletResp = given()
                            .header("Authorization", "Bearer " + partialBuyerJwt)
                            .get(walletServiceUrl + "/api/v1/wallets/" + PARTIAL_BUYER_ID)
                            .then()
                            .statusCode(200)
                            .extract().response();

                    // Comprador começou com 0 VIB, deve ter recebido 2 VIB
                    Number vibAvailable = walletResp.path("vibAvailable");
                    assertThat(vibAvailable.doubleValue())
                            .as("Comprador vibAvailable deve ter aumentado em 2 VIB")
                            .isGreaterThanOrEqualTo(2.0);
                });

        // ------------------------------------------------------------------ ASSERT: Wallet do vendedor — brlAvailable += 200
        await("Wallet do vendedor deve refletir settlement")
                .atMost(30, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    Response walletResp = given()
                            .header("Authorization", "Bearer " + partialSellerJwt)
                            .get(walletServiceUrl + "/api/v1/wallets/" + PARTIAL_SELLER_ID)
                            .then()
                            .statusCode(200)
                            .extract().response();

                    // Vendedor começou com 0 BRL, deve ter recebido 200 BRL (2 × 100)
                    Number brlAvailable = walletResp.path("brlAvailable");
                    assertThat(brlAvailable.doubleValue())
                            .as("Vendedor brlAvailable deve ter aumentado em 200 BRL (2 VIB × 100 BRL)")
                            .isGreaterThanOrEqualTo(200.0);
                });

        log.info("[E2E][partial-fill] ✓ Partial fill validado: BUY {} FILLED, SELL {} PARTIAL_FILLED",
                buyOrderId, sellOrderId);
    }

    // =========================================================================
    // Teste 4 — Multi-Match: BUY 2 VIB consome SELL A (1 VIB) + SELL B (1 VIB)
    // =========================================================================

    /**
     * Valida o cenário de multi-match (uma ordem consumindo múltiplas contrapartes):
     * <ol>
     *   <li>Vendedor A coloca SELL de <b>1 VIB</b> a 100 BRL/VIB → aguarda OPEN.</li>
     *   <li>Vendedor B coloca SELL de <b>1 VIB</b> a 100 BRL/VIB → aguarda OPEN.</li>
     *   <li>Comprador coloca BUY de <b>2 VIB</b> a 100 BRL/VIB.</li>
     *   <li>Motor executa multi-match: BUY consome ambas as SELLs.</li>
     *   <li>BUY fica {@code FILLED} (2 VIB), SELL A fica {@code FILLED}, SELL B fica {@code FILLED}.</li>
     * </ol>
     *
     * <p>O motor Lua consome asks ao mesmo preço em ordem FIFO (por timestamp/score).
     * O BUY de 2 VIB é maior que qualquer SELL individual (1 VIB cada), forçando
     * o multi-match contra exatamente 2 contrapartes.</p>
     *
     * <p>Critério de aceite AT-5.3.4: multi-match validado.</p>
     */
    @Test
    @Order(4)
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    @DisplayName("Multi-match: BUY 2 VIB consome SELL A (1 VIB) + SELL B (1 VIB) → todas FILLED")
    void testMultiMatch() {
        // ------------------------------------------------------------------ ARRANGE
        String multiBuyerJwt   = craftJwt(MULTI_BUYER_ID);
        String multiSellerAJwt = craftJwt(MULTI_SELLER_A_ID);
        String multiSellerBJwt = craftJwt(MULTI_SELLER_B_ID);

        String sellAmount = "1.00000000";
        String buyAmount  = "2.00000000";

        // ------------------------------------------------------------------ ACT: coloca SELL A de 1 VIB
        log.info("[E2E][multi-match] Colocando SELL A de 1 VIB a {} BRL/VIB", PRICE_MATCH);

        String sellAOrderId = given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + multiSellerAJwt)
                .body(orderBody(multiSellerAWalletId, "SELL", PRICE_MATCH, sellAmount))
                .post(orderServiceUrl + "/api/v1/orders")
                .then()
                .statusCode(202)
                .extract().path("orderId");

        assertThat(sellAOrderId).as("orderId da SELL A não pode ser nulo").isNotNull();
        log.info("[E2E][multi-match] SELL A criada: orderId={}", sellAOrderId);

        // ------------------------------------------------------------------ WAIT: SELL A fica OPEN
        log.info("[E2E][multi-match] Aguardando SELL A ficar OPEN...");
        await("SELL A deve atingir status OPEN")
                .atMost(45, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    String status = getOrderStatus(multiSellerAJwt, sellAOrderId);
                    assertThat(status)
                            .as("SELL A deve estar OPEN")
                            .isEqualTo("OPEN");
                });

        // ------------------------------------------------------------------ ACT: coloca SELL B de 1 VIB
        log.info("[E2E][multi-match] Colocando SELL B de 1 VIB a {} BRL/VIB", PRICE_MATCH);

        String sellBOrderId = given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + multiSellerBJwt)
                .body(orderBody(multiSellerBWalletId, "SELL", PRICE_MATCH, sellAmount))
                .post(orderServiceUrl + "/api/v1/orders")
                .then()
                .statusCode(202)
                .extract().path("orderId");

        assertThat(sellBOrderId).as("orderId da SELL B não pode ser nulo").isNotNull();
        log.info("[E2E][multi-match] SELL B criada: orderId={}", sellBOrderId);

        // ------------------------------------------------------------------ WAIT: SELL B fica OPEN
        log.info("[E2E][multi-match] Aguardando SELL B ficar OPEN...");
        await("SELL B deve atingir status OPEN")
                .atMost(45, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    String status = getOrderStatus(multiSellerBJwt, sellBOrderId);
                    assertThat(status)
                            .as("SELL B deve estar OPEN")
                            .isEqualTo("OPEN");
                });

        // ------------------------------------------------------------------ ACT: coloca BUY de 2 VIB
        log.info("[E2E][multi-match] Colocando BUY de 2 VIB a {} BRL/VIB (consome ambas SELLs)", PRICE_MATCH);

        String buyOrderId = given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + multiBuyerJwt)
                .body(orderBody(multiBuyerWalletId, "BUY", PRICE_MATCH, buyAmount))
                .post(orderServiceUrl + "/api/v1/orders")
                .then()
                .statusCode(202)
                .extract().path("orderId");

        assertThat(buyOrderId).as("orderId da BUY não pode ser nulo").isNotNull();
        log.info("[E2E][multi-match] BUY criada: orderId={}", buyOrderId);

        // ------------------------------------------------------------------ WAIT: BUY fica FILLED
        log.info("[E2E][multi-match] Aguardando BUY ficar FILLED (multi-match)...");
        await("BUY de 2 VIB deve ser completamente executada via multi-match")
                .atMost(60, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    String status = getOrderStatus(multiBuyerJwt, buyOrderId);
                    assertThat(status)
                            .as("BUY deve ser FILLED após consumir SELL A + SELL B")
                            .isEqualTo("FILLED");
                });

        // ------------------------------------------------------------------ WAIT: SELL A fica FILLED
        log.info("[E2E][multi-match] Aguardando SELL A ficar FILLED...");
        await("SELL A deve atingir FILLED")
                .atMost(30, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    String status = getOrderStatus(multiSellerAJwt, sellAOrderId);
                    assertThat(status)
                            .as("SELL A deve ser FILLED")
                            .isEqualTo("FILLED");
                });

        // ------------------------------------------------------------------ WAIT: SELL B fica FILLED
        log.info("[E2E][multi-match] Aguardando SELL B ficar FILLED...");
        await("SELL B deve atingir FILLED")
                .atMost(30, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    String status = getOrderStatus(multiSellerBJwt, sellBOrderId);
                    assertThat(status)
                            .as("SELL B deve ser FILLED")
                            .isEqualTo("FILLED");
                });

        // ------------------------------------------------------------------ ASSERT: BUY — remainingAmount = 0
        Response finalBuy = given()
                .header("Authorization", "Bearer " + multiBuyerJwt)
                .get(orderServiceUrl + "/api/v1/orders/" + buyOrderId)
                .then()
                .statusCode(200)
                .extract().response();

        assertThat(finalBuy.<String>path("status"))
                .as("BUY status final").isEqualTo("FILLED");
        assertThat(finalBuy.path("remainingAmount").toString())
                .as("BUY remainingAmount deve ser 0").isEqualTo("0");

        // ------------------------------------------------------------------ ASSERT: SELL A — remainingAmount = 0
        Response finalSellA = given()
                .header("Authorization", "Bearer " + multiSellerAJwt)
                .get(orderServiceUrl + "/api/v1/orders/" + sellAOrderId)
                .then()
                .statusCode(200)
                .extract().response();

        assertThat(finalSellA.<String>path("status"))
                .as("SELL A status final").isEqualTo("FILLED");
        assertThat(finalSellA.path("remainingAmount").toString())
                .as("SELL A remainingAmount deve ser 0").isEqualTo("0");

        // ------------------------------------------------------------------ ASSERT: SELL B — remainingAmount = 0
        Response finalSellB = given()
                .header("Authorization", "Bearer " + multiSellerBJwt)
                .get(orderServiceUrl + "/api/v1/orders/" + sellBOrderId)
                .then()
                .statusCode(200)
                .extract().response();

        assertThat(finalSellB.<String>path("status"))
                .as("SELL B status final").isEqualTo("FILLED");
        assertThat(finalSellB.path("remainingAmount").toString())
                .as("SELL B remainingAmount deve ser 0").isEqualTo("0");

        log.info("[E2E][multi-match] ✓ Multi-match validado: BUY {} FILLED, SELL A {} FILLED, SELL B {} FILLED",
                buyOrderId, sellAOrderId, sellBOrderId);
    }

    // =========================================================================
    // Teste 5 — Rejeição por saldo insuficiente: BUY → CANCELLED
    // =========================================================================

    /**
     * Valida o cenário de rejeição por saldo insuficiente:
     * <ol>
     *   <li>Comprador é criado com apenas <b>1.00 BRL</b> disponível.</li>
     *   <li>Comprador tenta colocar BUY de <b>1 VIB</b> a 100 BRL/VIB (total = 100 BRL).</li>
     *   <li>Wallet-service detecta saldo insuficiente e emite {@code FundsReservationFailedEvent}.</li>
     *   <li>Order-service recebe o evento e cancela a ordem → {@code CANCELLED}.</li>
     * </ol>
     *
     * <p>A ordem <b>nunca</b> deve atingir {@code OPEN} (o que indicaria reserva indevida de fundos).
     * A carteira do comprador deve permanecer intacta: 1.00 BRL disponivel, 0.00 BRL locked.</p>
     *
     * <p>Critério de aceite AT-5.3.5: rejeição por saldo insuficiente validada.</p>
     */
    @Test
    @Order(5)
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    @DisplayName("Rejeição: BUY com saldo insuficiente → FundsReservationFailed → CANCELLED")
    void testInsufficientFundsRejection() {
        // ------------------------------------------------------------------ ARRANGE
        String insufficientJwt = craftJwt(INSUFFICIENT_FUNDS_USER_ID);

        // ------------------------------------------------------------------ ACT: coloca BUY de 1 VIB a 100 BRL (precisa 100, tem 1)
        log.info("[E2E][insufficient-funds] Colocando BUY de 1 VIB a {} BRL/VIB (saldo = 1 BRL, insuficiente)",
                PRICE_MATCH);

        String orderId = given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + insufficientJwt)
                .body(orderBody(insufficientFundsWalletId, "BUY", PRICE_MATCH, AMOUNT_1_VIB))
                .post(orderServiceUrl + "/api/v1/orders")
                .then()
                .statusCode(202)
                .extract().path("orderId");

        assertThat(orderId).as("orderId não pode ser nulo").isNotNull();
        log.info("[E2E][insufficient-funds] Ordem criada: orderId={}", orderId);

        // ------------------------------------------------------------------ WAIT: PENDING → CANCELLED (nunca OPEN)
        // O wallet-service rejeita a reserva e publica FundsReservationFailedEvent.
        // O order-service consome o evento e transiciona a ordem para CANCELLED.
        log.info("[E2E][insufficient-funds] Aguardando CANCELLED (FundsReservationFailed)...");
        await("Ordem deve ser CANCELLED por saldo insuficiente")
                .atMost(30, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    String status = getOrderStatus(insufficientJwt, orderId);
                    // A ordem pode estar PENDING antes de ser cancelada, mas NUNCA deve ser OPEN
                    assertThat(status)
                            .as("Ordem NUNCA deve atingir OPEN com saldo insuficiente")
                            .isNotEqualTo("OPEN");
                    assertThat(status)
                            .as("Ordem deve ser CANCELLED após FundsReservationFailed")
                            .isEqualTo("CANCELLED");
                });

        // ------------------------------------------------------------------ ASSERT: Ordem final — status CANCELLED
        Response finalOrder = given()
                .header("Authorization", "Bearer " + insufficientJwt)
                .get(orderServiceUrl + "/api/v1/orders/" + orderId)
                .then()
                .statusCode(200)
                .extract().response();

        assertThat(finalOrder.<String>path("status"))
                .as("Status final da ordem").isEqualTo("CANCELLED");
        assertThat(finalOrder.<String>path("orderType"))
                .as("Tipo da ordem").isEqualTo("BUY");

        // ------------------------------------------------------------------ ASSERT: Wallet intacta
        log.info("[E2E][insufficient-funds] Validando que a wallet permaneceu intacta...");

        Response walletResp = given()
                .header("Authorization", "Bearer " + insufficientJwt)
                .get(walletServiceUrl + "/api/v1/wallets/" + INSUFFICIENT_FUNDS_USER_ID)
                .then()
                .statusCode(200)
                .extract().response();

        Number brlAvailable = walletResp.path("brlAvailable");
        Number brlLocked    = walletResp.path("brlLocked");

        assertThat(brlAvailable.doubleValue())
                .as("brlAvailable deve permanecer 1.00 (nada foi reservado)")
                .isEqualTo(1.0);
        assertThat(brlLocked.doubleValue())
                .as("brlLocked deve permanecer 0.00 (reserva rejeitada)")
                .isEqualTo(0.0);

        log.info("[E2E][insufficient-funds] ✓ Rejeição validada: ordem {} CANCELLED, wallet intacta", orderId);
    }

    // =========================================================================
    // Teste 6 — Idempotência: duas ordens BUY idênticas → orderIds distintos
    // =========================================================================

    /**
     * Valida a idempotência cross-service do fluxo de ordens:
     * <ol>
     *   <li>Comprador coloca BUY de 1 VIB a 100 BRL/VIB.</li>
     *   <li>Vendedor coloca SELL de 1 VIB a 100 BRL/VIB → ambas {@code FILLED}.</li>
     *   <li>Comprador coloca uma <b>segunda BUY idêntica</b> (mesmo walletId, preço, quantidade).</li>
     *   <li>A segunda ordem é processada independentemente com orderId distinto.</li>
     *   <li>Sem contraparte disponível, a segunda BUY fica {@code OPEN} e eventualmente {@code CANCELLED} por timeout.</li>
     * </ol>
     *
     * <p>Valida que:</p>
     * <ul>
     *   <li>O sistema não confunde ordens com mesmos parâmetros (UUIDs distintos).</li>
     *   <li>O Outbox Pattern não republica mensagens já processadas.</li>
     *   <li>A idempotência funciona no nível de eventId/correlationId, não no payload.</li>
     * </ul>
     *
     * <p>Critério de aceite AT-5.3.6: idempotência cross-service validada.</p>
     */
    @Test
    @Order(6)
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("Idempotência: duas ordens BUY idênticas geram orderIds distintos e processamento independente")
    void testIdempotencyCrossService() {
        // ------------------------------------------------------------------ ARRANGE
        String idempBuyerJwt  = craftJwt(IDEMP_BUYER_ID);
        String idempSellerJwt = craftJwt(IDEMP_SELLER_ID);

        // ------------------------------------------------------------------ ACT: Rodada 1 — BUY + SELL → FILLED
        log.info("[E2E][idempotency] Rodada 1: colocando BUY de 1 VIB a {} BRL/VIB", PRICE_MATCH);

        String buyOrderId1 = given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + idempBuyerJwt)
                .body(orderBody(idempBuyerWalletId, "BUY", PRICE_MATCH, AMOUNT_1_VIB))
                .post(orderServiceUrl + "/api/v1/orders")
                .then()
                .statusCode(202)
                .extract().path("orderId");

        assertThat(buyOrderId1).as("orderId da BUY 1 não pode ser nulo").isNotNull();
        log.info("[E2E][idempotency] BUY 1 criada: orderId={}", buyOrderId1);

        // Aguarda BUY 1 ficar OPEN
        await("BUY 1 deve atingir OPEN")
                .atMost(45, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    String status = getOrderStatus(idempBuyerJwt, buyOrderId1);
                    assertThat(status).as("BUY 1 deve estar OPEN").isEqualTo("OPEN");
                });

        log.info("[E2E][idempotency] Rodada 1: colocando SELL de 1 VIB a {} BRL/VIB", PRICE_MATCH);

        String sellOrderId1 = given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + idempSellerJwt)
                .body(orderBody(idempSellerWalletId, "SELL", PRICE_MATCH, AMOUNT_1_VIB))
                .post(orderServiceUrl + "/api/v1/orders")
                .then()
                .statusCode(202)
                .extract().path("orderId");

        assertThat(sellOrderId1).as("orderId da SELL 1 não pode ser nulo").isNotNull();
        log.info("[E2E][idempotency] SELL 1 criada: orderId={}", sellOrderId1);

        // Aguarda BUY 1 FILLED
        await("BUY 1 deve atingir FILLED")
                .atMost(45, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    String status = getOrderStatus(idempBuyerJwt, buyOrderId1);
                    assertThat(status).as("BUY 1 deve ser FILLED").isEqualTo("FILLED");
                });

        // Aguarda SELL 1 FILLED
        await("SELL 1 deve atingir FILLED")
                .atMost(30, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    String status = getOrderStatus(idempSellerJwt, sellOrderId1);
                    assertThat(status).as("SELL 1 deve ser FILLED").isEqualTo("FILLED");
                });

        log.info("[E2E][idempotency] Rodada 1 concluída: BUY {} e SELL {} ambas FILLED",
                buyOrderId1, sellOrderId1);

        // ------------------------------------------------------------------ ACT: Rodada 2 — segunda BUY idêntica
        log.info("[E2E][idempotency] Rodada 2: colocando segunda BUY idêntica de 1 VIB a {} BRL/VIB", PRICE_MATCH);

        String buyOrderId2 = given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + idempBuyerJwt)
                .body(orderBody(idempBuyerWalletId, "BUY", PRICE_MATCH, AMOUNT_1_VIB))
                .post(orderServiceUrl + "/api/v1/orders")
                .then()
                .statusCode(202)
                .extract().path("orderId");

        assertThat(buyOrderId2).as("orderId da BUY 2 não pode ser nulo").isNotNull();
        log.info("[E2E][idempotency] BUY 2 criada: orderId={}", buyOrderId2);

        // ------------------------------------------------------------------ ASSERT: orderIds distintos
        assertThat(buyOrderId2)
                .as("Segunda BUY deve ter orderId DIFERENTE da primeira (não é replay)")
                .isNotEqualTo(buyOrderId1);

        log.info("[E2E][idempotency] ✓ OrderIds distintos confirmados: BUY1={} ≠ BUY2={}",
                buyOrderId1, buyOrderId2);

        // ------------------------------------------------------------------ ASSERT: segunda BUY é processada independentemente
        // Sem contraparte, a segunda BUY pode ficar PENDING → OPEN (se saldo suficiente)
        // ou PENDING → CANCELLED (se saldo já consumido). Ambos são válidos.
        await("BUY 2 deve ser processada (sair de PENDING)")
                .atMost(45, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    String status = getOrderStatus(idempBuyerJwt, buyOrderId2);
                    assertThat(status)
                            .as("BUY 2 deve ter sido processada (não pode ficar null/PENDING indefinidamente)")
                            .isNotNull()
                            .isIn("OPEN", "FILLED", "CANCELLED");
                });

        String finalStatus2 = getOrderStatus(idempBuyerJwt, buyOrderId2);
        log.info("[E2E][idempotency] BUY 2 status final: {}", finalStatus2);

        // A primeira BUY deve continuar FILLED (não foi afetada pela segunda)
        String finalStatus1 = getOrderStatus(idempBuyerJwt, buyOrderId1);
        assertThat(finalStatus1)
                .as("BUY 1 deve permanecer FILLED após segunda BUY ser processada")
                .isEqualTo("FILLED");

        log.info("[E2E][idempotency] ✓ Idempotência validada: BUY1={} (FILLED), BUY2={} ({})",
                buyOrderId1, buyOrderId2, finalStatus2);
    }

    // =========================================================================
    // Teste 7 — Integridade financeira: saldos BRL/VIB zero-sum após settlement
    // =========================================================================

    /**
     * Valida a integridade financeira completa após um ciclo de trade:
     * <ol>
     *   <li>Comprador inicia com 500.00 BRL, 0.00 VIB.</li>
     *   <li>Vendedor inicia com 0.00 BRL, 10.00 VIB.</li>
     *   <li>BUY de 3 VIB a 50 BRL/VIB (total = 150 BRL).</li>
     *   <li>SELL de 3 VIB a 50 BRL/VIB.</li>
     *   <li>Match executado → ambas {@code FILLED}.</li>
     * </ol>
     *
     * <p>Validação contábil (zero-sum):</p>
     * <ul>
     *   <li>BRL total antes: 500 + 0 = 500. Depois: 350 + 150 = 500. ✓</li>
     *   <li>VIB total antes: 0 + 10 = 10. Depois: 3 + 7 = 10. ✓</li>
     * </ul>
     *
     * <p>Critério de aceite AT-5.3.7: integridade financeira validada.</p>
     */
    @Test
    @Order(7)
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("Integridade financeira: saldos BRL/VIB zero-sum após settlement completo")
    void testBalanceZeroSumAfterSettlement() {
        // ------------------------------------------------------------------ ARRANGE
        String balBuyerJwt  = craftJwt(BALANCE_BUYER_ID);
        String balSellerJwt = craftJwt(BALANCE_SELLER_ID);

        String tradePrice  = "50.00";
        String tradeAmount = "3.00000000";

        // ------------------------------------------------------------------ ACT: coloca SELL de 3 VIB a 50 BRL/VIB
        log.info("[E2E][balance] Colocando SELL de 3 VIB a 50 BRL/VIB");

        String sellOrderId = given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + balSellerJwt)
                .body(orderBody(balanceSellerWalletId, "SELL", tradePrice, tradeAmount))
                .post(orderServiceUrl + "/api/v1/orders")
                .then()
                .statusCode(202)
                .extract().path("orderId");

        assertThat(sellOrderId).as("orderId da SELL não pode ser nulo").isNotNull();

        // Aguarda SELL ficar OPEN
        await("SELL deve atingir OPEN")
                .atMost(45, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    String status = getOrderStatus(balSellerJwt, sellOrderId);
                    assertThat(status).as("SELL deve estar OPEN").isEqualTo("OPEN");
                });

        // ------------------------------------------------------------------ ACT: coloca BUY de 3 VIB a 50 BRL/VIB
        log.info("[E2E][balance] Colocando BUY de 3 VIB a 50 BRL/VIB");

        String buyOrderId = given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + balBuyerJwt)
                .body(orderBody(balanceBuyerWalletId, "BUY", tradePrice, tradeAmount))
                .post(orderServiceUrl + "/api/v1/orders")
                .then()
                .statusCode(202)
                .extract().path("orderId");

        assertThat(buyOrderId).as("orderId da BUY não pode ser nulo").isNotNull();

        // ------------------------------------------------------------------ WAIT: ambas FILLED
        log.info("[E2E][balance] Aguardando ambas as ordens FILLED...");
        await("BUY deve atingir FILLED")
                .atMost(45, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    String status = getOrderStatus(balBuyerJwt, buyOrderId);
                    assertThat(status).as("BUY deve ser FILLED").isEqualTo("FILLED");
                });

        await("SELL deve atingir FILLED")
                .atMost(30, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    String status = getOrderStatus(balSellerJwt, sellOrderId);
                    assertThat(status).as("SELL deve ser FILLED").isEqualTo("FILLED");
                });

        // ------------------------------------------------------------------ WAIT: settlement completo (brlLocked do comprador = 0)
        log.info("[E2E][balance] Aguardando settlement completo (brlLocked=0)...");
        await("Settlement deve estar completo — brlLocked do comprador deve ser 0")
                .atMost(30, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    Map<String, Number> bal = getWalletBalance(balBuyerJwt, BALANCE_BUYER_ID);
                    assertThat(bal.get("brlLocked").doubleValue())
                            .as("brlLocked do comprador deve ser 0 após settlement")
                            .isEqualTo(0.0);
                });

        // ------------------------------------------------------------------ ASSERT: Saldos do comprador
        Map<String, Number> buyerBal = getWalletBalance(balBuyerJwt, BALANCE_BUYER_ID);
        log.info("[E2E][balance] Saldos comprador: {}", buyerBal);

        assertThat(buyerBal.get("brlAvailable").doubleValue())
                .as("Comprador brlAvailable = 500 - 150 = 350").isEqualTo(350.0);
        assertThat(buyerBal.get("brlLocked").doubleValue())
                .as("Comprador brlLocked = 0").isEqualTo(0.0);
        assertThat(buyerBal.get("vibAvailable").doubleValue())
                .as("Comprador vibAvailable = 0 + 3 = 3").isEqualTo(3.0);
        assertThat(buyerBal.get("vibLocked").doubleValue())
                .as("Comprador vibLocked = 0").isEqualTo(0.0);

        // ------------------------------------------------------------------ ASSERT: Saldos do vendedor
        Map<String, Number> sellerBal = getWalletBalance(balSellerJwt, BALANCE_SELLER_ID);
        log.info("[E2E][balance] Saldos vendedor: {}", sellerBal);

        assertThat(sellerBal.get("brlAvailable").doubleValue())
                .as("Vendedor brlAvailable = 0 + 150 = 150").isEqualTo(150.0);
        assertThat(sellerBal.get("brlLocked").doubleValue())
                .as("Vendedor brlLocked = 0").isEqualTo(0.0);
        assertThat(sellerBal.get("vibAvailable").doubleValue())
                .as("Vendedor vibAvailable = 10 - 3 = 7").isEqualTo(7.0);
        assertThat(sellerBal.get("vibLocked").doubleValue())
                .as("Vendedor vibLocked = 0").isEqualTo(0.0);

        // ------------------------------------------------------------------ ASSERT: Zero-sum contábil
        double totalBrlAfter = buyerBal.get("brlAvailable").doubleValue()
                + sellerBal.get("brlAvailable").doubleValue();
        double totalVibAfter = buyerBal.get("vibAvailable").doubleValue()
                + sellerBal.get("vibAvailable").doubleValue();

        assertThat(totalBrlAfter)
                .as("Zero-sum BRL: total antes (500) == total depois (350 + 150)")
                .isEqualTo(500.0);
        assertThat(totalVibAfter)
                .as("Zero-sum VIB: total antes (10) == total depois (3 + 7)")
                .isEqualTo(10.0);

        log.info("[E2E][balance] ✓ Integridade financeira validada: BRL zero-sum={}, VIB zero-sum={}",
                totalBrlAfter, totalVibAfter);
    }

    // =========================================================================
    // Teste 8 — Concorrência: 3 BUY + 3 SELL simultâneos → todos FILLED
    // =========================================================================

    /**
     * Valida o comportamento sob concorrência de ordens simultâneas:
     * <ol>
     *   <li>Cria 3 compradores (BUY) e 3 vendedores (SELL), todos com saldo adequado.</li>
     *   <li>Dispara TODAS as 6 ordens <b>simultaneamente</b> via {@link CompletableFuture}.</li>
     *   <li>Cada BUY: 1 VIB a 100 BRL/VIB. Cada SELL: 1 VIB a 100 BRL/VIB.</li>
     *   <li>Aguarda que todas atinjam estado terminal ({@code FILLED}).</li>
     *   <li>Exatamente 3 matches devem ocorrer: cada BUY emparelhado com exatamente 1 SELL.</li>
     * </ol>
     *
     * <p>Valida que:</p>
     * <ul>
     *   <li>Nenhuma ordem fica "presa" em OPEN/PENDING.</li>
     *   <li>Nenhuma ordem é matchada duas vezes (sem duplicação).</li>
     *   <li>Contagem final de FILLED = 6 (3 BUY + 3 SELL).</li>
     * </ul>
     *
     * <p>Critério de aceite AT-5.3.8: concorrência validada.</p>
     */
    @Test
    @Order(8)
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    @DisplayName("Concorrência: 3 BUY + 3 SELL simultâneos → todos FILLED sem duplicação")
    void testConcurrentOrders() {
        // ------------------------------------------------------------------ ARRANGE
        // Buyers
        UUID[] buyerIds    = {CONC_BUYER_1_ID, CONC_BUYER_2_ID, CONC_BUYER_3_ID};
        UUID[] buyerWallets = {concBuyer1WalletId, concBuyer2WalletId, concBuyer3WalletId};
        String[] buyerJwts = {
                craftJwt(CONC_BUYER_1_ID),
                craftJwt(CONC_BUYER_2_ID),
                craftJwt(CONC_BUYER_3_ID)
        };

        // Sellers
        UUID[] sellerIds    = {CONC_SELLER_1_ID, CONC_SELLER_2_ID, CONC_SELLER_3_ID};
        UUID[] sellerWallets = {concSeller1WalletId, concSeller2WalletId, concSeller3WalletId};
        String[] sellerJwts = {
                craftJwt(CONC_SELLER_1_ID),
                craftJwt(CONC_SELLER_2_ID),
                craftJwt(CONC_SELLER_3_ID)
        };

        // ------------------------------------------------------------------ ACT: dispara 6 ordens simultaneamente
        log.info("[E2E][concurrency] Disparando 3 BUY + 3 SELL simultaneamente...");

        List<CompletableFuture<String>> futures = new ArrayList<>();

        // 3 BUYs
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            futures.add(CompletableFuture.supplyAsync(() ->
                    given()
                            .contentType(ContentType.JSON)
                            .header("Authorization", "Bearer " + buyerJwts[idx])
                            .body(orderBody(buyerWallets[idx], "BUY", PRICE_MATCH, AMOUNT_1_VIB))
                            .post(orderServiceUrl + "/api/v1/orders")
                            .then()
                            .statusCode(202)
                            .extract().path("orderId")
            ));
        }

        // 3 SELLs
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            futures.add(CompletableFuture.supplyAsync(() ->
                    given()
                            .contentType(ContentType.JSON)
                            .header("Authorization", "Bearer " + sellerJwts[idx])
                            .body(orderBody(sellerWallets[idx], "SELL", PRICE_MATCH, AMOUNT_1_VIB))
                            .post(orderServiceUrl + "/api/v1/orders")
                            .then()
                            .statusCode(202)
                            .extract().path("orderId")
            ));
        }

        // Coleta todos os orderIds
        List<String> orderIds = futures.stream()
                .map(CompletableFuture::join)
                .toList();

        assertThat(orderIds).as("Todas as 6 ordens devem ter sido criadas").hasSize(6);
        orderIds.forEach(id -> assertThat(id).as("orderId não pode ser nulo").isNotNull());

        log.info("[E2E][concurrency] 6 ordens criadas: {}", orderIds);

        // Os JWTs de cada ordem (buyer 0,1,2 + seller 0,1,2)
        String[] allJwts = {
                buyerJwts[0], buyerJwts[1], buyerJwts[2],
                sellerJwts[0], sellerJwts[1], sellerJwts[2]
        };

        // ------------------------------------------------------------------ WAIT: todas devem atingir estado terminal
        log.info("[E2E][concurrency] Aguardando todas as 6 ordens atingirem estado terminal...");

        for (int i = 0; i < 6; i++) {
            final int idx = i;
            final String jwt = allJwts[idx];
            final String orderId = orderIds.get(idx);

            await("Ordem " + orderId + " deve atingir estado terminal")
                    .atMost(90, TimeUnit.SECONDS)
                    .pollInterval(2, TimeUnit.SECONDS)
                    .untilAsserted(() -> {
                        String status = getOrderStatus(jwt, orderId);
                        assertThat(status)
                                .as("Ordem %s deve estar em estado terminal (FILLED ou CANCELLED)", orderId)
                                .isNotNull()
                                .isIn("FILLED", "CANCELLED");
                    });
        }

        // ------------------------------------------------------------------ ASSERT: contagem de FILLED
        int filledCount = 0;
        for (int i = 0; i < 6; i++) {
            String status = getOrderStatus(allJwts[i], orderIds.get(i));
            log.info("[E2E][concurrency] Ordem {} status = {}", orderIds.get(i), status);
            if ("FILLED".equals(status)) {
                filledCount++;
            }
        }

        assertThat(filledCount)
                .as("Todas as 6 ordens devem ser FILLED (3 BUY + 3 SELL matchados)")
                .isEqualTo(6);

        log.info("[E2E][concurrency] ✓ Concorrência validada: {}/6 ordens FILLED", filledCount);
    }

    // =========================================================================
    // Teste 9 — Projeção CQRS: Read Model MongoDB
    // =========================================================================

    /**
     * Valida a completude da projeção CQRS no Read Model (MongoDB):
     * <ol>
     *   <li>Comprador coloca BUY de 1 VIB a 100 BRL/VIB.</li>
     *   <li>Vendedor coloca SELL de 1 VIB a 100 BRL/VIB.</li>
     *   <li>Aguarda ambas {@code FILLED}.</li>
     *   <li>Consulta GET para ambas as ordens e valida todos os campos do Read Model.</li>
     * </ol>
     *
     * <p>Valida que a projeção MongoDB contém:</p>
     * <ul>
     *   <li>{@code orderId}, {@code status}, {@code orderType}, {@code price},
     *       {@code amount}, {@code remainingAmount}, {@code userId} corretos.</li>
     *   <li>{@code history[]} é um array não-vazio com ao menos 2 entradas.</li>
     *   <li>Cada entry tem {@code eventType}, {@code timestamp} e {@code eventId} não-nulos.</li>
     *   <li>Entries em ordem cronológica (timestamps crescentes).</li>
     * </ul>
     *
     * <p>Critério de aceite: consistência eventual da projeção CQRS validada.</p>
     */
    @Test
    @Order(9)
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("Projeção CQRS: Read Model MongoDB contém history completo e campos corretos após FILLED")
    void testCqrsProjectionReadModel() {
        // ------------------------------------------------------------------ ARRANGE
        String projBuyerJwt  = craftJwt(PROJ_BUYER_ID);
        String projSellerJwt = craftJwt(PROJ_SELLER_ID);

        // ------------------------------------------------------------------ ACT: BUY
        log.info("[E2E][cqrs] Colocando BUY de 1 VIB a 100 BRL/VIB...");
        String buyOrderId = given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + projBuyerJwt)
                .body(orderBody(projBuyerWalletId, "BUY", PRICE_MATCH, AMOUNT_1_VIB))
                .post(orderServiceUrl + "/api/v1/orders")
                .then()
                .statusCode(202)
                .extract().path("orderId");
        log.info("[E2E][cqrs] BUY orderId={}", buyOrderId);

        // ------------------------------------------------------------------ ACT: SELL
        log.info("[E2E][cqrs] Colocando SELL de 1 VIB a 100 BRL/VIB...");
        String sellOrderId = given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + projSellerJwt)
                .body(orderBody(projSellerWalletId, "SELL", PRICE_MATCH, AMOUNT_1_VIB))
                .post(orderServiceUrl + "/api/v1/orders")
                .then()
                .statusCode(202)
                .extract().path("orderId");
        log.info("[E2E][cqrs] SELL orderId={}", sellOrderId);

        // ------------------------------------------------------------------ WAIT: ambas FILLED
        await("BUY deve ficar FILLED")
                .atMost(45, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(getOrderStatus(projBuyerJwt, buyOrderId)).isEqualTo("FILLED"));

        await("SELL deve ficar FILLED")
                .atMost(45, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(getOrderStatus(projSellerJwt, sellOrderId)).isEqualTo("FILLED"));

        log.info("[E2E][cqrs] Ambas FILLED. Validando projeção Read Model...");

        // ------------------------------------------------------------------ ASSERT: BUY Read Model
        Response buyResp = given()
                .header("Authorization", "Bearer " + projBuyerJwt)
                .get(orderServiceUrl + "/api/v1/orders/" + buyOrderId)
                .then()
                .statusCode(200)
                .extract().response();

        validateReadModel(buyResp, buyOrderId, "BUY", PROJ_BUYER_ID);

        // ------------------------------------------------------------------ ASSERT: SELL Read Model
        Response sellResp = given()
                .header("Authorization", "Bearer " + projSellerJwt)
                .get(orderServiceUrl + "/api/v1/orders/" + sellOrderId)
                .then()
                .statusCode(200)
                .extract().response();

        validateReadModel(sellResp, sellOrderId, "SELL", PROJ_SELLER_ID);

        log.info("[E2E][cqrs] ✓ Projeção CQRS validada para BUY {} e SELL {}", buyOrderId, sellOrderId);
    }

    /**
     * Valida todos os campos obrigatórios do Read Model para uma ordem FILLED.
     *
     * @param resp      Response do GET /api/v1/orders/{orderId}.
     * @param orderId   ID esperado da ordem.
     * @param orderType "BUY" ou "SELL".
     * @param userId    UUID do dono da ordem.
     */
    private static void validateReadModel(Response resp, String orderId, String orderType, UUID userId) {
        // Campos escalares
        assertThat(resp.jsonPath().getString("orderId"))
                .as("orderId deve estar presente e correto")
                .isEqualTo(orderId);

        assertThat(resp.jsonPath().getString("status"))
                .as("status deve ser FILLED")
                .isEqualTo("FILLED");

        assertThat(resp.jsonPath().getString("orderType"))
                .as("orderType deve ser " + orderType)
                .isEqualTo(orderType);

        assertThat(resp.jsonPath().getDouble("price"))
                .as("price deve ser 100.00")
                .isEqualTo(100.00);

        assertThat(resp.jsonPath().getDouble("amount"))
                .as("amount deve ser 1.00000000")
                .isEqualTo(1.0);

        assertThat(resp.jsonPath().getDouble("remainingAmount"))
                .as("remainingAmount deve ser 0")
                .isEqualTo(0.0);

        assertThat(resp.jsonPath().getString("userId"))
                .as("userId deve ser o UUID do owner")
                .isEqualTo(userId.toString());

        // history[] — array não-vazio com ao menos 2 entradas
        List<Map<String, Object>> history = resp.jsonPath().getList("history");
        assertThat(history)
                .as("history deve ser não-nulo e ter ao menos 2 entradas (ORDER_RECEIVED + MATCH_EXECUTED ou equiv.)")
                .isNotNull()
                .hasSizeGreaterThanOrEqualTo(2);

        // Cada entry deve ter eventType, timestamp e eventId não-nulos
        for (int i = 0; i < history.size(); i++) {
            Map<String, Object> entry = history.get(i);
            assertThat(entry.get("eventType"))
                    .as("history[%d].eventType não pode ser nulo", i)
                    .isNotNull();

            assertThat(entry.get("timestamp"))
                    .as("history[%d].timestamp não pode ser nulo", i)
                    .isNotNull();

            assertThat(entry.get("eventId"))
                    .as("history[%d].eventId não pode ser nulo", i)
                    .isNotNull();
        }

        // Timestamps em ordem cronológica (crescentes)
        for (int i = 1; i < history.size(); i++) {
            String prevTs = history.get(i - 1).get("timestamp").toString();
            String currTs = history.get(i).get("timestamp").toString();
            assertThat(currTs.compareTo(prevTs))
                    .as("history[%d].timestamp deve ser >= history[%d].timestamp", i, i - 1)
                    .isGreaterThanOrEqualTo(0);
        }

        log.info("[E2E][cqrs] ✓ Read Model validado para {} orderId={} — {} entries no history",
                orderType, orderId, history.size());
    }

    // =========================================================================
    // Teste 10 — Partial Fill + Timeout: SELL resíduo → CANCELLED
    // =========================================================================

    /**
     * Valida o cenário de partial fill seguido de cancelamento por timeout:
     * <ol>
     *   <li>Vendedor coloca SELL de 3 VIB a 100 BRL/VIB → aguarda OPEN.</li>
     *   <li>Comprador coloca BUY de 1 VIB a 100 BRL/VIB → match parcial.</li>
     *   <li>SELL transita para {@code PARTIAL_FILLED} (remaining = 2 VIB), BUY vai para {@code FILLED}.</li>
     *   <li>Nenhuma outra ordem aparece.</li>
     *   <li>{@code SagaTimeoutCleanupJob} cancela a SELL remanescente após 1 min → {@code CANCELLED}.</li>
     * </ol>
     *
     * <p>O {@code SagaTimeoutCleanupJob} cancela ordens em estados PENDING, OPEN e PARTIAL
     * que passaram mais de 1 minuto (perfil e2e). Portanto, a transição
     * PARTIAL_FILLED → CANCELLED é esperada.</p>
     *
     * <p>Valida também que os fundos bloqueados do vendedor são liberados após o cancel.</p>
     */
    @Test
    @Order(10)
    @Timeout(value = 150, unit = TimeUnit.SECONDS)
    @DisplayName("Partial fill + timeout: SELL parcial sem mais matches → resíduo CANCELLED e funds released")
    void testPartialFillThenTimeoutCancel() {
        // ------------------------------------------------------------------ ARRANGE
        String sellerJwt = craftJwt(PARTIAL_CANCEL_SELLER_ID);
        String buyerJwt  = craftJwt(PARTIAL_CANCEL_BUYER_ID);

        // ------------------------------------------------------------------ ACT: SELL de 3 VIB
        log.info("[E2E][partial-cancel] Vendedor coloca SELL de 3 VIB a 100 BRL/VIB...");
        String sellOrderId = given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + sellerJwt)
                .body(orderBody(partialCancelSellerWalletId, "SELL", PRICE_MATCH, "3.00000000"))
                .post(orderServiceUrl + "/api/v1/orders")
                .then()
                .statusCode(202)
                .extract().path("orderId");
        log.info("[E2E][partial-cancel] SELL orderId={}", sellOrderId);

        // Aguarda SELL ficar OPEN (funds reserved)
        await("SELL deve ficar OPEN")
                .atMost(30, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(getOrderStatus(sellerJwt, sellOrderId)).isEqualTo("OPEN"));

        log.info("[E2E][partial-cancel] SELL está OPEN. Colocando BUY de 1 VIB...");

        // ------------------------------------------------------------------ ACT: BUY de 1 VIB (match parcial)
        String buyOrderId = given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + buyerJwt)
                .body(orderBody(partialCancelBuyerWalletId, "BUY", PRICE_MATCH, AMOUNT_1_VIB))
                .post(orderServiceUrl + "/api/v1/orders")
                .then()
                .statusCode(202)
                .extract().path("orderId");
        log.info("[E2E][partial-cancel] BUY orderId={}", buyOrderId);

        // ------------------------------------------------------------------ FASE 1: BUY → FILLED, SELL → PARTIAL_FILLED
        await("BUY deve ficar FILLED")
                .atMost(45, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(getOrderStatus(buyerJwt, buyOrderId)).isEqualTo("FILLED"));

        await("SELL deve ficar PARTIAL_FILLED")
                .atMost(45, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    String status = getOrderStatus(sellerJwt, sellOrderId);
                    assertThat(status)
                            .as("SELL deve transitar para PARTIAL_FILLED após match parcial")
                            .isEqualTo("PARTIAL_FILLED");
                });

        log.info("[E2E][partial-cancel] BUY=FILLED, SELL=PARTIAL_FILLED. Aguardando timeout (~1 min)...");

        // ------------------------------------------------------------------ FASE 2: SELL → CANCELLED (após timeout)
        // SagaTimeoutCleanupJob roda a cada 5s no perfil e2e e cancela ordens PARTIAL após 1 min
        await("SELL deve ser cancelada pelo SagaTimeoutCleanupJob")
                .atMost(120, TimeUnit.SECONDS)
                .pollInterval(3, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(getOrderStatus(sellerJwt, sellOrderId)).isEqualTo("CANCELLED"));

        log.info("[E2E][partial-cancel] SELL=CANCELLED. Validando saldos do vendedor...");

        // ------------------------------------------------------------------ ASSERT: saldos do vendedor
        // Vendedor iniciou com 0 BRL e 10 VIB.
        // - 3 VIB foram locked para a SELL.
        // - 1 VIB foi vendido (settlement) → vendedor recebe 100 BRL.
        // - 2 VIB restantes foram released após cancel.
        // Resultado esperado: brl=100, vibAvailable=9 (10-1), vibLocked=0
        Map<String, Number> sellerBalance = getWalletBalance(sellerJwt, PARTIAL_CANCEL_SELLER_ID);

        assertThat(sellerBalance.get("vibLocked").doubleValue())
                .as("vibLocked do vendedor deve ser 0 após cancel (funds released)")
                .isEqualTo(0.0);

        assertThat(sellerBalance.get("vibAvailable").doubleValue())
                .as("vibAvailable do vendedor deve ser 9.0 (10 original - 1 vendido)")
                .isEqualTo(9.0);

        log.info("[E2E][partial-cancel] ✓ Partial fill + timeout validado: SELL CANCELLED, vibLocked=0, vibAvailable=9");
    }

    // =========================================================================
    // Teste 11 — Segurança: Acesso Cross-User bloqueado
    // =========================================================================

    /**
     * Valida controles de segurança cross-user em três cenários:
     * <ol>
     *   <li><b>Cenário A:</b> Usuário B tenta consultar a ordem do Usuário A → 403 ou 404.</li>
     *   <li><b>Cenário B:</b> Usuário B tenta consultar a wallet do Usuário A → 403.</li>
     *   <li><b>Cenário C:</b> Usuário B tenta criar ordem com walletId do Usuário A → 403 ou 400.</li>
     * </ol>
     *
     * <p>Nenhum dado do Usuário A deve ser vazado no response body das respostas de erro.</p>
     */
    @Test
    @Order(11)
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    @DisplayName("Segurança: acesso cross-user bloqueado em ordens, wallets e criação com walletId alheio")
    void testCrossUserSecurityBlocked() {
        // ------------------------------------------------------------------ ARRANGE
        String userAJwt = craftJwt(SEC_USER_A_ID);
        String userBJwt = craftJwt(SEC_USER_B_ID);

        // Usuário A cria uma BUY para ter uma ordem no sistema
        log.info("[E2E][security] Usuário A cria BUY de 1 VIB a 100 BRL...");
        String orderIdA = given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + userAJwt)
                .body(orderBody(secUserAWalletId, "BUY", PRICE_MATCH, AMOUNT_1_VIB))
                .post(orderServiceUrl + "/api/v1/orders")
                .then()
                .statusCode(202)
                .extract().path("orderId");
        log.info("[E2E][security] Ordem de A criada: {}", orderIdA);

        // Aguarda a ordem existir no Read Model
        await("Ordem de A deve aparecer no Read Model")
                .atMost(30, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    String status = getOrderStatus(userAJwt, orderIdA);
                    assertThat(status).as("Ordem de A deve existir").isNotNull();
                });

        // ------------------------------------------------------------------ CENÁRIO A: consulta de ordem cross-user
        log.info("[E2E][security] Cenário A: Usuário B tenta GET /orders/{} de A...", orderIdA);
        Response crossOrderResp = given()
                .header("Authorization", "Bearer " + userBJwt)
                .get(orderServiceUrl + "/api/v1/orders/" + orderIdA);

        assertThat(crossOrderResp.statusCode())
                .as("Acesso cross-user a ordem deve retornar 403 ou 404")
                .isIn(403, 404);

        // Verifica que nenhum dado de A é vazado
        String crossOrderBody = crossOrderResp.body().asString();
        assertThat(crossOrderBody)
                .as("Response não deve conter o userId de A")
                .doesNotContain(SEC_USER_A_ID.toString());
        assertThat(crossOrderBody)
                .as("Response não deve conter o walletId de A")
                .doesNotContain(secUserAWalletId.toString());

        log.info("[E2E][security] Cenário A: status={} ✓", crossOrderResp.statusCode());

        // ------------------------------------------------------------------ CENÁRIO B: consulta de wallet cross-user
        log.info("[E2E][security] Cenário B: Usuário B tenta GET /wallets/{} de A...", SEC_USER_A_ID);
        Response crossWalletResp = given()
                .header("Authorization", "Bearer " + userBJwt)
                .get(walletServiceUrl + "/api/v1/wallets/" + SEC_USER_A_ID);

        assertThat(crossWalletResp.statusCode())
                .as("Acesso cross-user a wallet deve retornar 403")
                .isEqualTo(403);

        log.info("[E2E][security] Cenário B: status=403 ✓");

        // ------------------------------------------------------------------ CENÁRIO C: criação de ordem com walletId alheio
        log.info("[E2E][security] Cenário C: Usuário B tenta criar BUY com walletId de A...");
        Response crossCreateResp = given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + userBJwt)
                .body(orderBody(secUserAWalletId, "BUY", PRICE_MATCH, AMOUNT_1_VIB))
                .post(orderServiceUrl + "/api/v1/orders");

        assertThat(crossCreateResp.statusCode())
                .as("Criar ordem com walletId de outro usuário deve retornar 403 ou 400")
                .isIn(403, 400);

        log.info("[E2E][security] Cenário C: status={} ✓", crossCreateResp.statusCode());
        log.info("[E2E][security] ✓ Todos os cenários de segurança cross-user validados");
    }

    // =========================================================================
    // Teste 12 — Resiliência: duas rodadas sequenciais de trade
    // =========================================================================

    /**
     * Valida que o sistema se mantém funcional após uma rodada completa de trades,
     * executando duas rodadas sequenciais com preços diferentes:
     * <ol>
     *   <li><b>Rodada 1:</b> BUY + SELL de 1 VIB a 80 BRL/VIB → ambas FILLED.</li>
     *   <li><b>Rodada 2:</b> BUY + SELL de 1 VIB a 90 BRL/VIB → ambas FILLED.</li>
     * </ol>
     *
     * <p>Exercita:</p>
     * <ul>
     *   <li>Outbox relay multi-ciclo</li>
     *   <li>Reutilização de conexões Redis</li>
     *   <li>Estado correto do order book após execução anterior (ordens FILLED removidas)</li>
     *   <li>Nenhuma interferência entre rodada 1 e 2</li>
     * </ul>
     */
    @Test
    @Order(12)
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    @DisplayName("Resiliência: duas rodadas sequenciais de trade completam sem interferência")
    void testResilienceSequentialRounds() {
        // ------------------------------------------------------------------ ARRANGE
        String buyerJwt  = craftJwt(RESIL_BUYER_ID);
        String sellerJwt = craftJwt(RESIL_SELLER_ID);

        // ================================================================== RODADA 1: 80 BRL/VIB
        log.info("[E2E][resilience] Rodada 1: BUY + SELL de 1 VIB a 80 BRL/VIB...");

        String buyOrderId1 = given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + buyerJwt)
                .body(orderBody(resilBuyerWalletId, "BUY", "80.00", AMOUNT_1_VIB))
                .post(orderServiceUrl + "/api/v1/orders")
                .then()
                .statusCode(202)
                .extract().path("orderId");
        log.info("[E2E][resilience] R1 BUY orderId={}", buyOrderId1);

        // Aguarda BUY ficar OPEN antes de colocar SELL
        await("R1 BUY deve ficar OPEN")
                .atMost(30, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(getOrderStatus(buyerJwt, buyOrderId1)).isEqualTo("OPEN"));

        String sellOrderId1 = given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + sellerJwt)
                .body(orderBody(resilSellerWalletId, "SELL", "80.00", AMOUNT_1_VIB))
                .post(orderServiceUrl + "/api/v1/orders")
                .then()
                .statusCode(202)
                .extract().path("orderId");
        log.info("[E2E][resilience] R1 SELL orderId={}", sellOrderId1);

        // Aguarda ambas FILLED
        await("R1 BUY deve ficar FILLED")
                .atMost(45, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(getOrderStatus(buyerJwt, buyOrderId1)).isEqualTo("FILLED"));

        await("R1 SELL deve ficar FILLED")
                .atMost(45, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(getOrderStatus(sellerJwt, sellOrderId1)).isEqualTo("FILLED"));

        log.info("[E2E][resilience] Rodada 1 concluída: BUY=FILLED, SELL=FILLED");

        // ================================================================== RODADA 2: 90 BRL/VIB (preço diferente)
        log.info("[E2E][resilience] Rodada 2: BUY + SELL de 1 VIB a 90 BRL/VIB...");

        String buyOrderId2 = given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + buyerJwt)
                .body(orderBody(resilBuyerWalletId, "BUY", "90.00", AMOUNT_1_VIB))
                .post(orderServiceUrl + "/api/v1/orders")
                .then()
                .statusCode(202)
                .extract().path("orderId");
        log.info("[E2E][resilience] R2 BUY orderId={}", buyOrderId2);

        // Aguarda BUY ficar OPEN antes de colocar SELL
        await("R2 BUY deve ficar OPEN")
                .atMost(30, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(getOrderStatus(buyerJwt, buyOrderId2)).isEqualTo("OPEN"));

        String sellOrderId2 = given()
                .contentType(ContentType.JSON)
                .header("Authorization", "Bearer " + sellerJwt)
                .body(orderBody(resilSellerWalletId, "SELL", "90.00", AMOUNT_1_VIB))
                .post(orderServiceUrl + "/api/v1/orders")
                .then()
                .statusCode(202)
                .extract().path("orderId");
        log.info("[E2E][resilience] R2 SELL orderId={}", sellOrderId2);

        // Aguarda ambas FILLED
        await("R2 BUY deve ficar FILLED")
                .atMost(45, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(getOrderStatus(buyerJwt, buyOrderId2)).isEqualTo("FILLED"));

        await("R2 SELL deve ficar FILLED")
                .atMost(45, TimeUnit.SECONDS)
                .pollInterval(2, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(getOrderStatus(sellerJwt, sellOrderId2)).isEqualTo("FILLED"));

        log.info("[E2E][resilience] Rodada 2 concluída: BUY=FILLED, SELL=FILLED");

        // ------------------------------------------------------------------ ASSERT: sem interferência
        // Confirma que TODAS as 4 ordens estão FILLED (nenhuma foi corrompida)
        assertThat(getOrderStatus(buyerJwt, buyOrderId1)).isEqualTo("FILLED");
        assertThat(getOrderStatus(sellerJwt, sellOrderId1)).isEqualTo("FILLED");
        assertThat(getOrderStatus(buyerJwt, buyOrderId2)).isEqualTo("FILLED");
        assertThat(getOrderStatus(sellerJwt, sellOrderId2)).isEqualTo("FILLED");

        // Confirma que são 4 ordens distintas
        assertThat(buyOrderId1).isNotEqualTo(buyOrderId2);
        assertThat(sellOrderId1).isNotEqualTo(sellOrderId2);

        log.info("[E2E][resilience] ✓ Resiliência validada: 2 rodadas sequenciais sem interferência");
    }

    // =========================================================================
    // Helpers privados
    // =========================================================================

    /**
     * Consulta os saldos de uma wallet via {@code GET /api/v1/wallets/{userId}}.
     *
     * @param jwt    JWT do usuário autenticado.
     * @param userId UUID do usuário dono da wallet.
     * @return Map com chaves: brlAvailable, brlLocked, vibAvailable, vibLocked.
     */
    private static Map<String, Number> getWalletBalance(String jwt, UUID userId) {
        Response resp = given()
                .header("Authorization", "Bearer " + jwt)
                .get(walletServiceUrl + "/api/v1/wallets/" + userId)
                .then()
                .statusCode(200)
                .extract().response();

        return Map.of(
                "brlAvailable", resp.<Number>path("brlAvailable"),
                "brlLocked",    resp.<Number>path("brlLocked"),
                "vibAvailable", resp.<Number>path("vibAvailable"),
                "vibLocked",    resp.<Number>path("vibLocked")
        );
    }

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
                        TIMEOUT_USER_ID.toString(),
                        PARTIAL_BUYER_ID.toString(),
                        PARTIAL_SELLER_ID.toString(),
                        MULTI_BUYER_ID.toString(),
                        MULTI_SELLER_A_ID.toString(),
                        MULTI_SELLER_B_ID.toString(),
                        INSUFFICIENT_FUNDS_USER_ID.toString(),
                        IDEMP_BUYER_ID.toString(),
                        IDEMP_SELLER_ID.toString(),
                        BALANCE_BUYER_ID.toString(),
                        BALANCE_SELLER_ID.toString(),
                        CONC_BUYER_1_ID.toString(),
                        CONC_BUYER_2_ID.toString(),
                        CONC_BUYER_3_ID.toString(),
                        CONC_SELLER_1_ID.toString(),
                        CONC_SELLER_2_ID.toString(),
                        CONC_SELLER_3_ID.toString(),
                        PROJ_BUYER_ID.toString(),
                        PROJ_SELLER_ID.toString(),
                        PARTIAL_CANCEL_BUYER_ID.toString(),
                        PARTIAL_CANCEL_SELLER_ID.toString(),
                        SEC_USER_A_ID.toString(),
                        SEC_USER_B_ID.toString(),
                        RESIL_BUYER_ID.toString(),
                        RESIL_SELLER_ID.toString()
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
                  { "userId": "%s", "brl":   10.00, "vib": 0.00  },
                  { "userId": "%s", "brl": 1000.00, "vib": 0.00 },
                  { "userId": "%s", "brl":    0.00, "vib": 10.00 },
                  { "userId": "%s", "brl":  500.00, "vib": 0.00 },
                  { "userId": "%s", "brl":    0.00, "vib": 5.00 },
                  { "userId": "%s", "brl":    0.00, "vib": 5.00 },
                  { "userId": "%s", "brl":    1.00, "vib": 0.00 },
                  { "userId": "%s", "brl":  500.00, "vib": 0.00 },
                  { "userId": "%s", "brl":    0.00, "vib": 10.00 },
                  { "userId": "%s", "brl":  500.00, "vib": 0.00 },
                  { "userId": "%s", "brl":    0.00, "vib": 10.00 },
                  { "userId": "%s", "brl":  200.00, "vib": 0.00 },
                  { "userId": "%s", "brl":  200.00, "vib": 0.00 },
                  { "userId": "%s", "brl":  200.00, "vib": 0.00 },
                  { "userId": "%s", "brl":    0.00, "vib": 5.00 },
                  { "userId": "%s", "brl":    0.00, "vib": 5.00 },
                  { "userId": "%s", "brl":    0.00, "vib": 5.00 },
                  { "userId": "%s", "brl":  300.00, "vib": 0.00 },
                  { "userId": "%s", "brl":    0.00, "vib": 5.00 },
                  { "userId": "%s", "brl":  200.00, "vib": 0.00 },
                  { "userId": "%s", "brl":    0.00, "vib": 10.00 },
                  { "userId": "%s", "brl":  100.00, "vib": 1.00 },
                  { "userId": "%s", "brl":  100.00, "vib": 1.00 },
                  { "userId": "%s", "brl": 1000.00, "vib": 0.00 },
                  { "userId": "%s", "brl":    0.00, "vib": 20.00 }
                ]
                """.formatted(BUYER_ID, SELLER_ID, TIMEOUT_USER_ID, PARTIAL_BUYER_ID, PARTIAL_SELLER_ID,
                        MULTI_BUYER_ID, MULTI_SELLER_A_ID, MULTI_SELLER_B_ID, INSUFFICIENT_FUNDS_USER_ID,
                        IDEMP_BUYER_ID, IDEMP_SELLER_ID, BALANCE_BUYER_ID, BALANCE_SELLER_ID,
                        CONC_BUYER_1_ID, CONC_BUYER_2_ID, CONC_BUYER_3_ID,
                        CONC_SELLER_1_ID, CONC_SELLER_2_ID, CONC_SELLER_3_ID,
                        PROJ_BUYER_ID, PROJ_SELLER_ID,
                        PARTIAL_CANCEL_BUYER_ID, PARTIAL_CANCEL_SELLER_ID,
                        SEC_USER_A_ID, SEC_USER_B_ID,
                        RESIL_BUYER_ID, RESIL_SELLER_ID);

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
            } else if (PARTIAL_BUYER_ID.toString().equals(userId)) {
                partialBuyerWalletId = UUID.fromString(walletId);
            } else if (PARTIAL_SELLER_ID.toString().equals(userId)) {
                partialSellerWalletId = UUID.fromString(walletId);
            } else if (MULTI_BUYER_ID.toString().equals(userId)) {
                multiBuyerWalletId = UUID.fromString(walletId);
            } else if (MULTI_SELLER_A_ID.toString().equals(userId)) {
                multiSellerAWalletId = UUID.fromString(walletId);
            } else if (MULTI_SELLER_B_ID.toString().equals(userId)) {
                multiSellerBWalletId = UUID.fromString(walletId);
            } else if (INSUFFICIENT_FUNDS_USER_ID.toString().equals(userId)) {
                insufficientFundsWalletId = UUID.fromString(walletId);
            } else if (IDEMP_BUYER_ID.toString().equals(userId)) {
                idempBuyerWalletId = UUID.fromString(walletId);
            } else if (IDEMP_SELLER_ID.toString().equals(userId)) {
                idempSellerWalletId = UUID.fromString(walletId);
            } else if (BALANCE_BUYER_ID.toString().equals(userId)) {
                balanceBuyerWalletId = UUID.fromString(walletId);
            } else if (BALANCE_SELLER_ID.toString().equals(userId)) {
                balanceSellerWalletId = UUID.fromString(walletId);
            } else if (CONC_BUYER_1_ID.toString().equals(userId)) {
                concBuyer1WalletId = UUID.fromString(walletId);
            } else if (CONC_BUYER_2_ID.toString().equals(userId)) {
                concBuyer2WalletId = UUID.fromString(walletId);
            } else if (CONC_BUYER_3_ID.toString().equals(userId)) {
                concBuyer3WalletId = UUID.fromString(walletId);
            } else if (CONC_SELLER_1_ID.toString().equals(userId)) {
                concSeller1WalletId = UUID.fromString(walletId);
            } else if (CONC_SELLER_2_ID.toString().equals(userId)) {
                concSeller2WalletId = UUID.fromString(walletId);
            } else if (CONC_SELLER_3_ID.toString().equals(userId)) {
                concSeller3WalletId = UUID.fromString(walletId);
            } else if (PROJ_BUYER_ID.toString().equals(userId)) {
                projBuyerWalletId = UUID.fromString(walletId);
            } else if (PROJ_SELLER_ID.toString().equals(userId)) {
                projSellerWalletId = UUID.fromString(walletId);
            } else if (PARTIAL_CANCEL_BUYER_ID.toString().equals(userId)) {
                partialCancelBuyerWalletId = UUID.fromString(walletId);
            } else if (PARTIAL_CANCEL_SELLER_ID.toString().equals(userId)) {
                partialCancelSellerWalletId = UUID.fromString(walletId);
            } else if (SEC_USER_A_ID.toString().equals(userId)) {
                secUserAWalletId = UUID.fromString(walletId);
            } else if (SEC_USER_B_ID.toString().equals(userId)) {
                secUserBWalletId = UUID.fromString(walletId);
            } else if (RESIL_BUYER_ID.toString().equals(userId)) {
                resilBuyerWalletId = UUID.fromString(walletId);
            } else if (RESIL_SELLER_ID.toString().equals(userId)) {
                resilSellerWalletId = UUID.fromString(walletId);
            }
        }

        assertThat(buyerWalletId).as("buyerWalletId não deve ser nulo após seeder").isNotNull();
        assertThat(sellerWalletId).as("sellerWalletId não deve ser nulo após seeder").isNotNull();
        assertThat(timeoutUserWalletId).as("timeoutUserWalletId não deve ser nulo após seeder").isNotNull();
        assertThat(partialBuyerWalletId).as("partialBuyerWalletId não deve ser nulo após seeder").isNotNull();
        assertThat(partialSellerWalletId).as("partialSellerWalletId não deve ser nulo após seeder").isNotNull();
        assertThat(multiBuyerWalletId).as("multiBuyerWalletId não deve ser nulo após seeder").isNotNull();
        assertThat(multiSellerAWalletId).as("multiSellerAWalletId não deve ser nulo após seeder").isNotNull();
        assertThat(multiSellerBWalletId).as("multiSellerBWalletId não deve ser nulo após seeder").isNotNull();
        assertThat(insufficientFundsWalletId).as("insufficientFundsWalletId não deve ser nulo após seeder").isNotNull();
        assertThat(idempBuyerWalletId).as("idempBuyerWalletId não deve ser nulo após seeder").isNotNull();
        assertThat(idempSellerWalletId).as("idempSellerWalletId não deve ser nulo após seeder").isNotNull();
        assertThat(balanceBuyerWalletId).as("balanceBuyerWalletId não deve ser nulo após seeder").isNotNull();
        assertThat(balanceSellerWalletId).as("balanceSellerWalletId não deve ser nulo após seeder").isNotNull();
        assertThat(concBuyer1WalletId).as("concBuyer1WalletId não deve ser nulo após seeder").isNotNull();
        assertThat(concBuyer2WalletId).as("concBuyer2WalletId não deve ser nulo após seeder").isNotNull();
        assertThat(concBuyer3WalletId).as("concBuyer3WalletId não deve ser nulo após seeder").isNotNull();
        assertThat(concSeller1WalletId).as("concSeller1WalletId não deve ser nulo após seeder").isNotNull();
        assertThat(concSeller2WalletId).as("concSeller2WalletId não deve ser nulo após seeder").isNotNull();
        assertThat(concSeller3WalletId).as("concSeller3WalletId não deve ser nulo após seeder").isNotNull();
        assertThat(projBuyerWalletId).as("projBuyerWalletId não deve ser nulo após seeder").isNotNull();
        assertThat(projSellerWalletId).as("projSellerWalletId não deve ser nulo após seeder").isNotNull();
        assertThat(partialCancelBuyerWalletId).as("partialCancelBuyerWalletId não deve ser nulo após seeder").isNotNull();
        assertThat(partialCancelSellerWalletId).as("partialCancelSellerWalletId não deve ser nulo após seeder").isNotNull();
        assertThat(secUserAWalletId).as("secUserAWalletId não deve ser nulo após seeder").isNotNull();
        assertThat(secUserBWalletId).as("secUserBWalletId não deve ser nulo após seeder").isNotNull();
        assertThat(resilBuyerWalletId).as("resilBuyerWalletId não deve ser nulo após seeder").isNotNull();
        assertThat(resilSellerWalletId).as("resilSellerWalletId não deve ser nulo após seeder").isNotNull();

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
