package com.vibranium.performance;

import com.vibranium.performance.helpers.BalanceTracker;
import com.vibranium.performance.helpers.KeycloakAdminHelper;
import com.vibranium.performance.helpers.TestUser;
import com.vibranium.performance.helpers.WalletApiHelper;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Simulação de validação de casamento de ordens com carteiras reais.
 *
 * <p>Corrige o problema das simulações anteriores que utilizavam walletIds aleatórios.
 * Esta simulação:</p>
 * <ol>
 *   <li>Cria 4 usuários reais no Keycloak (que disparam criação automática de carteiras)</li>
 *   <li>Deposita saldo inicial em cada carteira via API</li>
 *   <li>Executa rodadas de 10 BUY + 15 SELL com carteiras válidas</li>
 *   <li>Valida que o saldo final corresponde ao esperado</li>
 * </ol>
 *
 * <h3>Regra de casamento por rodada:</h3>
 * <p>10 ordens BUY × 1.50 VIB = 15.00 VIB total de compra.<br>
 * 15 ordens SELL × 1.00 VIB = 15.00 VIB total de venda.<br>
 * Volume igual garante que todas as ordens de compra sejam completamente preenchidas.</p>
 *
 * <h3>Execução:</h3>
 * <pre>
 * mvn gatling:test -pl tests/performance -Pvalidation
 * # Ou via docker-compose:
 * GATLING_SIMULATION=com.vibranium.performance.OrderMatchingValidationSimulation \
 *   docker compose -f tests/performance/docker-compose.perf.yml run --rm gatling
 * </pre>
 */
public class OrderMatchingValidationSimulation extends Simulation {

    // ─── Configuração (sobrescrevível via variáveis de ambiente) ───

    /** Número de usuários de teste a criar */
    private static final int NUM_USERS = 4;

    /** Número de rodadas de compra/venda */
    private static final int NUM_ROUNDS = intEnv("NUM_ROUNDS", 5);

    /** Ordens de compra por rodada */
    private static final int BUYS_PER_ROUND = 10;

    /** Ordens de venda por rodada (volume total = BUYS_PER_ROUND × BUY_AMOUNT) */
    private static final int SELLS_PER_ROUND = 15;

    /** Preço fixo para todas as ordens (simplifica matching e validação) */
    private static final BigDecimal PRICE = new BigDecimal("100.00000000");

    /** Quantidade VIB por ordem de compra. Total buy/round = 10 × 1.50 = 15 VIB */
    private static final BigDecimal BUY_AMOUNT = new BigDecimal("1.50000000");

    /** Quantidade VIB por ordem de venda. Total sell/round = 15 × 1.00 = 15 VIB */
    private static final BigDecimal SELL_AMOUNT = new BigDecimal("1.00000000");

    /** Saldo inicial depositado em cada carteira (BRL e VIB) */
    private static final BigDecimal INITIAL_DEPOSIT = new BigDecimal("10000000.00000000");

    /** Pausa entre buys e sells para processamento da saga (segundos) */
    private static final int SETTLE_PAUSE_SECONDS = intEnv("SETTLE_PAUSE_SECONDS", 10);

    /** Tempo de espera final para liquidação de todas as ordens (segundos) */
    private static final int FINAL_SETTLE_WAIT_SECONDS = intEnv("FINAL_SETTLE_WAIT_SECONDS", 60);

    // ─── URLs dos serviços ───

    private static final String KEYCLOAK_BASE_URL = env("KEYCLOAK_BASE_URL", "http://keycloak:8080");
    private static final String KEYCLOAK_REALM = env("KEYCLOAK_REALM", "orderbook-realm");
    private static final String KEYCLOAK_CLIENT_ID = env("KEYCLOAK_CLIENT_ID", "order-client");
    private static final String KEYCLOAK_ADMIN_USER = env("KEYCLOAK_ADMIN_USER", "admin");
    private static final String KEYCLOAK_ADMIN_PASSWORD = env("KEYCLOAK_ADMIN_PASSWORD", "perftest");
    private static final String WALLET_SERVICE_URL = env("WALLET_SERVICE_URL", "http://wallet-service:8081");

