package com.vibranium.orderservice.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibranium.contracts.enums.AssetType;
import com.vibranium.contracts.enums.OrderStatus;
import com.vibranium.contracts.enums.OrderType;
import com.vibranium.contracts.events.wallet.FundsReservedEvent;
import com.vibranium.orderservice.application.dto.PlaceOrderRequest;
import com.vibranium.orderservice.domain.model.Order;
import com.vibranium.orderservice.domain.model.UserRegistry;
import com.vibranium.orderservice.domain.repository.OrderRepository;
import com.vibranium.orderservice.domain.repository.UserRegistryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste de integração para validação do RNF01 — Alta Escalabilidade (5.000 trades/s).
 *
 * <h3>Estratégia de validação</h3>
 * <p>Em ambiente Docker local, é impossível alcançar 5.000 req/s reais (CPU/memória
 * compartilhados entre todos os containers). Este teste valida o throughput <b>por instância</b>
 * e projeta a escalabilidade horizontal:</p>
 *
 * <ol>
 *   <li><b>Camada HTTP:</b> Dispara N ordens simultâneas via MockMvc + Virtual Threads
 *       e mede o throughput sustentado do endpoint {@code POST /api/v1/orders}.</li>
 *   <li><b>Camada Saga:</b> Pré-cria N ordens PENDING e dispara N FundsReservedEvents
 *       concorrentes, medindo o throughput do consumer RabbitMQ → PostgreSQL.</li>
 *   <li><b>Projeção:</b> Com base no throughput medido por instância, calcula quantas
 *       instâncias do order-service são necessárias para atingir 5.000 trades/s.</li>
 * </ol>
 *
 * <p>Critérios de aceite:</p>
 * <ul>
 *   <li>Throughput HTTP ≥ 50 req/s por instância (Testcontainers/Docker overhead)</li>
 *   <li>Throughput Saga ≥ 50 events/s por instância</li>
 *   <li>Error rate = 0% (nenhuma requisição rejeitada)</li>
 *   <li>Projeção horizontal ≤ 100 instâncias para 5.000 trades/s (viável em K8s)</li>
 * </ul>
 */
@DisplayName("RNF01 — Alta Escalabilidade: Throughput por Instância")
class Rnf01ScalabilityIntegrationTest extends AbstractIntegrationTest {

    private static final double RNF01_TARGET_GLOBAL = 5000.0;
    private static final int MAX_INSTANCES_VIABLE = 100;

    private static final String ORDER_EVENTS_EXCHANGE = "vibranium.events";
    private static final String FUNDS_RESERVED_RK     = "wallet.events.funds-reserved";

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private UserRegistryRepository userRegistryRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID registeredUserId;

    @BeforeEach
    void cleanup() {
        orderRepository.deleteAllInBatch();
        userRegistryRepository.deleteAll();

        registeredUserId = UUID.randomUUID();
        userRegistryRepository.save(new UserRegistry(registeredUserId.toString()));
    }

    // =========================================================================
    // Teste 1 — Throughput HTTP: N ordens simultâneas via POST /api/v1/orders
    // =========================================================================

    @Test
    @DisplayName("RNF01 — HTTP: 200 ordens concorrentes aceitas com throughput ≥ 50 req/s")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void rnf01_httpThroughput_200ConcurrentOrders() throws Exception {
        // --- ARRANGE ---
        int total = 200;
        CountDownLatch readyLatch = new CountDownLatch(total);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger accepted    = new AtomicInteger(0);
        List<Future<Long>> futures = new ArrayList<>(total);

        PlaceOrderRequest request = new PlaceOrderRequest(
                UUID.randomUUID(), OrderType.BUY,
                new BigDecimal("100.00"), new BigDecimal("1.00000000")
        );
        String requestJson = objectMapper.writeValueAsString(request);

        // --- ACT ---
        long wallStart = System.currentTimeMillis();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < total; i++) {
                futures.add(executor.submit(() -> {
                    readyLatch.countDown();
                    startLatch.await();

                    long t0 = System.currentTimeMillis();
                    mockMvc.perform(post("/api/v1/orders")
                                    .with(jwt().jwt(b -> b.subject(registeredUserId.toString())))
                                    .contentType(APPLICATION_JSON)
                                    .content(requestJson))
                            .andExpect(status().isAccepted());
                    accepted.incrementAndGet();
                    return System.currentTimeMillis() - t0;
                }));
            }
            readyLatch.await();
            startLatch.countDown();

