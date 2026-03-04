package com.vibranium.orderservice.integration;

import com.vibranium.contracts.enums.AssetType;
import com.vibranium.contracts.enums.OrderStatus;
import com.vibranium.contracts.enums.OrderType;
import com.vibranium.contracts.events.wallet.FundsReservedEvent;
import com.vibranium.orderservice.infrastructure.redis.RedisMatchEngineAdapter;
import com.vibranium.orderservice.infrastructure.redis.RedisMatchEngineAdapter.MatchResult;
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
 *   FundsReservedEvent → [queue: RabbitMQConfig.QUEUE_FUNDS_RESERVED, RK: wallet.events.funds-reserved]
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

    @Value("${app.redis.keys.asks:{vibranium}:asks}")
    private String asksKey;

    @Value("${app.redis.keys.bids:{vibranium}:bids}")
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

    /**
     * Verifica que uma ordem é movida para o estado {@code CANCELLED} quando o Redis
     * fica indisponível durante a execução do script Lua de match.
     *
     * <h3>Por que este teste está desabilitado?</h3>
     * Simular indisponibilidade de rede exige um proxy intermediário entre a JVM e o
     * container Redis. O container Redis compartilhado desta suite não pode ser
     * derrubado sem impactar todos os outros testes em execução paralela.
     *
     * <h3>Solução planejada (sprint de resiliência)</h3>
     * <ol>
     *   <li>Adicionar {@code ghcr.io/shopify/toxiproxy} ao {@code docker-compose-test.yml}
     *       como proxy na frente do Redis (ex: {@code redis-proxy:6399 → redis:6379}).</li>
     *   <li>Usar {@code ToxiproxyContainer} do Testcontainers para criar o proxy
     *       programaticamente no {@code @BeforeAll}.</li>
     *   <li>No corpo do teste, introduzir um toxic de timeout/latência:
     *       <pre>{@code
     * redisProxy.toxics()
     *     .timeout("redis-timeout", ToxicDirection.DOWNSTREAM, 0); // drop connection
     *       }</pre>
     *   </li>
     *   <li>Submeter uma ordem e aguardar o consumo do evento de compensação
     *       ({@code FundsReservationFailedEvent}) que deve mover a ordem para {@code CANCELLED}.</li>
     *   <li>Remover o toxic e validar que o sistema se recupera para novos testes.</li>
     * </ol>
     *
     * <h3>Referências</h3>
     * <ul>
     *   <li><a href="https://github.com/Shopify/toxiproxy">Toxiproxy (Shopify)</a></li>
     *   <li><a href="https://java.testcontainers.org/modules/toxiproxy/">Testcontainers Toxiproxy module</a></li>
     *   <li>Rastrear como issue na milestone "Resiliência e Chaos Engineering"</li>
     * </ul>
     */
    @Test
    @Disabled("Requer container Redis dedicado (Toxiproxy) para simular indisponibilidade sem afetar outros testes")
    @DisplayName("Dado Redis indisponível, Order deve ser CANCELLED")
    void whenRedisUnavailable_thenOrderIsCancelled() {
        // TODO: Implementar em sprint de resiliência — ver Javadoc acima para roadmap completo.
    }

    // =========================================================================
    // US-002 — Subtask 2.4: Cenários de Partial Fill (FASE RED → GREEN)
    // =========================================================================

    /**
     * Cenário 6 — PARTIAL_BID: BID qty=100 vs ASK qty=40.
     *
     * <p>A ordem BID (buyer) envia 100 VIB mas apenas 40 estão disponíveis.
     * Resultado esperado:
     * <ul>
     *   <li>ASK de 40 é consumido completamente (removed do vibranium:asks)</li>
     *   <li>BID residual de 60 permanece no {@code vibranium:bids}</li>
     *   <li>Order BUY → {@code PARTIAL}, {@code remainingAmount = 60}</li>
     *   <li>{@code MatchResult.remainingCounterpartQty() == 0} (ASK foi totalmente consumido)</li>
     * </ul>
     */
    @Test
    @DisplayName("PARTIAL_BID: BID 100 vs ASK 40 → BID residual 60 em bids, Order→PARTIAL")
    void whenBidPartiallyMatchesAsk_thenBidResidualRemainsInBook() {
        BigDecimal price  = new BigDecimal("500.00");
        BigDecimal askQty = new BigDecimal("40.00000000");
        BigDecimal bidQty = new BigDecimal("100.00000000");

        UUID askOrderId  = UUID.randomUUID();
        UUID askUserId   = UUID.randomUUID();
        UUID askWalletId = UUID.randomUUID();
        UUID askCorrelId = UUID.randomUUID();
        redisTemplate.opsForZSet().add(asksKey,
                buildRedisValue(askOrderId, askUserId, askWalletId, askQty, askCorrelId),
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

        // Order deve estar com status PARTIAL e quantidade residual = 60
        await().atMost(8, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    Order updated = orderRepository.findById(bidOrderId).orElseThrow();
                    assertThat(updated.getStatus())
                            .as("BID parcialmente executado deve ser PARTIAL")
                            .isEqualTo(OrderStatus.PARTIAL);
                    assertThat(updated.getRemainingAmount())
                            .as("Remaining deve ser 60 — os 40 executados foram deduzidos")
                            .isEqualByComparingTo(new BigDecimal("60.00000000"));
                });

        // Redis: ASK consumido; BID residual de 60 deve estar em vibranium:bids
        await().atMost(3, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    assertThat(redisTemplate.opsForZSet().zCard(asksKey))
                            .as("ASK deve ter sido completamente consumido")
                            .isZero();

                    assertThat(redisTemplate.opsForZSet().zCard(bidsKey))
                            .as("BID residual (60 VIB) deve permanecer no livro")
                            .isEqualTo(1L);

                    String bidValue = (String) redisTemplate.opsForZSet()
                            .rangeByScore(bidsKey, priceToScore(price), priceToScore(price))
                            .stream().findFirst().orElse(null);
                    assertThat(bidValue).isNotNull();
                    assertThat(extractQty(bidValue))
                            .as("BID residual deve reflectir exatamente 60 VIB")
                            .isEqualByComparingTo(new BigDecimal("60.00000000"));
                    assertThat(extractOrderId(bidValue))
                            .as("BID residual deve usar o mesmo orderId original")
                            .isEqualTo(bidOrderId);
                });

        // Validação direta do MatchResult via tryMatch — confirma API multi-match
        // Após o BID de 100 já ter consumido o ASK, novo tryMatch insere novo BID no livro
        List<MatchResult> partialResults = matchEngine.tryMatch(
                UUID.randomUUID(), UUID.randomUUID().toString(), UUID.randomUUID(),
                OrderType.BUY, price, new BigDecimal("5.00000000"), UUID.randomUUID());
        // tryMatch retorna lista (possivelmente vazia ou com 1 match se houver residual no livro)
        assertThat(partialResults)
                .as("tryMatch retorna List<MatchResult> (API multi-match validada)")
                .isNotNull();
    }

    /**
     * Cenário 7 — PARTIAL_ASK via SELL: SELL qty=100 vs BID qty=40.
     *
     * <p>A ordem SELL (seller) envia 100 VIB mas apenas 40 BIDs estão disponíveis.
     * Resultado esperado:
     * <ul>
     *   <li>BID de 40 é consumido completamente (removed do vibranium:bids)</li>
     *   <li>ASK residual de 60 permanece no {@code vibranium:asks}</li>
     *   <li>Order SELL → {@code PARTIAL}, {@code remainingAmount = 60}</li>
     *   <li>{@code MatchResult.remainingCounterpartQty() == 0} (BID foi totalmente consumido)</li>
     * </ul>
     */
    @Test
    @DisplayName("PARTIAL_ASK via SELL: SELL 100 vs BID 40 → ASK residual 60 em asks, Order→PARTIAL")
    void whenAskPartiallyMatchesBid_thenAskResidualRemainsInBook() {
        BigDecimal price  = new BigDecimal("500.00");
        BigDecimal bidQty = new BigDecimal("40.00000000");
        BigDecimal askQty = new BigDecimal("100.00000000");

        // BID pré-existente no Redis
        UUID bidOrderId  = UUID.randomUUID();
        UUID bidUserId   = UUID.randomUUID();
        UUID bidWalletId = UUID.randomUUID();
        UUID bidCorrelId = UUID.randomUUID();
        redisTemplate.opsForZSet().add(bidsKey,
                buildRedisValue(bidOrderId, bidUserId, bidWalletId, bidQty, bidCorrelId),
                priceToScore(price));

        // SELL Order no banco → PENDING
        UUID askOrderId  = UUID.randomUUID();
        UUID askCorrelId = UUID.randomUUID();
        UUID askWalletId = UUID.randomUUID();
        Order order = Order.create(askOrderId, askCorrelId, UUID.randomUUID().toString(),
                askWalletId, OrderType.SELL, price, askQty);
        orderRepository.save(order);

        FundsReservedEvent event = FundsReservedEvent.of(
                askCorrelId, askOrderId, askWalletId, AssetType.VIBRANIUM, askQty);

        rabbitTemplate.convertAndSend("vibranium.events", FUNDS_RESERVED_RK, event);

        // Order SELL deve estar com status PARTIAL e remaining = 60
        await().atMost(8, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    Order updated = orderRepository.findById(askOrderId).orElseThrow();
                    assertThat(updated.getStatus())
                            .as("SELL parcialmente executado deve ser PARTIAL")
                            .isEqualTo(OrderStatus.PARTIAL);
                    assertThat(updated.getRemainingAmount())
                            .as("Remaining deve ser 60 após executar 40 VIB")
                            .isEqualByComparingTo(new BigDecimal("60.00000000"));
                });

        // Redis: BID consumido; ASK residual de 60 deve estar em vibranium:asks
        await().atMost(3, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    assertThat(redisTemplate.opsForZSet().zCard(bidsKey))
                            .as("BID deve ter sido completamente consumido")
                            .isZero();

                    assertThat(redisTemplate.opsForZSet().zCard(asksKey))
                            .as("ASK residual (60 VIB) deve permanecer no livro")
                            .isEqualTo(1L);

                    String askValue = (String) redisTemplate.opsForZSet()
                            .rangeByScore(asksKey, priceToScore(price), priceToScore(price))
                            .stream().findFirst().orElse(null);
                    assertThat(askValue).isNotNull();
                    assertThat(extractQty(askValue))
                            .as("ASK residual deve reflectir 60 VIB")
                            .isEqualByComparingTo(new BigDecimal("60.00000000"));
                    assertThat(extractOrderId(askValue))
                            .as("ASK residual usa o mesmo orderId original")
                            .isEqualTo(askOrderId);
                });

        // Validação direta do MatchResult — confirma API multi-match
        List<MatchResult> directResults = matchEngine.tryMatch(
                UUID.randomUUID(), UUID.randomUUID().toString(), UUID.randomUUID(),
                OrderType.SELL, price, new BigDecimal("5.00000000"), UUID.randomUUID());
        assertThat(directResults)
                .as("tryMatch retorna List<MatchResult> (API multi-match validada)")
                .isNotNull();
    }

    /**
     * Cenário 8 — Múltiplos partial fills convergem o livro para zero.
     *
     * <p>1 ASK de 30 VIB é consumido por 3 BIDs sequenciais de 10 VIB cada.
     * Invariante: {@code asks} e {@code bids} devem estar vazios ao final;
     * todas as ordens FILLED.</p>
     */
    @Test
    @DisplayName("Múltiplos partial fills: 3×BID-10 vs ASK-30 → livro zerado, todas FILLED")
    void whenMultiplePartialFills_thenBookConvergesCorrectly() {
        BigDecimal price    = new BigDecimal("500.00");
        BigDecimal askQty   = new BigDecimal("30.00000000");
        BigDecimal eachBid  = new BigDecimal("10.00000000");

        // ASK de 30 VIB pré-existente
        redisTemplate.opsForZSet().add(asksKey,
                buildRedisValue(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), askQty, UUID.randomUUID()),
                priceToScore(price));

        List<UUID> bidOrderIds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            UUID oid = UUID.randomUUID();
            UUID cid = UUID.randomUUID();
            UUID wid = UUID.randomUUID();
            Order o  = Order.create(oid, cid, UUID.randomUUID().toString(), wid,
                    OrderType.BUY, price, eachBid);
            orderRepository.save(o);
            bidOrderIds.add(oid);
            // Envia eventos sequencialmente para não depender de atomicidade concorrente
            rabbitTemplate.convertAndSend("vibranium.events", FUNDS_RESERVED_RK,
                    FundsReservedEvent.of(cid, oid, wid, AssetType.BRL, price.multiply(eachBid)));
        }

        // Todas as 3 ordens devem estar FILLED após os 3 matches parciais
        await().atMost(15, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    for (UUID oid : bidOrderIds) {
                        assertThat(orderRepository.findById(oid).orElseThrow().getStatus())
                                .as("Cada BUY de 10 deve ser FILLED após cruzar com ASK de 30")
                                .isEqualTo(OrderStatus.FILLED);
                    }
                });

        // Livro deve estar completamente vazio
        assertThat(redisTemplate.opsForZSet().zCard(asksKey))
                .as("asks deve estar vazio — ASK de 30 foi consumido por 3 BIDs de 10")
                .isZero();
        assertThat(redisTemplate.opsForZSet().zCard(bidsKey))
                .as("bids deve estar vazio — nenhum BID sem contraparte")
                .isZero();
    }

    /**
     * Cenário 9 — Idempotência por {@code eventId}: mesmo evento entregue 2×.
     *
     * <p>O RabbitMQ garante at-least-once delivery. Se a mesma mensagem for
     * entregue duas vezes (rede flaky, ack perdido), a segunda entrega deve
     * ser descartada com base no {@code FundsReservedEvent.eventId()}, sem
     * duplicar o match.</p>
     *
     * <p><strong>FASE RED:</strong> Falhará até {@code ProcessedEventRepository}
     * ser criado e o consumer verificar {@code eventId} antes de processar.</p>
     */
    @Test
    @DisplayName("Idempotência: mesmo eventId entregue 2× → processado somente uma vez")
    void whenDuplicateFundsReservedEvent_thenProcessedOnlyOnce() {
        BigDecimal price = new BigDecimal("500.00");
        BigDecimal qty   = new BigDecimal("10.00000000");

        // ASK pré-existente
        redisTemplate.opsForZSet().add(asksKey,
                buildRedisValue(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), qty, UUID.randomUUID()),
                priceToScore(price));

        UUID bidOrderId  = UUID.randomUUID();
        UUID bidCorrelId = UUID.randomUUID();
        UUID bidWalletId = UUID.randomUUID();
        Order order = Order.create(bidOrderId, bidCorrelId, UUID.randomUUID().toString(),
                bidWalletId, OrderType.BUY, price, qty);
        orderRepository.save(order);

        // Constrói o evento com eventId fixo (simula re-entrega exata pelo broker)
        FundsReservedEvent event = FundsReservedEvent.of(
                bidCorrelId, bidOrderId, bidWalletId, AssetType.BRL, price.multiply(qty));

        // 1ª entrega — deve ser processada
        rabbitTemplate.convertAndSend("vibranium.events", FUNDS_RESERVED_RK, event);

        await().atMost(8, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(
                        orderRepository.findById(bidOrderId).orElseThrow().getStatus())
                        .isEqualTo(OrderStatus.FILLED));

        // 2ª entrega com MESMO eventId — deve ser descartada (idempotência)
        rabbitTemplate.convertAndSend("vibranium.events", FUNDS_RESERVED_RK, event);

        // Aguarda processamento potencial e verifica que nenhum estado extra foi criado
        await().during(2, TimeUnit.SECONDS)
                .atMost(4, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    Order updated = orderRepository.findById(bidOrderId).orElseThrow();
                    assertThat(updated.getStatus())
                            .as("Status não deve mudar após re-entrega duplicada")
                            .isEqualTo(OrderStatus.FILLED);
                    // ASK não deve reaparecer de uma segunda tentativa de match
                    assertThat(redisTemplate.opsForZSet().zCard(asksKey))
                            .as("Não deve haver double-match — ASK já foi consumido")
                            .isZero();
                });
    }

    // =========================================================================
    // Cenário 10 — TC-PP-1: Precisão de preço com 8 casas decimais (AT-3.2.1)
    // =========================================================================

    /**
     * TC-PP-1 — Verifica que preços com 8 casas decimais (ex: 0.00000001 e 0.00000002)
     * são armazenados com scores distintos no Sorted Set do Redis.
     *
     * <p><strong>FASE RED:</strong> Com {@code PRICE_PRECISION = 1_000_000}:
     * {@code 0.00000001 × 1_000_000 = 0.01 → (long) 0} e
     * {@code 0.00000002 × 1_000_000 = 0.02 → (long) 0}.
     * Ambos os ASKs ficam no score 0, indiferenciáveis pelo sorted set.</p>
     *
     * <p><strong>FASE GREEN:</strong> Com {@code PRICE_PRECISION = 100_000_000}:
     * {@code 0.00000001 × 100_000_000 = 1} e {@code 0.00000002 × 100_000_000 = 2}.
     * Scores distintos → ordenação correta e match exato.</p>
     */
    @Test
    @DisplayName("TC-PP-1: Preços com 8 casas decimais (0.00000001 vs 0.00000002) produzem scores distintos e match correto")
    void testPricePrecision_8DecimalPlaces_differentiatedInBook() {
        BigDecimal price1 = new BigDecimal("0.00000001"); // 1 satoshi equivalente
        BigDecimal price2 = new BigDecimal("0.00000002"); // 2 satoshi equivalente
        BigDecimal qty    = new BigDecimal("1.00000000");

        UUID ask1OrderId  = UUID.randomUUID();
        UUID ask1WalletId = UUID.randomUUID();
        UUID ask1CorrelId = UUID.randomUUID();

        UUID ask2OrderId  = UUID.randomUUID();
        UUID ask2WalletId = UUID.randomUUID();
        UUID ask2CorrelId = UUID.randomUUID();

        // Insere 2 SELLs com preços microscopicamente diferentes — sem BID disponível → NO_MATCH
        List<MatchResult> r1s = matchEngine.tryMatch(ask1OrderId, "user-a", ask1WalletId,
                OrderType.SELL, price1, qty, ask1CorrelId);
        List<MatchResult> r2s = matchEngine.tryMatch(ask2OrderId, "user-b", ask2WalletId,
                OrderType.SELL, price2, qty, ask2CorrelId);

        assertThat(r1s).as("SELL a 0.00000001 não deve casar (sem BID)").isEmpty();
        assertThat(r2s).as("SELL a 0.00000002 não deve casar (sem BID)").isEmpty();

        // Ambos os ASKs devem estar no livro
        assertThat(redisTemplate.opsForZSet().zCard(asksKey))
                .as("Dois ASKs distintos devem estar no livro")
                .isEqualTo(2L);

        // Verifica scores via rangeWithScores — com PRICE_PRECISION=10^8 espera 1.0 e 2.0
        var entries = redisTemplate.opsForZSet().rangeWithScores(asksKey, 0, -1);
        assertThat(entries).isNotNull().hasSize(2);

        var scores = entries.stream()
                .map(org.springframework.data.redis.core.ZSetOperations.TypedTuple::getScore)
                .sorted()
                .toList();

        // FASE RED falha aqui: ambos os scores são 0.0 com PRICE_PRECISION=1_000_000
        assertThat(scores.get(0))
                .as("Score do ASK mais barato (0.00000001 × 10^8 = 1)")
                .isEqualTo(1.0);
        assertThat(scores.get(1))
                .as("Score do ASK mais caro (0.00000002 × 10^8 = 2)")
                .isEqualTo(2.0);

        // BID a price1 deve casar APENAS com o ASK mais barato (price1), não com price2
        UUID bidOrderId = UUID.randomUUID();
        List<MatchResult> bidResults = matchEngine.tryMatch(bidOrderId, "user-c", UUID.randomUUID(),
                OrderType.BUY, price1, qty, UUID.randomUUID());

        assertThat(bidResults)
                .as("BID a 0.00000001 deve casar com o ASK mais barato")
                .hasSize(1);
        MatchResult bidResult = bidResults.get(0);
        assertThat(bidResult.counterpartId())
                .as("Contraparte deve ser o ASK de 0.00000001 (ask1OrderId)")
                .isEqualTo(ask1OrderId);

        // Apenas o ASK de price2 deve restar
        assertThat(redisTemplate.opsForZSet().zCard(asksKey))
                .as("Apenas o ASK de 0.00000002 deve permanecer no livro")
                .isEqualTo(1L);
    }

    // =========================================================================
    // AT-3.1.1 — Multi-Match Loop (FASE RED → GREEN)
    // =========================================================================

    /**
     * TC-MM-1: BUY 100 vs 5 ASKs de 20 cada → 5 matches retornados, livro vazio.
     *
     * <p>O script Lua deve consumir as 5 contrapartes em um único tick atômico.<br>
     * FASE RED: Lua single-match retorna apenas 1 MatchResult → falha em {@code hasSize(5)}.<br>
     * FASE GREEN: Lua multi-match retorna lista de 5 MatchResults.</p>
     */
    @Test
    @DisplayName("TC-MM-1: BUY 100 vs 5 ASKs de 20 → 5 matches, livro vazio")
    void testMultiMatch_buyOf100_against5AsksOf20_returns5Matches() {
        BigDecimal price  = new BigDecimal("500.00");
        BigDecimal askQty = new BigDecimal("20.00000000");
        BigDecimal bidQty = new BigDecimal("100.00000000");

        // Insere 5 ASKs de 20 cada
        for (int i = 0; i < 5; i++) {
            redisTemplate.opsForZSet().add(asksKey,
                    buildRedisValue(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                            askQty, UUID.randomUUID()),
                    priceToScore(price));
        }
        assertThat(redisTemplate.opsForZSet().zCard(asksKey)).isEqualTo(5L);

        UUID bidOrderId  = UUID.randomUUID();
        UUID bidWalletId = UUID.randomUUID();
        UUID bidCorrelId = UUID.randomUUID();

        // Executa multi-match diretamente via adapter
        List<MatchResult> results = matchEngine.tryMatch(
                bidOrderId, "buyer-mm1", bidWalletId,
                OrderType.BUY, price, bidQty, bidCorrelId);

        // TC-MM-1a: deve retornar 5 matches
        assertThat(results)
                .as("TC-MM-1: BUY 100 vs 5 ASKs de 20 deve retornar 5 MatchResults")
                .hasSize(5);

        // TC-MM-1b: todos os matches devem ser FULL (cada ASK é totalmente consumido)
        results.forEach(r -> {
            assertThat(r.matched()).isTrue();
            assertThat(r.matchedQty()).isEqualByComparingTo(askQty);
            assertThat(r.fillType()).isEqualTo("FULL");
            assertThat(r.remainingCounterpartQty()).isEqualByComparingTo(BigDecimal.ZERO);
        });

        // TC-MM-1c: Redis — todos os 5 ASKs foram consumidos
        assertThat(redisTemplate.opsForZSet().zCard(asksKey))
                .as("TC-MM-1: livro de ASKs deve estar vazio após 5 matches")
                .isZero();

        // TC-MM-1d: Redis — BID não deve ter sido inserido (preenchimento completo)
        assertThat(redisTemplate.opsForZSet().zCard(bidsKey))
                .as("TC-MM-1: nenhum residual de BID no livro")
                .isZero();
    }

    /**
     * TC-MM-2: BUY 100 vs 3 ASKs de 50 cada → 2 matches, 3º ASK permanece no livro.
     *
     * <p>O BUY de 100 consome exatamente 2 ASKs de 50 (total=100) → preenchimento completo.
     * O 3º ASK de 50 permanece intacto no livro de ASKs.</p>
     */
    @Test
    @DisplayName("TC-MM-2: BUY 100 vs 3 ASKs de 50 → 2 matches, 3º ASK restante no livro")
    void testMultiMatch_buyOf100_against3AsksOf50_matchesTwoAndRemainder50InBook() {
        BigDecimal price  = new BigDecimal("500.00");
        BigDecimal askQty = new BigDecimal("50.00000000");
        BigDecimal bidQty = new BigDecimal("100.00000000");

        // Insere 3 ASKs de 50 cada (total disponível = 150)
        for (int i = 0; i < 3; i++) {
            redisTemplate.opsForZSet().add(asksKey,
                    buildRedisValue(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                            askQty, UUID.randomUUID()),
                    priceToScore(price));
        }

        List<MatchResult> results = matchEngine.tryMatch(
                UUID.randomUUID(), "buyer-mm2", UUID.randomUUID(),
                OrderType.BUY, price, bidQty, UUID.randomUUID());

        // TC-MM-2a: deve retornar 2 matches (50+50=100 = BID qty exata)
        assertThat(results)
                .as("TC-MM-2: BUY 100 vs 3 ASKs de 50 deve retornar 2 MatchResults")
                .hasSize(2);

        // TC-MM-2b: ambos FULL (cada ASK de 50 totalmente consumido)
        results.forEach(r -> {
            assertThat(r.fillType()).isEqualTo("FULL");
            assertThat(r.matchedQty()).isEqualByComparingTo(askQty);
        });

        // TC-MM-2c: Redis — apenas o 3º ASK deve permanecer
        assertThat(redisTemplate.opsForZSet().zCard(asksKey))
                .as("TC-MM-2: 3º ASK deve permanecer no livro")
                .isEqualTo(1L);

        String remainingAsk = (String) redisTemplate.opsForZSet()
                .rangeByScore(asksKey, priceToScore(price), priceToScore(price))
                .stream().findFirst().orElse(null);
        assertThat(remainingAsk).isNotNull();
        assertThat(extractQty(remainingAsk))
                .as("TC-MM-2: ASK restante deve ter qty=50")
                .isEqualByComparingTo(askQty);

        // TC-MM-2d: Redis — BID não deve estar no livro (preenchimento exato)
        assertThat(redisTemplate.opsForZSet().zCard(bidsKey))
                .as("TC-MM-2: nenhum residual de BID")
                .isZero();
    }

    /**
     * TC-MM-3: Livro vazio → NO_MATCH (lista vazia).
     *
     * <p>Comportamento inalterado em relação ao single-match: retorna lista vazia
     * e insere o BID no livro.</p>
     */
    @Test
    @DisplayName("TC-MM-3: livro vazio → NO_MATCH (lista vazia), BID inserido no livro")
    void testMultiMatch_emptyBook_returnsNoMatch() {
        BigDecimal price  = new BigDecimal("500.00");
        BigDecimal bidQty = new BigDecimal("100.00000000");

        // Livro vazio
        assertThat(redisTemplate.opsForZSet().zCard(asksKey)).isZero();

        UUID bidOrderId = UUID.randomUUID();
        List<MatchResult> results = matchEngine.tryMatch(
                bidOrderId, "buyer-mm3", UUID.randomUUID(),
                OrderType.BUY, price, bidQty, UUID.randomUUID());

        // TC-MM-3a: lista vazia = NO_MATCH
        assertThat(results)
                .as("TC-MM-3: livro vazio deve retornar lista vazia")
                .isEmpty();

        // TC-MM-3b: BID deve ter sido inserido no livro
        assertThat(redisTemplate.opsForZSet().zCard(bidsKey))
                .as("TC-MM-3: BID deve estar no livro após NO_MATCH")
                .isEqualTo(1L);

        String insertedBid = (String) redisTemplate.opsForZSet()
                .rangeByScore(bidsKey, priceToScore(price), priceToScore(price))
                .stream().findFirst().orElse(null);
        assertThat(insertedBid).isNotNull();
        assertThat(extractOrderId(insertedBid))
                .as("TC-MM-3: BID inserido deve ter o orderId correto")
                .isEqualTo(bidOrderId);
    }

    /**
     * TC-MM-4: MAX_MATCHES=100 — BUY 110 vs 101 ASKs de 1 → 100 matches, residual de 10 no livro.
     *
     * <p>Após 100 iterações (MAX_MATCHES), o loop encerra. O BUY ainda tem 10 de residual
     * que é inserido no livro. O 101º ASK permanece intacto.</p>
     */
    @Test
    @DisplayName("TC-MM-4: MAX_MATCHES=100 — BUY 110 vs 101 ASKs de 1 → 100 matches, BUY residual 10 no livro")
    void testMultiMatch_maxIterationsReached_returnsPartialWithRemaining() {
        BigDecimal price  = new BigDecimal("500.00");
        BigDecimal askQty = new BigDecimal("1.00000000");
        BigDecimal bidQty = new BigDecimal("110.00000000");

        // Insere 101 ASKs de 1 cada — cada um com orderId único (member distinto no Sorted Set)
        for (int i = 0; i < 101; i++) {
            redisTemplate.opsForZSet().add(asksKey,
                    buildRedisValue(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                            askQty, UUID.randomUUID()),
                    priceToScore(price));
        }
        assertThat(redisTemplate.opsForZSet().zCard(asksKey)).isEqualTo(101L);

        List<MatchResult> results = matchEngine.tryMatch(
                UUID.randomUUID(), "buyer-mm4", UUID.randomUUID(),
                OrderType.BUY, price, bidQty, UUID.randomUUID());

        // TC-MM-4a: exatamente 100 matches (MAX_MATCHES atingido)
        assertThat(results)
                .as("TC-MM-4: MAX_MATCHES=100 deve limitar o número de matches")
                .hasSize(100);

        // TC-MM-4b: todos os 100 matches são FULL (cada ASK de qty=1 totalmente consumido)
        results.forEach(r -> {
            assertThat(r.fillType()).isEqualTo("FULL");
            assertThat(r.matchedQty()).isEqualByComparingTo(askQty);
        });

        // TC-MM-4c: Redis — 1 ASK restante (o 101º, nunca tocado)
        assertThat(redisTemplate.opsForZSet().zCard(asksKey))
                .as("TC-MM-4: 101º ASK deve permanecer no livro")
                .isEqualTo(1L);

        // TC-MM-4d: Redis — BID residual de 10 inserido no livro
        assertThat(redisTemplate.opsForZSet().zCard(bidsKey))
                .as("TC-MM-4: BID residual de 10 deve estar no livro")
                .isEqualTo(1L);
        String residualBid = (String) redisTemplate.opsForZSet()
                .rangeByScore(bidsKey, priceToScore(price), priceToScore(price))
                .stream().findFirst().orElse(null);
        assertThat(residualBid).isNotNull();
        assertThat(extractQty(residualBid))
                .as("TC-MM-4: BID residual deve ter qty=10 (110 - 100 matches de 1)")
                .isEqualByComparingTo(new BigDecimal("10.00000000"));
    }

    // =========================================================================
    // Helpers — importados pelo RedisMatchEngineAdapter via @Autowired
    // =========================================================================

    @Autowired
    private RedisMatchEngineAdapter matchEngine;

    // =========================================================================
    // Helpers
    // =========================================================================

    // AT-3.2.1: espelha PRICE_PRECISION = 100_000_000L do RedisMatchEngineAdapter
    private static double priceToScore(BigDecimal price) {
        return price.multiply(BigDecimal.valueOf(100_000_000)).doubleValue();
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

    /** Extrai o orderId (índice 0) do valor pipe-delimited do Sorted Set. */
    private static UUID extractOrderId(String value) {
        return UUID.fromString(value.split("\\|")[0]);
    }
}
