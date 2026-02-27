package com.vibranium.orderservice.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibranium.contracts.enums.OrderType;
import com.vibranium.orderservice.domain.model.UserRegistry;                  // [RED] não existe
import com.vibranium.orderservice.domain.repository.UserRegistryRepository;   // [RED] não existe
import com.vibranium.orderservice.web.dto.PlaceOrderRequest;                  // [RED] não existe
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.amqp.core.Message;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Testes de integração para {@code OrderCommandController}.
 *
 * <p>Verifica o contrato REST {@code POST /api/v1/orders} do Command Side:</p>
 * <ul>
 *   <li>Validação de usuário no registro local ({@code tb_user_registry})</li>
 *   <li>Retorno {@code 202 Accepted} e publicação de {@code ReserveFundsCommand}</li>
 *   <li>Rejeição de usuário não cadastrado (403)</li>
 *   <li>Validação de payload (400 via Bean Validation)</li>
 *   <li>Latência p99 sob concorrência com Virtual Threads</li>
 * </ul>
 *
 * <p><strong>Estado RED:</strong> {@code OrderCommandController}, {@code PlaceOrderRequest}
 * e {@code UserRegistryRepository} ainda não existem. O ApplicationContext falha ao subir,
 * gerando {@code NoSuchBeanDefinitionException}. Todos os testes devem estar no estado FAIL
 * até que a Fase GREEN seja implementada.</p>
 */
@DisplayName("OrderCommandController — POST /api/v1/orders")
class OrderCommandControllerTest extends AbstractIntegrationTest {

    private static final String RESERVE_FUNDS_QUEUE = "wallet.commands.reserve-funds";

    @Autowired
    private UserRegistryRepository userRegistryRepository; // [RED]

    @Autowired
    private ObjectMapper objectMapper;

    private UUID registeredUserId;
    private UUID walletId;

    @BeforeEach
    void setup() {
        // Cria um usuário no registry local para simular onboarding já realizado
        registeredUserId = UUID.randomUUID();
        walletId = UUID.randomUUID();

        userRegistryRepository.deleteAll();
        // [RED] UserRegistry é a entidade que será criada na Fase GREEN
        UserRegistry userRegistry = new UserRegistry(registeredUserId.toString());
        userRegistryRepository.save(userRegistry);

        // Limpa a queue de comandos para evitar interferência entre testes
        drainQueue(RESERVE_FUNDS_QUEUE);
    }

    // =========================================================================
    // Cenário 1 — Happy Path: usuário autenticado e cadastrado → 202 + comando publicado
    // =========================================================================