    // ─── Estado compartilhado ───

    private static final List<TestUser> TEST_USERS = new ArrayList<>();
    private static final BalanceTracker TRACKER = new BalanceTracker();
    private static final Map<String, BigDecimal[]> INITIAL_BALANCES = new ConcurrentHashMap<>();
    private static final Random RANDOM = new Random();

    // ═══════════════════════════════════════════════════════════════
    // FASE 1: SETUP — Criação de usuários, carteiras e depósitos
    // ═══════════════════════════════════════════════════════════════

    @Override
    public void before() {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║  ORDER MATCHING VALIDATION — Setup Phase                    ║");
        System.out.println("╠══════════════════════════════════════════════════════════════╣");
        System.out.printf("║  Rounds: %d | Buys/round: %d | Sells/round: %d              ║%n",
                NUM_ROUNDS, BUYS_PER_ROUND, SELLS_PER_ROUND);
        System.out.printf("║  Price: %s | Buy amount: %s | Sell amount: %s  ║%n",
                PRICE.toPlainString(), BUY_AMOUNT.toPlainString(), SELL_AMOUNT.toPlainString());
        System.out.println("╚══════════════════════════════════════════════════════════════╝");

        // 1. Criar usuários no Keycloak (idempotente)
        System.out.println("[1/4] Creating " + NUM_USERS + " test users in Keycloak...");
        KeycloakAdminHelper keycloak = new KeycloakAdminHelper(
                KEYCLOAK_BASE_URL, KEYCLOAK_REALM, KEYCLOAK_CLIENT_ID,
                KEYCLOAK_ADMIN_USER, KEYCLOAK_ADMIN_PASSWORD
        );
        List<TestUser> users = keycloak.createTestUsers(NUM_USERS);
        TEST_USERS.addAll(users);

        // 2. Aguardar criação automática de carteiras (Keycloak → RabbitMQ → wallet-service)
        System.out.println("[2/4] Waiting for wallets to be created via Keycloak events...");
        WalletApiHelper walletApi = new WalletApiHelper(WALLET_SERVICE_URL);
        for (TestUser user : TEST_USERS) {
            java.util.UUID walletId = walletApi.waitForWallet(user, 30, 2000);
            user.setWalletId(walletId);
        }

        // 3. Depositar saldo inicial em cada carteira
        System.out.println("[3/4] Depositing initial balances (BRL=" + INITIAL_DEPOSIT
                + ", VIB=" + INITIAL_DEPOSIT + " per user)...");
        for (TestUser user : TEST_USERS) {
            walletApi.adjustBalance(user, INITIAL_DEPOSIT, INITIAL_DEPOSIT);
        }

        // Pausa curta para consolidação do saldo
        sleep(3000);

        // 4. Registrar saldos iniciais para validação posterior
        System.out.println("[4/4] Recording initial balances...");
        for (TestUser user : TEST_USERS) {
            BigDecimal[] balance = walletApi.getBalance(user);
            INITIAL_BALANCES.put(user.getKeycloakId(), balance);
            System.out.printf("  %s: BRL=%s VIB=%s%n",
                    user.getUsername(), balance[0].toPlainString(), balance[2].toPlainString());
        }

        System.out.println("Setup complete. Starting " + NUM_ROUNDS + " rounds of order matching...\n");
    }

    // ═══════════════════════════════════════════════════════════════
    // FASE 2: EXECUÇÃO — Rodadas de compra e venda
    // ═══════════════════════════════════════════════════════════════

