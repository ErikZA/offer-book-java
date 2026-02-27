package com.vibranium.orderservice.integration;

import com.vibranium.contracts.enums.AssetType;
import com.vibranium.contracts.enums.OrderStatus;
import com.vibranium.contracts.enums.OrderType;
import com.vibranium.contracts.events.wallet.FundsReservedEvent;
import com.vibranium.orderservice.domain.model.Order;
import com.vibranium.orderservice.domain.repository.OrderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Testes de integração para {@code FundsReservedEventConsumer} + {@code RedisMatchEngineAdapter}.
 *
 * <p>Fluxo testado:</p>
 * <pre>
 *   FundsReservedEvent → [queue: order.events.funds-reserved, RK: wallet.events.funds-reserved]
 *       → FundsReservedEventConsumer
 *           → RedisMatchEngineAdapter (Script Lua atômico)
 *               → match:    Order → FILLED/PARTIAL
 *               → sem match: Order → OPEN (BID inserido no Redis)
 * </pre>
 *
 * <p>As asserções verificam o estado final NO BANCO (Order.status) e no Redis
 * (Sorted Set contents), sem necessidade de capturar eventos publicados.</p>
 */
@DisplayName("MatchEngine (Redis + FundsReservedEventConsumer) — Cenários de Matching")
class MatchEngineRedisIntegrationTest extends AbstractIntegrationTest {

    private static final String FUNDS_RESERVED_RK = "wallet.events.funds-reserved";

    @Value("${app.redis.keys.asks:vibranium:asks}")
    private String asksKey;

    @Value("${app.redis.keys.bids:vibranium:bids}")
    private String bidsKey;

    @Autowired
    private OrderRepository orderRepository;

    @BeforeEach
    void setup() {
        orderRepository.deleteAll();
        redisTemplate.delete(asksKey);
        redisTemplate.delete(bidsKey);
    }

    @AfterEach
    void tearDown() {
        redisTemplate.delete(asksKey);
        redisTemplate.delete(bidsKey);
    }

    // =========================================================================
    // Cenário 1 — BID bate exatamente no preço do ASK → Order → FILLED
    // =========================================================================

    @Test
    @DisplayName("Dado BID com preço = ASK, deve executar match e Order→FILLED")
    void whenBidMatchesAskPrice_thenOrderTransitionsToFilled() {
        BigDecimal price = new BigDecimal("500.00");
        BigDecimal qty   = new BigDecimal("10.00000000");

        // ASK pré-existente no Redis
        redisTemplate.opsForZSet().add(asksKey,
                buildRedisValue(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), qty, UUID.randomUUID()),
                priceToScore(price));

        // BID Order no banco → PENDING
        UUID bidOrderId  = UUID.randomUUID();
        UUID bidCorrelId = UUID.randomUUID();
        UUID bidWalletId = UUID.randomUUID();
        Order order = Order.create(bidOrderId, bidCorrelId, UUID.randomUUID().toString(),
                bidWalletId, OrderType.BUY, price, qty);
        orderRepository.save(order);

        FundsReservedEvent event = FundsReservedEvent.of(
                bidCorrelId, bidOrderId, bidWalletId, AssetType.BRL, price.multiply(qty));

        rabbitTemplate.convertAndSend("vibranium.events", FUNDS_RESERVED_RK, event);