    @Test
    @DisplayName("Dado usuário registrado e JWT válido, deve retornar 202 e publicar ReserveFundsCommand")
    void whenRegisteredUserPlacesOrder_thenReturns202AndPublishesReserveFundsCommand()
            throws Exception {
        // --- ARRANGE ---
        PlaceOrderRequest request = new PlaceOrderRequest(
                walletId,
                OrderType.BUY,
                new BigDecimal("500.00"),
                new BigDecimal("10.00000000")
        );

        // --- ACT ---
        mockMvc.perform(post("/api/v1/orders")
                        // jwt() injeta um token mock com sub = registeredUserId
                        // Sem necessidade de Keycloak rodando
                        .with(jwt().jwt(b -> b
                                .subject(registeredUserId.toString())
                                .claim("realm_access", java.util.Map.of("roles", List.of("USER")))))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                // --- ASSERT HTTP ---
                .andExpect(status().isAccepted())            // 202 Accepted
                .andExpect(jsonPath("$.orderId").isNotEmpty())
                .andExpect(jsonPath("$.correlationId").isNotEmpty())
                .andExpect(jsonPath("$.status").value("PENDING"));

        // --- ASSERT RABBIT --- ReserveFundsCommand deve ter chegado na fila da Wallet
        await().atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    Message msg = rabbitTemplate.receive(RESERVE_FUNDS_QUEUE, 1000);
                    assertThat(msg)
                            .as("ReserveFundsCommand deve ser publicado na queue '%s'",
                                    RESERVE_FUNDS_QUEUE)
                            .isNotNull();
                });
    }

    // =========================================================================
    // Cenário 2 — Usuário não registrado → 403 Forbidden
    // =========================================================================

    @Test
    @DisplayName("Dado usuário autenticado mas SEM registro local, deve retornar 403")
    void whenUnregisteredUserPlacesOrder_thenReturns403() throws Exception {
        // --- ARRANGE --- userId que NÃO existe em tb_user_registry
        UUID unknownUserId = UUID.randomUUID();
        PlaceOrderRequest request = new PlaceOrderRequest(
                walletId, OrderType.SELL,
                new BigDecimal("500.00"),
                new BigDecimal("5.00000000")
        );

        // --- ACT + ASSERT ---
        mockMvc.perform(post("/api/v1/orders")
                        .with(jwt().jwt(b -> b.subject(unknownUserId.toString())))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())   // 403: usuário não está no registry local
                .andExpect(jsonPath("$.error").value("USER_NOT_REGISTERED"));
    }

    // =========================================================================
    // Cenário 3 — Payload inválido → 400 Bad Request (Bean Validation)
    // =========================================================================

    @Test
    @DisplayName("Dado payload com preço negativo, deve retornar 400 Bad Request")
    void whenInvalidPricePlaceOrder_thenReturns400() throws Exception {
        // --- ARRANGE ---
        PlaceOrderRequest invalidRequest = new PlaceOrderRequest(
                walletId,
                OrderType.BUY,
                new BigDecimal("-100.00"),    // preço negativo → @Positive falha
                new BigDecimal("10.00000000")
        );

        // --- ACT + ASSERT ---
        mockMvc.perform(post("/api/v1/orders")
                        .with(jwt().jwt(b -> b.subject(registeredUserId.toString())))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("price"));
    }

    @Test
    @DisplayName("Dado payload sem walletId, deve retornar 400 Bad Request")
    void whenMissingWalletIdPlaceOrder_thenReturns400() throws Exception {
        // --- ARRANGE --- walletId null → @NotNull falha
        String bodyWithoutWalletId = """
                {
                    "orderType": "BUY",
                    "price": "500.00",
                    "amount": "10.00000000"
                }
                """;

        // --- ACT + ASSERT ---
        mockMvc.perform(post("/api/v1/orders")
                        .with(jwt().jwt(b -> b.subject(registeredUserId.toString())))
                        .contentType(APPLICATION_JSON)
                        .content(bodyWithoutWalletId))
                .andExpect(status().isBadRequest());
    }

    // =========================================================================
    // Cenário 4 — Sem JWT → 401 Unauthorized
    // =========================================================================

    @Test
    @DisplayName("Dado requisição sem JWT, deve retornar 401 Unauthorized")
    void whenNoJwtToken_thenReturns401() throws Exception {
        PlaceOrderRequest request = new PlaceOrderRequest(
                walletId, OrderType.BUY,
                new BigDecimal("500.00"),
                new BigDecimal("10.00000000")
        );

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // Cenário 5 — Concorrência: 50 ordens simultâneas do mesmo usuário (Virtual Threads)
    // Valida SLA de latência < 200ms p99 e ausência de deadlocks
    // =========================================================================

    @Test
    @DisplayName("Dado 50 ordens concorrentes do mesmo usuário, todas devem receber 202 em < 200ms p99")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void whenFiftyConcurrentOrdersFromSameUser_thenAllAcceptedWithinSLA() throws Exception {
        // --- ARRANGE ---
        int concurrency = 50;
        CountDownLatch startLatch = new CountDownLatch(1);  // todos partem ao mesmo tempo
        CountDownLatch doneLatch  = new CountDownLatch(concurrency);
        AtomicInteger accepted    = new AtomicInteger(0);
        List<Long> latenciesMs    = new ArrayList<>();

        PlaceOrderRequest request = new PlaceOrderRequest(
                walletId, OrderType.BUY,
                new BigDecimal("500.00"),
                new BigDecimal("1.00000000")
        );
        String requestJson = objectMapper.writeValueAsString(request);
        String jwtToken    = "mocked-jwt-" + registeredUserId; // processado via mockMvc jwt()

        // Virtual Thread executor — simula o comportamento do servidor em produção
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Long>> futures = new ArrayList<>();

            for (int i = 0; i < concurrency; i++) {
                futures.add(executor.submit(() -> {
                    startLatch.await(); // todos iniciam simultaneamente
                    long start = System.currentTimeMillis();
                    try {
                        mockMvc.perform(post("/api/v1/orders")
                                        .with(jwt().jwt(b -> b.subject(registeredUserId.toString())))
                                        .contentType(APPLICATION_JSON)
                                        .content(requestJson))
                                .andExpect(status().isAccepted());
                        accepted.incrementAndGet();
                        return System.currentTimeMillis() - start;
                    } finally {
                        doneLatch.countDown();
                    }
                }));
            }

            // Inicia todas as threads simultaneamente
            startLatch.countDown();
            doneLatch.await(25, TimeUnit.SECONDS);

            // Coleta latências
            for (Future<Long> f : futures) {
                latenciesMs.add(f.get());
            }
        }

        // --- ASSERT ---
        assertThat(accepted.get())
                .as("Todas as 50 ordens devem ser aceitas")
                .isEqualTo(concurrency);

        // Calcula p99 das latências
        List<Long> sorted = latenciesMs.stream().sorted().toList();
        long p99 = sorted.get((int) Math.ceil(concurrency * 0.99) - 1);

        assertThat(p99)
                .as("Latência p99 deve ser <= 200ms sob Virtual Threads (SLA de 5000 trades/s)")
                .isLessThanOrEqualTo(200L);
    }

    // =========================================================================
    // Cenário 6 — Ordem de VENDA com fundos registrados → 202
    // =========================================================================

    @Test
    @DisplayName("Dado usuário registrado colocando ordem SELL, deve retornar 202")
    void whenRegisteredUserPlacesSellOrder_thenReturns202() throws Exception {
        PlaceOrderRequest request = new PlaceOrderRequest(
                walletId,
                OrderType.SELL,
                new BigDecimal("499.99"),
                new BigDecimal("2.50000000")
        );

        mockMvc.perform(post("/api/v1/orders")
                        .with(jwt().jwt(b -> b.subject(registeredUserId.toString())))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    // =========================================================================
    // Helper
    // =========================================================================

    /** Drena todas as mensagens de uma queue (evita interferência entre testes). */
    private void drainQueue(String queueName) {
        Message msg;
        do {
            msg = rabbitTemplate.receive(queueName, 100);
        } while (msg != null);
    }
}
