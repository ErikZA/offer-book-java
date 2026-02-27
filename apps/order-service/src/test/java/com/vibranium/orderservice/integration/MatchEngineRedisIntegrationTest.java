package com.vibranium.orderservice.integration;

import com.vibranium.contracts.enums.AssetType;
import com.vibranium.contracts.events.FundsReservedEvent;      // [RED] deve existir em common-contracts
import com.vibranium.contracts.events.MatchExecutedEvent;      // [RED] deve existir em common-contracts
import com.vibranium.contracts.events.OrderAddedToBookEvent;   // [RED] deve existir em common-contracts
import com.vibranium.contracts.events.OrderCancelledEvent;     // [RED] deve existir em common-contracts
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Testes de integração para {@code RedisMatchEngineAdapter} — motor de matching sobre Redis Sorted Set.
 *
 * <p>Fluxo testado:</p>
 * <pre>
 *   FundsReservedEvent → [order.events.funds-reserved]
 *       → RabbitMQ → MatchEngineConsumer
 *           → Lua script (ZRANGEBYSCORE + ZREM atomicamente)
 *               → se match: publica MatchExecutedEvent
 *               → se sem match: publica OrderAddedToBookEvent (insere em Redis)
 * </pre>
 *
 * <p><strong>Formato do valor no Sorted Set</strong> (pipe-delimited):</p>
 * <pre>{orderId}|{userId}|{walletId}|{quantity}|{correlationId}|{epochMs}</pre>
 *
 * <p><strong>Score</strong>: {@code price.multiply(1_000_000).longValue()} — permite
 * preços com até 2 casas decimais sem perda de precisão em {@code double}.</p>
 *
 * <p><strong>Estado RED:</strong> {@code RedisMatchEngineAdapter}, {@code FundsReservedEvent},
 * {@code MatchExecutedEvent} e {@code OrderAddedToBookEvent} ainda não existem.
 * Os testes irão FALHAR em tempo de compilação até a Fase GREEN.</p>
 */
@DisplayName("MatchEngine (Redis Sorted Set) — Cenários de Matching")
class MatchEngineRedisIntegrationTest extends AbstractIntegrationTest {

    // -------------------------------------------------------------------------
    // Exchanges e routing keys conforme application.yaml
    // -------------------------------------------------------------------------
    private static final String ORDER_EVENTS_EXCHANGE  = "vibranium.events";
    private static final String FUNDS_RESERVED_RK      = "order.events.funds-reserved";
    private static final String MATCH_EXECUTED_RK      = "order.events.match-executed";
    private static final String ORDER_ADDED_RK         = "order.events.order-added-to-book";
    private static final String ORDER_CANCELLED_RK     = "order.events.order-cancelled";

    // Redis keys conforme application.yaml (app.redis.keys)
    private static final String ASKS_KEY = "vibranium:asks";
    private static final String BIDS_KEY = "vibranium:bids";

    /** Listas thread-safe para capturar eventos publicados pelo sistema sob teste. */
    private final List<MatchExecutedEvent>    matchEvents     = new CopyOnWriteArrayList<>();
    private final List<OrderAddedToBookEvent> addedToBookEvts = new CopyOnWriteArrayList<>();
    private final List<OrderCancelledEvent>   cancelledEvts   = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setup() {
        // Garante Sorted Sets limpos antes de cada teste
        redisTemplate.delete(ASKS_KEY);
        redisTemplate.delete(BIDS_KEY);
        matchEvents.clear();
        addedToBookEvts.clear();
        cancelledEvts.clear();
    }

    @AfterEach
    void tearDown() {
        redisTemplate.delete(ASKS_KEY);
        redisTemplate.delete(BIDS_KEY);
    }

    // =========================================================================
    // Cenário 1 — BID bate exatamente no preço de um ASK → MatchExecutedEvent
    // =========================================================================

