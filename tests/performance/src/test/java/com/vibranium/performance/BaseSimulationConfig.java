package com.vibranium.performance;

import com.vibranium.performance.helpers.KeycloakTokenFeeder;
import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Configuração base compartilhada entre todas as simulações Gatling.
 *
 * <p>Define o protocolo HTTP, a geração de payloads BUY/SELL em proporção ~50/50,
 * e a obtenção de JWT via Keycloak.</p>
 */
public final class BaseSimulationConfig {

    private static final Random RANDOM = new Random();

    // Singleton do token feeder — compartilhado entre virtual users
    private static final KeycloakTokenFeeder TOKEN_FEEDER = KeycloakTokenFeeder.fromEnv();

    // URLs das instâncias de order-service para distribuição round-robin
    private static final String[] ORDER_SERVICE_URLS = parseOrderServiceUrls();
    private static final AtomicInteger ORDER_URL_COUNTER = new AtomicInteger(0);

    // URLs das instâncias de wallet-service para distribuição round-robin
    private static final String[] WALLET_SERVICE_URLS = parseWalletServiceUrls();
    private static final AtomicInteger WALLET_URL_COUNTER = new AtomicInteger(0);

    // Pre-warm: obter o token de forma eager para evitar bloqueio do actor-thread do Gatling
    // durante a injeção de virtual users
    static {
        TOKEN_FEEDER.getBearerToken();
    }

    private BaseSimulationConfig() {
        // utility class
    }

    /**
     * URL base do serviço alvo. Lê de {@code TARGET_BASE_URL}.
     *
     * <p>Default: order-service direto (sem Kong Gateway) para testes de performance.
     * Em ambientes com gateway, sobrescrever via variável de ambiente.</p>
     */
    public static String baseUrl() {
        return env("TARGET_BASE_URL", "http://localhost:8080");
    }

    /**
     * Protocolo HTTP com headers comuns, JWT auth e configurações de performance.
     * Não define baseUrl — cada request usa URL completa via round-robin.
     */
    public static HttpProtocolBuilder httpProtocol() {
        return http
                .baseUrl(baseUrl())
                .acceptHeader("application/json")
                .contentTypeHeader("application/json")
                .userAgentHeader("Gatling/VibraniumPerfTest")
                // Não seguir redirects — 202 é a resposta esperada
                .disableFollowRedirect();
    }

    /**
     * Seleciona a próxima URL de order-service via round-robin.
     * Garante distribuição uniforme entre todas as instâncias.
     */
    static String nextOrderServiceUrl() {
        int idx = Math.abs(ORDER_URL_COUNTER.getAndIncrement() % ORDER_SERVICE_URLS.length);
        return ORDER_SERVICE_URLS[idx];
    }

    /**
     * Seleciona a próxima URL de wallet-service via round-robin.
     * Garante distribuição uniforme entre todas as instâncias.
     */
    public static String nextWalletServiceUrl() {
        int idx = Math.abs(WALLET_URL_COUNTER.getAndIncrement() % WALLET_SERVICE_URLS.length);
        return WALLET_SERVICE_URLS[idx];
    }

    /**
     * Retorna todas as URLs de wallet-service configuradas.
     */
    public static String[] walletServiceUrls() {
        return WALLET_SERVICE_URLS.clone();
    }

    private static String[] parseOrderServiceUrls() {
        String urls = env("ORDER_SERVICE_URLS", "");
        if (urls.isBlank()) {
            return new String[]{ baseUrl() };
        }
        return urls.split(",");
    }

    private static String[] parseWalletServiceUrls() {
        String urls = env("WALLET_SERVICE_URLS", "");
        if (urls.isBlank()) {
            return new String[]{ env("WALLET_SERVICE_URL", "http://localhost:8081") };
        }
        return urls.split(",");
    }

    /**
     * Chain que gera um payload de ordem aleatório (BUY ou SELL ~50/50) e envia POST.
     *
     * <p>Cada virtual user gera UUIDs únicos para walletId, variando price e amount
     * dentro de faixas realistas para gerar matches no motor de ordens.</p>
     */
    public static ChainBuilder placeOrderChain() {
        return exec(session -> {
                    // Round-robin: distribui entre as instâncias de order-service
                    String targetUrl = nextOrderServiceUrl();

                    // Gera dados da ordem — BUY/SELL alternado ~50/50
                    String orderType = RANDOM.nextBoolean() ? "BUY" : "SELL";
                    // Price entre 90.00 e 110.00 — faixa estreita para maximizar matches
                    double price = 90.0 + (RANDOM.nextDouble() * 20.0);
                    // Amount entre 0.1 e 10.0
                    double amount = 0.1 + (RANDOM.nextDouble() * 9.9);

                    return session
                            .set("targetUrl", targetUrl)
                            .set("walletId", UUID.randomUUID().toString())
                            .set("orderType", orderType)
                            .set("price", String.format("%.8f", price))
                            .set("amount", String.format("%.8f", amount))
                            .set("authToken", TOKEN_FEEDER.getBearerToken());
                })
                .exec(
                        http("Place Order")
                                .post("#{targetUrl}/api/v1/orders")
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
                                .check(jsonPath("$.orderId").exists().saveAs("orderId"))
                                .check(jsonPath("$.correlationId").exists().saveAs("correlationId"))
                                .check(jsonPath("$.status").is("PENDING"))
                );
    }

    /**
     * Assertions padrão para todos os cenários.
     *
     * @param maxErrorPercent Percentual máximo de erros aceitável
     * @param maxP99Ms        Latência p99 máxima em milissegundos
     */
    public static io.gatling.javaapi.core.Assertion[] standardAssertions(double maxErrorPercent, long maxP99Ms) {
        return new io.gatling.javaapi.core.Assertion[]{
                global().failedRequests().percent().lt(maxErrorPercent),
                global().responseTime().percentile4().lt((int) maxP99Ms)
        };
    }

    static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }
}