    private final ScenarioBuilder validationScenario = scenario("Order Matching Validation")
            .exec(
                    repeat(NUM_ROUNDS, "round").on(
                            // Log do início da rodada
                            exec(session -> {
                                int round = session.getInt("round") + 1;
                                System.out.println("=== Round " + round + "/" + NUM_ROUNDS + " ===");
                                return session;
                            })
                            // 10 ordens de COMPRA
                            .exec(
                                    repeat(BUYS_PER_ROUND, "buyIdx").on(
                                            exec(session -> {
                                                TestUser user = TEST_USERS.get(RANDOM.nextInt(TEST_USERS.size()));
                                                TRACKER.recordBuy(user.getKeycloakId(), BUY_AMOUNT);
                                                return session
                                                        .set("walletId", user.getWalletId().toString())
                                                        .set("orderType", "BUY")
                                                        .set("price", PRICE.toPlainString())
                                                        .set("amount", BUY_AMOUNT.toPlainString())
                                                        .set("authToken", user.getBearerToken());
                                            })
                                            .exec(
                                                    http("Place BUY Order")
                                                            .post("/api/v1/orders")
                                                            .header("Authorization", "#{authToken}")
                                                            .body(StringBody("""
                                                                    {
                                                                      "walletId": "#{walletId}",
                                                                      "orderType": "#{orderType}",
                                                                      "price": #{price},
                                                                      "amount": #{amount}
                                                                    }
                                                                    """))
                                                            .check(status().is(202))
                                                            .check(jsonPath("$.orderId").exists())
                                                            .check(jsonPath("$.status").is("PENDING"))
                                            )
                                    )
                            )
                            // Pausa para que as ordens de compra sejam processadas e adicionadas ao livro
                            .pause(Duration.ofSeconds(SETTLE_PAUSE_SECONDS))
                            // 15 ordens de VENDA (volume total = buy total, garantindo casamento completo)
                            .exec(
                                    repeat(SELLS_PER_ROUND, "sellIdx").on(
                                            exec(session -> {
                                                TestUser user = TEST_USERS.get(RANDOM.nextInt(TEST_USERS.size()));
                                                TRACKER.recordSell(user.getKeycloakId(), SELL_AMOUNT);
                                                return session
                                                        .set("walletId", user.getWalletId().toString())
                                                        .set("orderType", "SELL")
                                                        .set("price", PRICE.toPlainString())
                                                        .set("amount", SELL_AMOUNT.toPlainString())
                                                        .set("authToken", user.getBearerToken());
                                            })
                                            .exec(
                                                    http("Place SELL Order")
                                                            .post("/api/v1/orders")
                                                            .header("Authorization", "#{authToken}")
                                                            .body(StringBody("""
                                                                    {
                                                                      "walletId": "#{walletId}",
                                                                      "orderType": "#{orderType}",
                                                                      "price": #{price},
                                                                      "amount": #{amount}
                                                                    }
                                                                    """))
                                                            .check(status().is(202))
                                                            .check(jsonPath("$.orderId").exists())
                                                            .check(jsonPath("$.status").is("PENDING"))
                                            )
                                    )
                            )
                            // Pausa para casamento e liquidação da rodada
                            .pause(Duration.ofSeconds(SETTLE_PAUSE_SECONDS))
                    )
            );

    // ═══════════════════════════════════════════════════════════════
    // FASE 3: VALIDAÇÃO — Comparação de saldos esperados vs reais
    // ═══════════════════════════════════════════════════════════════