    @Test
    @DisplayName("Dado BID com preço = ASK, deve executar match e publicar MatchExecutedEvent")
    void whenBidMatchesAskPrice_thenMatchExecutedEventPublished() {
        // --- ARRANGE ---
        UUID askOrderId    = UUID.randomUUID();
        UUID askUserId     = UUID.randomUUID();
        UUID askWalletId   = UUID.randomUUID();
        UUID askCorrelId   = UUID.randomUUID();
        BigDecimal price   = new BigDecimal("500.00");
        BigDecimal qty     = new BigDecimal("10.00000000");

        // Insere ASK diretamente no Sorted Set (simula ordem já no livro)
        String askValue = buildAskValue(askOrderId, askUserId, askWalletId, qty, askCorrelId);
        double score    = priceToScore(price);
        redisTemplate.opsForZSet().add(ASKS_KEY, askValue, score);

        // Evento que chega da wallet-service após reserva de fundos (BID)
        UUID bidOrderId  = UUID.randomUUID();
        UUID bidUserId   = UUID.randomUUID();
        UUID bidWalletId = UUID.randomUUID();
        UUID bidCorrelId = UUID.randomUUID();
        // [RED] FundsReservedEvent.of() — factory method que será criado na Fase GREEN
        FundsReservedEvent event = FundsReservedEvent.of(
                bidCorrelId, bidOrderId, bidWalletId,
                AssetType.BRL, price.multiply(qty)
        );
        event.setMatchPrice(price);
        event.setMatchQty(qty);
        event.setUserId(bidUserId.toString());

        // --- ACT --- publica evento para o consumidor do match engine
        rabbitTemplate.convertAndSend(ORDER_EVENTS_EXCHANGE, FUNDS_RESERVED_RK, event);

        // --- ASSERT ---
        await().atMost(8, TimeUnit.SECONDS)
                .untilAsserted(() ->
                        assertThat(matchEvents)
                                .as("Deve ocorrer exatamente 1 match executado")
                                .hasSize(1));

        MatchExecutedEvent match = matchEvents.get(0);
        assertThat(match.getBidOrderId()).isEqualTo(bidOrderId);
        assertThat(match.getAskOrderId()).isEqualTo(askOrderId);
        assertThat(match.getPrice()).isEqualByComparingTo(price);
        assertThat(match.getQuantity()).isEqualByComparingTo(qty);

        // ASK deve ter sido removido do Redis
        assertThat(redisTemplate.opsForZSet().zCard(ASKS_KEY)).isZero();
    }

    // =========================================================================
    // Cenário 2 — BID com preço < ASK → sem match → OrderAddedToBookEvent
    // =========================================================================

    @Test
    @DisplayName("Dado BID com preço abaixo do ASK, deve adicionar BID ao livro e publicar OrderAddedToBookEvent")
    void whenBidDoesNotMatchAskPrice_thenOrderAddedToBookEventPublished() {
        // --- ARRANGE ---
        BigDecimal askPrice = new BigDecimal("500.00");
        BigDecimal bidPrice = new BigDecimal("490.00"); // < askPrice → sem match

        UUID askOrderId  = UUID.randomUUID();
        String askValue  = buildAskValue(askOrderId, UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("10.00"), UUID.randomUUID());
        redisTemplate.opsForZSet().add(ASKS_KEY, askValue, priceToScore(askPrice));

        UUID bidCorrelId = UUID.randomUUID();
        UUID bidOrderId  = UUID.randomUUID();
        FundsReservedEvent event = FundsReservedEvent.of(
                bidCorrelId, bidOrderId, UUID.randomUUID(),
                AssetType.BRL, bidPrice.multiply(new BigDecimal("10.00"))
        );
        event.setMatchPrice(bidPrice);
        event.setMatchQty(new BigDecimal("10.00"));
        event.setUserId(UUID.randomUUID().toString());

        // --- ACT ---
        rabbitTemplate.convertAndSend(ORDER_EVENTS_EXCHANGE, FUNDS_RESERVED_RK, event);

        // --- ASSERT ---
        await().atMost(8, TimeUnit.SECONDS)
                .untilAsserted(() ->
                        assertThat(addedToBookEvts).hasSize(1));

        OrderAddedToBookEvent added = addedToBookEvts.get(0);
        assertThat(added.getOrderId()).isEqualTo(bidOrderId);

        // BID deve ter sido inserido na lista de bids
        assertThat(redisTemplate.opsForZSet().zCard(BIDS_KEY))
                .as("BID deve estar no livro de ordens Redis")
                .isEqualTo(1L);

        // ASK original permanece no livro
        assertThat(redisTemplate.opsForZSet().zCard(ASKS_KEY)).isEqualTo(1L);
    }

    // =========================================================================
    // Cenário 3 — BID parcialmente preenche ASK → ASK permanece com qtd reduzida
    // =========================================================================