            // Coleta todas as latências
            List<Long> latencies = new ArrayList<>(total);
            for (Future<Long> f : futures) {
                latencies.add(f.get(30, TimeUnit.SECONDS));
            }
        }

        long wallElapsed = System.currentTimeMillis() - wallStart;
        double throughput = total / (wallElapsed / 1000.0);

        // --- ASSERT ---
        assertThat(accepted.get())
                .as("Todas as %d ordens devem ser aceitas (HTTP 202)", total)
                .isEqualTo(total);

        assertThat(throughput)
                .as("Throughput HTTP por instância deve ser ≥ 50 req/s (medido: %.1f req/s)", throughput)
                .isGreaterThanOrEqualTo(50.0);

        // Projeção horizontal
        int instancesNeeded = (int) Math.ceil(RNF01_TARGET_GLOBAL / throughput);
        assertThat(instancesNeeded)
                .as("Instâncias necessárias para 5.000 TPS (%.0f req/s/inst → %d instâncias) "
                    + "devem ser ≤ %d (viável em K8s)", throughput, instancesNeeded, MAX_INSTANCES_VIABLE)
                .isLessThanOrEqualTo(MAX_INSTANCES_VIABLE);

        System.out.printf("""
                %n╔═══════════════════════════════════════════════════════════════╗
                ║  RNF01 — Throughput HTTP (POST /api/v1/orders)                ║
                ╠═══════════════════════════════════════════════════════════════╣
                ║  Ordens disparadas:       %,7d                               ║
                ║  Tempo total:             %,7d ms                            ║
                ║  Throughput medido:        %,7.1f req/s                       ║
                ║  Instâncias p/ 5.000 TPS: %,7d                               ║
                ║  Viável (≤ %d):            %-7s                              ║
                ╚═══════════════════════════════════════════════════════════════╝%n""",
                total, wallElapsed, throughput, instancesNeeded,
                MAX_INSTANCES_VIABLE, instancesNeeded <= MAX_INSTANCES_VIABLE ? "SIM ✓" : "NÃO ✗");
    }

    // =========================================================================
    // Teste 2 — Throughput Saga: N FundsReservedEvents concorrentes processados
    // =========================================================================

    @Test
    @DisplayName("RNF01 — Saga: 500 FundsReservedEvents concorrentes processados com throughput ≥ 50 events/s")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void rnf01_sagaThroughput_500ConcurrentEvents() throws Exception {
        // --- ARRANGE ---
        int total = 500;
        List<UUID> orderIds = new ArrayList<>(total);

        // Cria 500 ordens PENDING
        for (int i = 0; i < total; i++) {
            UUID orderId  = UUID.randomUUID();
            UUID walletId = UUID.randomUUID();
            UUID correlId = UUID.randomUUID();
            Order order = Order.create(
                    orderId, correlId, UUID.randomUUID().toString(), walletId,
                    OrderType.BUY, new BigDecimal("100.00"), new BigDecimal("1.00")
            );
            orderRepository.save(order);
            orderIds.add(orderId);
        }

        // Monta os eventos
        List<FundsReservedEvent> events = new ArrayList<>(total);
        for (UUID orderId : orderIds) {
            Order o = orderRepository.findById(orderId).orElseThrow();
            events.add(FundsReservedEvent.of(
                    o.getCorrelationId(), orderId, o.getWalletId(),
                    AssetType.BRL, o.getPrice().multiply(o.getAmount())
            ));
        }

        // --- ACT --- dispara todos simultaneamente via Virtual Threads
        long wallStart = System.currentTimeMillis();
        CountDownLatch readyLatch = new CountDownLatch(total);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger sent = new AtomicInteger(0);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (FundsReservedEvent event : events) {
                executor.submit((Callable<Void>) () -> {
                    readyLatch.countDown();
                    startLatch.await();
                    rabbitTemplate.convertAndSend(ORDER_EVENTS_EXCHANGE, FUNDS_RESERVED_RK, event);
                    sent.incrementAndGet();
                    return null;
                });
            }
            readyLatch.await();
            startLatch.countDown();
        }

        assertThat(sent.get())
                .as("Todos os %d eventos devem ter sido publicados", total)
                .isEqualTo(total);

        // Aguarda processamento completo
        await().atMost(30, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    long openCount = orderRepository.countByStatus(OrderStatus.OPEN);
                    assertThat(openCount)
                            .as("Todos os %d eventos devem ser processados (PENDING → OPEN)", total)
                            .isEqualTo(total);
                });

        long wallElapsed = System.currentTimeMillis() - wallStart;
        double throughput = total / (wallElapsed / 1000.0);

        // --- ASSERT ---
        long pendingCount = orderRepository.countByStatus(OrderStatus.PENDING);
        assertThat(pendingCount)
                .as("Nenhuma Order deve permanecer PENDING após o batch")
                .isZero();

        assertThat(throughput)
                .as("Throughput Saga por instância deve ser ≥ 50 events/s (medido: %.1f)", throughput)
                .isGreaterThanOrEqualTo(50.0);

        // Projeção horizontal
        int instancesNeeded = (int) Math.ceil(RNF01_TARGET_GLOBAL / throughput);
        assertThat(instancesNeeded)
                .as("Instâncias necessárias para 5.000 TPS (%.0f ev/s/inst → %d instâncias) "
                    + "devem ser ≤ %d (viável em K8s)", throughput, instancesNeeded, MAX_INSTANCES_VIABLE)
                .isLessThanOrEqualTo(MAX_INSTANCES_VIABLE);

        System.out.printf("""
                %n╔═══════════════════════════════════════════════════════════════╗
                ║  RNF01 — Throughput Saga (FundsReservedEvent → OPEN)           ║
                ╠═══════════════════════════════════════════════════════════════╣
                ║  Eventos disparados:      %,7d                               ║
                ║  Tempo total:             %,7d ms                            ║
                ║  Throughput medido:        %,7.1f events/s                    ║
                ║  Instâncias p/ 5.000 TPS: %,7d                               ║
                ║  Viável (≤ %d):            %-7s                              ║
                ╚═══════════════════════════════════════════════════════════════╝%n""",
                total, wallElapsed, throughput, instancesNeeded,
                MAX_INSTANCES_VIABLE, instancesNeeded <= MAX_INSTANCES_VIABLE ? "SIM ✓" : "NÃO ✗");
    }
}