    @Override
    public void after() {
        System.out.println("\n╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║  ORDER MATCHING VALIDATION — Verification Phase             ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");

        // Aguardar liquidação final de todas as ordens
        System.out.println("Waiting " + FINAL_SETTLE_WAIT_SECONDS + "s for final settlement...");
        sleep(FINAL_SETTLE_WAIT_SECONDS * 1000L);

        // Relatório intermediário: ordens registradas por usuário
        System.out.println("\n--- Trade Summary ---");
        Map<String, BigDecimal[]> trades = TRACKER.snapshot();
        for (TestUser user : TEST_USERS) {
            BigDecimal[] t = trades.getOrDefault(user.getKeycloakId(),
                    new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            System.out.printf("  %s: bought %.8f VIB, sold %.8f VIB%n",
                    user.getUsername(), t[0], t[1]);
        }

        // Consultar saldos reais e comparar com esperados
        WalletApiHelper walletApi = new WalletApiHelper(WALLET_SERVICE_URL);
        boolean allValid = true;

        System.out.println("\n--- Balance Validation ---");
        for (TestUser user : TEST_USERS) {
            BigDecimal[] initial = INITIAL_BALANCES.get(user.getKeycloakId());
            BigDecimal initialBrl = initial[0]; // brlAvailable
            BigDecimal initialVib = initial[2]; // vibAvailable

            BigDecimal expectedBrl = TRACKER.expectedBrl(user.getKeycloakId(), initialBrl, PRICE);
            BigDecimal expectedVib = TRACKER.expectedVib(user.getKeycloakId(), initialVib);

            BigDecimal[] actual = walletApi.getBalance(user);
            BigDecimal actualBrl = actual[0];
            BigDecimal actualBrlLocked = actual[1];
            BigDecimal actualVib = actual[2];
            BigDecimal actualVibLocked = actual[3];

            boolean brlMatch = actualBrl.setScale(8, RoundingMode.HALF_UP)
                    .compareTo(expectedBrl.setScale(8, RoundingMode.HALF_UP)) == 0;
            boolean vibMatch = actualVib.setScale(8, RoundingMode.HALF_UP)
                    .compareTo(expectedVib.setScale(8, RoundingMode.HALF_UP)) == 0;
            boolean noLocked = actualBrlLocked.compareTo(BigDecimal.ZERO) == 0
                    && actualVibLocked.compareTo(BigDecimal.ZERO) == 0;

            boolean userValid = brlMatch && vibMatch && noLocked;
            if (!userValid) allValid = false;

            String status = userValid ? "PASS" : "FAIL";
            System.out.println("-----------------------------------------------------");
            System.out.printf("User: %s (%s) [%s]%n", user.getUsername(), user.getKeycloakId(), status);
            System.out.printf("  BRL: expected=%-20s actual=%-20s locked=%-10s %s%n",
                    expectedBrl.toPlainString(), actualBrl.toPlainString(),
                    actualBrlLocked.toPlainString(), brlMatch ? "OK" : "MISMATCH");
            System.out.printf("  VIB: expected=%-20s actual=%-20s locked=%-10s %s%n",
                    expectedVib.toPlainString(), actualVib.toPlainString(),
                    actualVibLocked.toPlainString(), vibMatch ? "OK" : "MISMATCH");
            if (!noLocked) {
                System.out.println("  WARNING: Locked funds detected — orders may not have settled completely.");
            }
        }

        System.out.println("=====================================================");
        if (allValid) {
            System.out.println("RESULT: ALL VALIDATIONS PASSED");
            System.out.println("Wallet balances match expected values after order matching.");
        } else {
            System.out.println("RESULT: VALIDATION FAILED");
            System.out.println("One or more wallets have incorrect balances.");
            System.out.println("Check if all orders were processed and settled correctly.");
            throw new AssertionError("Balance validation failed. See detailed report above.");
        }
        System.out.println("=====================================================");
    }

    // ═══════════════════════════════════════════════════════════════
    // SETUP DO GATLING — Injeção e protocolo
    // ═══════════════════════════════════════════════════════════════

    {
        setUp(
                // Um único virtual user executa todas as rodadas sequencialmente
                // para garantir a ordem: buys → pausa → sells → pausa → próxima rodada
                validationScenario.injectOpen(atOnceUsers(1))
        )
        .protocols(BaseSimulationConfig.httpProtocol())
        .assertions(
                // Todas as ordens devem ser aceitas (HTTP 202)
                global().failedRequests().percent().lt(1.0)
        );
    }

    // ─── Utilitários ───

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }

    static int intEnv(String key, int defaultValue) {
        String value = System.getenv(key);
        return (value != null && !value.isBlank()) ? Integer.parseInt(value) : defaultValue;
    }
}