    @Test
    @DisplayName("Dado BID com qty < ASK qty, ASK deve permanecer no Redis com quantidade residual")
    void whenBidPartiallyFillsAsk_thenAskRemainsInRedisWithReducedQuantity() {
        // --- ARRANGE ---
        BigDecimal price     = new BigDecimal("500.00");
        BigDecimal askQty    = new BigDecimal("10.00000000");
        BigDecimal bidQty    = new BigDecimal("3.00000000");    // match parcial
        BigDecimal remaining = askQty.subtract(bidQty);        // 7.0

        UUID askOrderId = UUID.randomUUID();
        UUID askCorrelId = UUID.randomUUID();
        String askValue = buildAskValue(askOrderId, UUID.randomUUID(), UUID.randomUUID(),
                askQty, askCorrelId);
        redisTemplate.opsForZSet().add(ASKS_KEY, askValue, priceToScore(price));

        FundsReservedEvent event = FundsReservedEvent.of(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                AssetType.BRL, price.multiply(bidQty)
        );
        event.setMatchPrice(price);
        event.setMatchQty(bidQty);
        event.setUserId(UUID.randomUUID().toString());

        // --- ACT ---
        rabbitTemplate.convertAndSend(ORDER_EVENTS_EXCHANGE, FUNDS_RESERVED_RK, event);

        // --- ASSERT --- o ASK original é substituído por um novo valor com qtd residual
        await().atMost(8, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    // Deve existir exatamente 1 ASK no Redis
                    assertThat(redisTemplate.opsForZSet().zCard(ASKS_KEY)).isEqualTo(1L);

                    // Esse ASK deve ter a quantidade residual correta
                    String remainingAsk = (String) redisTemplate.opsForZSet()
                            .rangeByScore(ASKS_KEY,
                                    priceToScore(price),
                                    priceToScore(price))
                            .stream().findFirst().orElse(null);

                    assertThat(remainingAsk).isNotNull();
                    BigDecimal remainingQty = extractQuantity(remainingAsk);
                    assertThat(remainingQty).isEqualByComparingTo(remaining);
                });
    }

    // =========================================================================
    // Cenário 4 — 2 BIDs simultâneos competem pelo mesmo ASK (atomicidade Lua)
    // Apenas 1 deve vencer; o outro deve receber OrderAddedToBookEvent
    // =========================================================================

    @Test
    @DisplayName("Dado 2 BIDs concorrentes para o mesmo ASK, somente 1 deve executar match (Lua atômico)")
    void whenTwoSimultaneousBidsCompeteForSameAsk_onlyOneWinsAtomically()
            throws InterruptedException {
        // --- ARRANGE ---
        BigDecimal price = new BigDecimal("500.00");
        BigDecimal qty   = new BigDecimal("10.00000000");

        // 1 único ASK no livro
        String askValue = buildAskValue(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), qty, UUID.randomUUID());
        redisTemplate.opsForZSet().add(ASKS_KEY, askValue, priceToScore(price));

        // 2 eventos FundsReserved chegando simultaneamente
        FundsReservedEvent event1 = FundsReservedEvent.of(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                AssetType.BRL, price.multiply(qty));
        event1.setMatchPrice(price);
        event1.setMatchQty(qty);
        event1.setUserId(UUID.randomUUID().toString());

        FundsReservedEvent event2 = FundsReservedEvent.of(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                AssetType.BRL, price.multiply(qty));
        event2.setMatchPrice(price);
        event2.setMatchQty(qty);
        event2.setUserId(UUID.randomUUID().toString());

        CountDownLatch startLatch = new CountDownLatch(1);

        // --- ACT --- dispara os 2 BIDs exatamente ao mesmo tempo via Virtual Threads
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.submit(() -> {
                startLatch.await();
                rabbitTemplate.convertAndSend(ORDER_EVENTS_EXCHANGE, FUNDS_RESERVED_RK, event1);
                return null;
            });
            executor.submit(() -> {
                startLatch.await();
                rabbitTemplate.convertAndSend(ORDER_EVENTS_EXCHANGE, FUNDS_RESERVED_RK, event2);
                return null;
            });
            startLatch.countDown(); // ambos disparam ao mesmo tempo
        }

        // --- ASSERT ---
        await().atMost(10, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    int totalEvents = matchEvents.size() + addedToBookEvts.size();
                    assertThat(totalEvents)
                            .as("Deve processar exatamente 2 eventos no total")
                            .isEqualTo(2);
                });

        assertThat(matchEvents)
                .as("Somente 1 dos 2 BIDs deve executar match (Lua garantiu atomicidade)")
                .hasSize(1);
        assertThat(addedToBookEvts)
                .as("O BID que perdeu a disputa deve ter sido adicionado ao livro")
                .hasSize(1);

        // ASK deve ter sido consumido
        assertThat(redisTemplate.opsForZSet().zCard(ASKS_KEY))
                .as("ASK deve ter sido removido após o match")
                .isZero();
    }

    // =========================================================================
    // Cenário 5 — Redis indisponível → OrderCancelledEvent (resiliência)
    // =========================================================================

    @Test
    @DisplayName("Dado Redis indisponível (conexão perdida), deve publicar OrderCancelledEvent")
    void whenRedisUnavailable_thenOrderCancelledEventPublished() throws Exception {
        // --- ARRANGE --- desconecta Redis temporariamente bloqueando a porta
        // Não matamos o container, somente paramos de aceitar conexões do Lettuce
        // via iptables (em Linux). Em Windows/TestContainers usamos pause/unpause.
        REDIS.pause();  // [RED] GenericContainer.pause() bloqueia execução de novos comandos

        try {
            UUID bidCorrelId = UUID.randomUUID();
            FundsReservedEvent event = FundsReservedEvent.of(
                    bidCorrelId, UUID.randomUUID(), UUID.randomUUID(),
                    AssetType.BRL, new BigDecimal("5000.00")
            );
            event.setMatchPrice(new BigDecimal("500.00"));
            event.setMatchQty(new BigDecimal("10.00"));
            event.setUserId(UUID.randomUUID().toString());

            // --- ACT ---
            rabbitTemplate.convertAndSend(ORDER_EVENTS_EXCHANGE, FUNDS_RESERVED_RK, event);

            // --- ASSERT --- Spring Retry deve esgotar as tentativas e publicar cancelamento
            await().atMost(30, TimeUnit.SECONDS)
                    .untilAsserted(() ->
                            assertThat(cancelledEvts)
                                    .as("Deve publicar OrderCancelledEvent após Redis falhar")
                                    .hasSize(1));

            assertThat(cancelledEvts.get(0).getCorrelationId()).isEqualTo(bidCorrelId);
            assertThat(cancelledEvts.get(0).getReason()).contains("REDIS_UNAVAILABLE");

        } finally {
            // Garante que o Redis volta independente do resultado do teste
            REDIS.unpause();
        }
    }

    // =========================================================================
    // Listeners auxiliares — capturam eventos publicados pelo sistema
    // =========================================================================

    /**
     * Listener interno do teste que captura {@link MatchExecutedEvent}s publicados
     * pelo {@code MatchEngineConsumer}. Registrado dinamicamente no contexto Spring.
     *
     * <p>Nota: como os listeners de teste precisam do contexto Spring, eles são
     * declarados aqui como beans internos estáticos e auto-detectados via {@code @Component}.
     * Em produção, esses listeners NÃO existem.</p>
     */
    @Component
    static class MatchEventCaptor {

        private List<MatchExecutedEvent>    matchEvents;
        private List<OrderAddedToBookEvent> addedToBookEvts;
        private List<OrderCancelledEvent>   cancelledEvts;

        @RabbitListener(queues = "#{@matchExecutedTestQueue}")
        void onMatchExecuted(MatchExecutedEvent event) {
            if (matchEvents != null) matchEvents.add(event);
        }

        @RabbitListener(queues = "#{@orderAddedToBookTestQueue}")
        void onOrderAddedToBook(OrderAddedToBookEvent event) {
            if (addedToBookEvts != null) addedToBookEvts.add(event);
        }

        @RabbitListener(queues = "#{@orderCancelledTestQueue}")
        void onOrderCancelled(OrderCancelledEvent event) {
            if (cancelledEvts != null) cancelledEvts.add(event);
        }

        void wire(List<MatchExecutedEvent>    matchEvents,
                  List<OrderAddedToBookEvent> addedToBookEvts,
                  List<OrderCancelledEvent>   cancelledEvts) {
            this.matchEvents     = matchEvents;
            this.addedToBookEvts = addedToBookEvts;
            this.cancelledEvts   = cancelledEvts;
        }
    }

    @Autowired
    private MatchEventCaptor matchEventCaptor;

    @BeforeEach
    void wireCaptor() {
        matchEventCaptor.wire(matchEvents, addedToBookEvts, cancelledEvts);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Converte preço em score do Sorted Set.
     * Multiplica por 1_000_000 para preservar 6 casas decimais como inteiro.
     * Exemplo: 500.00 → 500_000_000.0 (cabe em double sem perda de precisão).
     */
    private static double priceToScore(BigDecimal price) {
        return price.multiply(BigDecimal.valueOf(1_000_000)).doubleValue();
    }

    /**
     * Formata o valor do membro do Sorted Set conforme contrato do {@code RedisMatchEngineAdapter}.
     * Formato: {@code orderId|userId|walletId|quantity|correlationId|epochMs}
     */
    private static String buildAskValue(UUID orderId, UUID userId, UUID walletId,
                                        BigDecimal qty, UUID correlId) {
        return String.join("|",
                orderId.toString(),
                userId.toString(),
                walletId.toString(),
                qty.toPlainString(),
                correlId.toString(),
                String.valueOf(Instant.now().toEpochMilli())
        );
    }

    /**
     * Extrai a quantidade (4º campo) de um valor do Sorted Set.
     */
    private static BigDecimal extractQuantity(String value) {
        String[] parts = value.split("\\|");
        return new BigDecimal(parts[3]);
    }
}