        await().atMost(8, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    Order updated = orderRepository.findById(bidOrderId).orElseThrow();
                    assertThat(updated.getStatus())
                            .as("Order BUY deve estar FILLED após match completo")
                            .isEqualTo(OrderStatus.FILLED);
                });

        assertThat(redisTemplate.opsForZSet().zCard(asksKey))
                .as("ASK deve ter sido consumido do livro")
                .isZero();
    }

    // =========================================================================
    // Cenário 2 — BID preço < ASK → sem match → Order → OPEN
    // =========================================================================

    @Test
    @DisplayName("Dado BID com preço abaixo do ASK, Order→OPEN e BID inserido no Redis")
    void whenBidDoesNotMatchAskPrice_thenOrderBecomesOpen() {
        BigDecimal askPrice = new BigDecimal("500.00");
        BigDecimal bidPrice = new BigDecimal("490.00");
        BigDecimal qty      = new BigDecimal("10.00000000");

        redisTemplate.opsForZSet().add(asksKey,
                buildRedisValue(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), qty, UUID.randomUUID()),
                priceToScore(askPrice));

        UUID bidOrderId  = UUID.randomUUID();
        UUID bidCorrelId = UUID.randomUUID();
        UUID bidWalletId = UUID.randomUUID();

        Order order = Order.create(bidOrderId, bidCorrelId, UUID.randomUUID().toString(),
                bidWalletId, OrderType.BUY, bidPrice, qty);
        orderRepository.save(order);

        FundsReservedEvent event = FundsReservedEvent.of(
                bidCorrelId, bidOrderId, bidWalletId, AssetType.BRL, bidPrice.multiply(qty));

        rabbitTemplate.convertAndSend("vibranium.events", FUNDS_RESERVED_RK, event);

        await().atMost(8, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    Order updated = orderRepository.findById(bidOrderId).orElseThrow();
                    assertThat(updated.getStatus()).isEqualTo(OrderStatus.OPEN);
                });

        assertThat(redisTemplate.opsForZSet().zCard(bidsKey))
                .as("BID deve ter sido inserido no livro Redis")
                .isEqualTo(1L);
        assertThat(redisTemplate.opsForZSet().zCard(asksKey)).isEqualTo(1L);
    }

    // =========================================================================
    // Cenário 3 — BID parcialmente preenche ASK → Order→FILLED, ASK residual no Redis
    // =========================================================================

    @Test
    @DisplayName("Dado BID qty=3 e ASK qty=10, BID→FILLED e ASK residual=7 no Redis")
    void whenBidPartiallyFillsAsk_thenAskRemainsInRedisWithReducedQuantity() {
        BigDecimal price  = new BigDecimal("500.00");
        BigDecimal askQty = new BigDecimal("10.00000000");
        BigDecimal bidQty = new BigDecimal("3.00000000");

        redisTemplate.opsForZSet().add(asksKey,
                buildRedisValue(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), askQty, UUID.randomUUID()),
                priceToScore(price));

        UUID bidOrderId  = UUID.randomUUID();
        UUID bidCorrelId = UUID.randomUUID();
        UUID bidWalletId = UUID.randomUUID();

        Order order = Order.create(bidOrderId, bidCorrelId, UUID.randomUUID().toString(),
                bidWalletId, OrderType.BUY, price, bidQty);
        orderRepository.save(order);

        FundsReservedEvent event = FundsReservedEvent.of(
                bidCorrelId, bidOrderId, bidWalletId, AssetType.BRL, price.multiply(bidQty));

        rabbitTemplate.convertAndSend("vibranium.events", FUNDS_RESERVED_RK, event);

        // BID é totalmente executado (bidQty=3 <= askQty=10)
        await().atMost(8, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    Order updated = orderRepository.findById(bidOrderId).orElseThrow();
                    assertThat(updated.getStatus()).isEqualTo(OrderStatus.FILLED);
                });

        // ASK residual = 7 permanece no Redis
        await().atMost(3, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    assertThat(redisTemplate.opsForZSet().zCard(asksKey)).isEqualTo(1L);

                    String askValue = (String) redisTemplate.opsForZSet()
                            .rangeByScore(asksKey, priceToScore(price), priceToScore(price))
                            .stream().findFirst().orElse(null);

                    assertThat(askValue).isNotNull();
                    assertThat(extractQty(askValue))
                            .as("ASK residual deve ter qty=7")
                            .isEqualByComparingTo(new BigDecimal("7.00000000"));
                });
    }

    // =========================================================================
    // Cenário 4 — 2 BIDs simultâneos pelo mesmo ASK (atomicidade Lua)
    // =========================================================================

    @Test
    @DisplayName("Dado 2 BIDs concorrentes, Lua garante que apenas 1 executa o match")
    void whenTwoSimultaneousBidsCompeteForSameAsk_onlyOneWinsAtomically() throws Exception {
        BigDecimal price = new BigDecimal("500.00");
        BigDecimal qty   = new BigDecimal("10.00000000");

        redisTemplate.opsForZSet().add(asksKey,
                buildRedisValue(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), qty, UUID.randomUUID()),
                priceToScore(price));

        List<UUID> orderIds  = new ArrayList<>();
        List<UUID> correlIds = new ArrayList<>();
        List<UUID> walletIds = new ArrayList<>();

        for (int i = 0; i < 2; i++) {
            UUID oid = UUID.randomUUID();
            UUID cid = UUID.randomUUID();
            UUID wid = UUID.randomUUID();
            orderRepository.save(Order.create(oid, cid, UUID.randomUUID().toString(),
                    wid, OrderType.BUY, price, qty));
            orderIds.add(oid);
            correlIds.add(cid);
            walletIds.add(wid);
        }

        CountDownLatch startLatch = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 2; i++) {
                final int idx = i;
                executor.submit(() -> {
                    startLatch.await();
                    rabbitTemplate.convertAndSend("vibranium.events", FUNDS_RESERVED_RK,
                            FundsReservedEvent.of(correlIds.get(idx), orderIds.get(idx),
                                    walletIds.get(idx), AssetType.BRL, price.multiply(qty)));
                    return null;
                });
            }
            startLatch.countDown();
        }

        await().atMost(12, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    long filledCount = orderIds.stream()
                            .map(id -> orderRepository.findById(id).orElseThrow())
                            .filter(o -> o.getStatus() == OrderStatus.FILLED).count();
                    long openCount = orderIds.stream()
                            .map(id -> orderRepository.findById(id).orElseThrow())
                            .filter(o -> o.getStatus() == OrderStatus.OPEN).count();

                    assertThat(filledCount)
                            .as("Somente 1 BID deve ter executado match")
                            .isEqualTo(1L);
                    assertThat(openCount)
                            .as("O outro BID deve estar no livro (OPEN)")
                            .isEqualTo(1L);
                });

        assertThat(redisTemplate.opsForZSet().zCard(asksKey))
                .as("ASK deve ter sido consumido")
                .isZero();
    }

    // =========================================================================
    // Cenário 5 — Simulação de Redis indisponível (desabilitado — requer Toxiproxy)
    // =========================================================================

    @Test
    @Disabled("Requer container Redis dedicado (Toxiproxy) para simular indisponibilidade sem afetar outros testes")
    @DisplayName("Dado Redis indisponível, Order deve ser CANCELLED")
    void whenRedisUnavailable_thenOrderIsCancelled() {
        // Implementar em sprint de resiliência com Toxiproxy ou container dedicado.
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static double priceToScore(BigDecimal price) {
        return price.multiply(BigDecimal.valueOf(1_000_000)).doubleValue();
    }

    private static String buildRedisValue(UUID orderId, UUID userId, UUID walletId,
                                          BigDecimal qty, UUID correlId) {
        return String.join("|",
                orderId.toString(), userId.toString(), walletId.toString(),
                qty.toPlainString(), correlId.toString(),
                String.valueOf(System.currentTimeMillis()));
    }

    private static BigDecimal extractQty(String value) {
        return new BigDecimal(value.split("\\|")[3]);
    }
}
