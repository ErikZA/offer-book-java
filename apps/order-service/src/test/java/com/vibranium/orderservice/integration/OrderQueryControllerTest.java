package com.vibranium.orderservice.integration;

import com.vibranium.contracts.enums.AssetType;
import com.vibranium.contracts.enums.FailureReason;
import com.vibranium.contracts.enums.OrderType;
import com.vibranium.contracts.events.order.MatchExecutedEvent;
import com.vibranium.contracts.events.order.OrderCancelledEvent;
import com.vibranium.contracts.events.order.OrderReceivedEvent;
import com.vibranium.contracts.events.wallet.FundsReservedEvent;
import com.vibranium.orderservice.config.RabbitMQConfig;
import com.vibranium.orderservice.domain.model.UserRegistry;
import com.vibranium.orderservice.domain.repository.UserRegistryRepository;
import com.vibranium.orderservice.application.query.model.OrderDocument;
import com.vibranium.orderservice.application.query.repository.OrderHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.TimeUnit;

/**
 * Testes de integração do Query Side — US-003.
 *
 * <p><strong>Estado RED:</strong> todos os testes falham pois os seguintes beans
 * ainda não existem: {@code OrderDocument}, {@code OrderHistoryRepository},
 * {@code OrderQueryController} e {@code OrderEventProjectionConsumer}.
 * A fase GREEN implementa esses componentes para os testes passarem.</p>
 *
 * <p>Cenários cobertos:</p>
 * <ul>
 *   <li>Quando {@code OrderReceivedEvent} chega, documento é criado no Mongo com status PENDING.</li>
 *   <li>Quando {@code MatchExecutedEvent} chega, history contém entrada MATCH_EXECUTED.</li>
 *   <li>GET /api/v1/orders exige JWT válido e retorna 200.</li>
 *   <li>Usuário só vê suas próprias ordens (filtro por userId do JWT).</li>
 * </ul>
 */
@DisplayName("OrderQueryController — Query Side Read Model (US-003)")
class OrderQueryControllerTest extends AbstractMongoIntegrationTest {

    @Autowired
    private UserRegistryRepository userRegistryRepository;

    @Autowired
    private OrderHistoryRepository orderHistoryRepository;

    private UUID userId;
    private UUID walletId;
    private UUID orderId;
    private UUID correlationId;

    @BeforeEach
    void setup() {
        userId        = UUID.randomUUID();
        walletId      = UUID.randomUUID();
        orderId       = UUID.randomUUID();
        correlationId = UUID.randomUUID();

        // Garante usuário registrado para publicar OrderReceivedEvent
        userRegistryRepository.deleteAll();
        UserRegistry ur = new UserRegistry(userId.toString());
        userRegistryRepository.save(ur);

        // Limpa o Read Model entre testes (idempotência garantida mas estado limpo)
        orderHistoryRepository.deleteAll();
    }

    // =========================================================================
    // TC-1: Criação de documento no Mongo ao receber OrderReceivedEvent
    // =========================================================================

    /**
     * [RED → GREEN] Publica {@code OrderReceivedEvent} diretamente na exchange de eventos.
     * O consumidor de projeção deve criar um {@code OrderDocument} com status PENDING.
     */
    @Test
    @DisplayName("whenOrderReceived_thenDocumentCreatedInMongoWithPendingStatus")
    void whenOrderReceived_thenDocumentCreatedInMongo() {
        // GIVEN — monta evento de ordem recebida
        OrderReceivedEvent event = OrderReceivedEvent.of(
                correlationId,
                orderId,
                userId,                             // UUID do usuario
                walletId,
                OrderType.BUY,
                new BigDecimal("50000.00"),
                new BigDecimal("0.5")
        );

        // WHEN — publica diretamente na exchange de eventos (simula o OrderCommandService)
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.EVENTS_EXCHANGE,
                RabbitMQConfig.RK_ORDER_RECEIVED,
                event
        );

        // THEN — aguarda o consumidor processar e verifica o documento criado
        await().atMost(10, TimeUnit.SECONDS)
               .untilAsserted(() -> {
                   Optional<OrderDocument> doc = orderHistoryRepository.findById(orderId.toString());
                   assertThat(doc).isPresent();
                   assertThat(doc.get().getStatus()).isEqualTo("PENDING");
                   assertThat(doc.get().getUserId()).isEqualTo(userId.toString());
                   assertThat(doc.get().getHistory()).hasSize(1);
                   assertThat(doc.get().getHistory().get(0).eventType()).isEqualTo("ORDER_RECEIVED");
               });
    }

    // =========================================================================
    // TC-2: MatchExecutedEvent appenda entrada no history
    // =========================================================================

    /**
     * [RED → GREEN] Cria documento PENDING e depois publica {@code MatchExecutedEvent}.
     * O history deve conter entrada "MATCH_EXECUTED" e status → FILLED.
     */
    @Test
    @DisplayName("whenMatchExecuted_thenHistoryContainsMatchEntryAndStatusFilled")
    void whenMatchExecuted_thenHistoryContainsMatchEntry() {
        // GIVEN — cria documento inicial via evento de recebimento
        OrderReceivedEvent received = OrderReceivedEvent.of(
                correlationId, orderId, userId, walletId,
                OrderType.BUY, new BigDecimal("50000.00"), new BigDecimal("1.0")
        );
        rabbitTemplate.convertAndSend(RabbitMQConfig.EVENTS_EXCHANGE, RabbitMQConfig.RK_ORDER_RECEIVED, received);

        await().atMost(10, TimeUnit.SECONDS)
               .until(() -> orderHistoryRepository.existsById(orderId.toString()));

        // WHEN — publica MatchExecutedEvent (orderId como buyOrderId)
        UUID sellOrderId   = UUID.randomUUID();
        UUID sellerUserId  = UUID.randomUUID();
        MatchExecutedEvent matchEvent = MatchExecutedEvent.of(
                correlationId,
                orderId,                          // buyOrderId
                sellOrderId,                      // sellOrderId
                userId,                           // buyerUserId
                sellerUserId,                     // sellerUserId
                walletId,                         // buyerWalletId
                UUID.randomUUID(),                // sellerWalletId
                new BigDecimal("50000.00"),
                new BigDecimal("1.0")
        );
        rabbitTemplate.convertAndSend(RabbitMQConfig.EVENTS_EXCHANGE, RabbitMQConfig.RK_MATCH_EXECUTED, matchEvent);

        // THEN — history deve conter entrada de match e status FILLED
        await().atMost(10, TimeUnit.SECONDS)
               .untilAsserted(() -> {
                   Optional<OrderDocument> doc = orderHistoryRepository.findById(orderId.toString());
                   assertThat(doc).isPresent();
                   assertThat(doc.get().getStatus()).isEqualTo("FILLED");
                   assertThat(doc.get().getHistory())
                           .anyMatch(h -> h.eventType().equals("MATCH_EXECUTED"));
               });
    }

    // =========================================================================
    // TC-3: GET /api/v1/orders requer JWT válido e retorna 200
    // =========================================================================

    /**
     * [RED → GREEN] Sem JWT → 401. Com JWT válido → 200 com array de ordens.
     */
    @Test
    @DisplayName("getOrdersList_withoutJwt_returns401")
    void getOrdersList_requiresValidJwt_returns200() throws Exception {
        // GIVEN — documento no Mongo para o usuário
        OrderReceivedEvent received = OrderReceivedEvent.of(
                correlationId, orderId, userId, walletId,
                OrderType.BUY, new BigDecimal("50000.00"), new BigDecimal("0.1")
        );
        rabbitTemplate.convertAndSend(RabbitMQConfig.EVENTS_EXCHANGE, RabbitMQConfig.RK_ORDER_RECEIVED, received);
        await().atMost(10, TimeUnit.SECONDS)
               .until(() -> orderHistoryRepository.existsById(orderId.toString()));

        // WHEN/THEN — sem JWT: 401
        mockMvc.perform(get("/api/v1/orders"))
               .andExpect(status().isUnauthorized());

        // WHEN/THEN — com JWT válido: 200
        mockMvc.perform(get("/api/v1/orders")
                        .with(jwt().jwt(j -> j.subject(userId.toString()))))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.content").isArray());
    }

    // =========================================================================
    // TC-4: Filtro por usuário — não expõe ordens de outros usuários
    // =========================================================================

    /**
     * [RED → GREEN] Usuário A cria ordem; usuário B autenticado não a vê.
     */
    @Test
    @DisplayName("getOrders_filtersOnlyOwnOrders_notOthersOrders")
    void getOrders_filtersOnlyOwnOrders_notOthersOrders() throws Exception {
        // GIVEN — ordem do userId (usuário A)
        OrderReceivedEvent received = OrderReceivedEvent.of(
                correlationId, orderId, userId, walletId,
                OrderType.SELL, new BigDecimal("50000.00"), new BigDecimal("0.2")
        );
        rabbitTemplate.convertAndSend(RabbitMQConfig.EVENTS_EXCHANGE, RabbitMQConfig.RK_ORDER_RECEIVED, received);
        await().atMost(10, TimeUnit.SECONDS)
               .until(() -> orderHistoryRepository.existsById(orderId.toString()));

        // WHEN — usuário B (diferente) consulta suas ordens
        UUID userBId = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/orders")
                        .with(jwt().jwt(j -> j.subject(userBId.toString()))))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.content").isEmpty());

        // THEN — usuário A vê sua própria ordem
        mockMvc.perform(get("/api/v1/orders")
                        .with(jwt().jwt(j -> j.subject(userId.toString()))))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.content[0].orderId").value(orderId.toString()));
    }

    // =========================================================================
    // TC-5: Idempotência — evento duplicado não duplica history entry
    // =========================================================================

    /**
     * [RED → GREEN] Publica o mesmo evento duas vezes e verifica que history tem apenas 1 entrada.
     */
    @Test
    @DisplayName("whenSameEventPublishedTwice_thenHistoryHasOnlyOneEntry")
    void whenSameEventPublishedTwice_thenHistoryHasOnlyOneEntry() {
        // GIVEN — mesmo OrderReceivedEvent publicado 2x
        OrderReceivedEvent event = OrderReceivedEvent.of(
                correlationId, orderId, userId, walletId,
                OrderType.BUY, new BigDecimal("50000.00"), new BigDecimal("0.3")
        );

        // WHEN
        rabbitTemplate.convertAndSend(RabbitMQConfig.EVENTS_EXCHANGE, RabbitMQConfig.RK_ORDER_RECEIVED, event);
        rabbitTemplate.convertAndSend(RabbitMQConfig.EVENTS_EXCHANGE, RabbitMQConfig.RK_ORDER_RECEIVED, event);

        // THEN — somente 1 entrada no history (idempotente)
        await().atMost(10, TimeUnit.SECONDS)
               .untilAsserted(() -> {
                   Optional<OrderDocument> doc = orderHistoryRepository.findById(orderId.toString());
                   assertThat(doc).isPresent();
                   // Idempotência: mesmo evento 2x → somente 1 entry no history
                   long orderReceivedCount = doc.get().getHistory().stream()
                           .filter(h -> h.eventType().equals("ORDER_RECEIVED"))
                           .count();
                   assertThat(orderReceivedCount).isEqualTo(1);
               });
    }

    // =========================================================================
    // TC-6: GET /api/v1/orders/{orderId} retorna detalhe com history
    // =========================================================================

    /**
     * [RED → GREEN] Consulta por ID retorna documento completo com array history[].
     */
    @Test
    @DisplayName("getOrderById_returnsDocumentWithHistory")
    void getOrderById_returnsDocumentWithHistory() throws Exception {
        // GIVEN
        OrderReceivedEvent received = OrderReceivedEvent.of(
                correlationId, orderId, userId, walletId,
                OrderType.BUY, new BigDecimal("50000.00"), new BigDecimal("0.5")
        );
        rabbitTemplate.convertAndSend(RabbitMQConfig.EVENTS_EXCHANGE, RabbitMQConfig.RK_ORDER_RECEIVED, received);
        await().atMost(10, TimeUnit.SECONDS)
               .until(() -> orderHistoryRepository.existsById(orderId.toString()));

        // WHEN/THEN
        mockMvc.perform(get("/api/v1/orders/{orderId}", orderId)
                        .with(jwt().jwt(j -> j.subject(userId.toString()))))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.orderId").value(orderId.toString()))
               .andExpect(jsonPath("$.history").isArray())
               .andExpect(jsonPath("$.history[0].eventType").value("ORDER_RECEIVED"));
    }

    // =========================================================================
    // TC-7: OrderCancelledEvent → status CANCELLED no history
    // =========================================================================

    /**
     * [RED → GREEN] Publica {@code OrderCancelledEvent} após recebimento.
     * O documento deve ter status CANCELLED e history com entrada "ORDER_CANCELLED".
     */
    @Test
    @DisplayName("whenOrderCancelled_thenHistoryContainsCancelledEntry")
    void whenOrderCancelled_thenHistoryContainsCancelledEntry() {
        // GIVEN — cria doc PENDING
        OrderReceivedEvent received = OrderReceivedEvent.of(
                correlationId, orderId, userId, walletId,
                OrderType.BUY, new BigDecimal("50000.00"), new BigDecimal("0.5")
        );
        rabbitTemplate.convertAndSend(RabbitMQConfig.EVENTS_EXCHANGE, RabbitMQConfig.RK_ORDER_RECEIVED, received);
        await().atMost(10, TimeUnit.SECONDS)
               .until(() -> orderHistoryRepository.existsById(orderId.toString()));

        // WHEN
        OrderCancelledEvent cancelEvent = OrderCancelledEvent.of(
                correlationId, orderId, FailureReason.INSUFFICIENT_FUNDS, "Saldo insuficiente"
        );
        rabbitTemplate.convertAndSend(RabbitMQConfig.EVENTS_EXCHANGE, RabbitMQConfig.RK_ORDER_CANCELLED, cancelEvent);

        // THEN
        await().atMost(10, TimeUnit.SECONDS)
               .untilAsserted(() -> {
                   Optional<OrderDocument> doc = orderHistoryRepository.findById(orderId.toString());
                   assertThat(doc).isPresent();
                   assertThat(doc.get().getStatus()).isEqualTo("CANCELLED");
                   assertThat(doc.get().getHistory())
                           .anyMatch(h -> h.eventType().equals("ORDER_CANCELLED"));
               });
    }

    // =========================================================================
    // TC-8: BOLA/IDOR — ownership check em GET /api/v1/orders/{orderId} (AT-4.1.1)
    // =========================================================================

    /**
     * [RED → GREEN] Usuário diferente tenta acessar ordem alheia → 403 Forbidden.
     *
     * <p>Valida proteção IDOR (OWASP API Security BOLA): o {@code jwt.sub} deve
     * bater com o {@code OrderDocument.userId}; caso contrário, 403 é retornado.</p>
     */
    @Test
    @DisplayName("getOrderById_differentUser_returns403 (AT-4.1.1)")
    void testGetOrderById_differentUser_returns403() throws Exception {
        // GIVEN — ordem pertencente ao userId (usuário A)
        OrderReceivedEvent received = OrderReceivedEvent.of(
                correlationId, orderId, userId, walletId,
                OrderType.BUY, new BigDecimal("50000.00"), new BigDecimal("0.5")
        );
        rabbitTemplate.convertAndSend(RabbitMQConfig.EVENTS_EXCHANGE, RabbitMQConfig.RK_ORDER_RECEIVED, received);
        await().atMost(10, TimeUnit.SECONDS)
               .until(() -> orderHistoryRepository.existsById(orderId.toString()));

        // WHEN — usuário B (diferente) tenta consultar a ordem do usuário A
        UUID userBId = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/orders/{orderId}", orderId)
                        .with(jwt().jwt(j -> j.subject(userBId.toString()))))
               .andExpect(status().isForbidden());
    }

    /**
     * [RED → GREEN] Dono da ordem acessa com JWT correto → 200 OK.
     *
     * <p>Quando {@code jwt.sub} coincide com {@code OrderDocument.userId},
     * o endpoint retorna o documento normalmente.</p>
     */
    @Test
    @DisplayName("getOrderById_sameUser_returns200 (AT-4.1.1)")
    void testGetOrderById_sameUser_returns200() throws Exception {
        // GIVEN
        OrderReceivedEvent received = OrderReceivedEvent.of(
                correlationId, orderId, userId, walletId,
                OrderType.BUY, new BigDecimal("50000.00"), new BigDecimal("0.5")
        );
        rabbitTemplate.convertAndSend(RabbitMQConfig.EVENTS_EXCHANGE, RabbitMQConfig.RK_ORDER_RECEIVED, received);
        await().atMost(10, TimeUnit.SECONDS)
               .until(() -> orderHistoryRepository.existsById(orderId.toString()));

        // WHEN/THEN — o próprio dono da ordem
        mockMvc.perform(get("/api/v1/orders/{orderId}", orderId)
                        .with(jwt().jwt(j -> j.subject(userId.toString()))))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.orderId").value(orderId.toString()));
    }

    /**
     * [RED → GREEN] Usuário com {@code ROLE_ADMIN} acessa ordem de outro usuário → 200 OK.
     *
     * <p>Admin bypass: se o claim {@code roles} contiver {@code ROLE_ADMIN}, o filtro
     * de ownership é ignorado — necessário para suporte e auditoria interna.</p>
     */
    @Test
    @DisplayName("getOrderById_adminRole_returns200 (AT-4.1.1)")
    void testGetOrderById_adminRole_returns200() throws Exception {
        // GIVEN — ordem pertencente ao userId (usuário A)
        OrderReceivedEvent received = OrderReceivedEvent.of(
                correlationId, orderId, userId, walletId,
                OrderType.BUY, new BigDecimal("50000.00"), new BigDecimal("0.5")
        );
        rabbitTemplate.convertAndSend(RabbitMQConfig.EVENTS_EXCHANGE, RabbitMQConfig.RK_ORDER_RECEIVED, received);
        await().atMost(10, TimeUnit.SECONDS)
               .until(() -> orderHistoryRepository.existsById(orderId.toString()));

        // WHEN/THEN — admin com sub diferente do dono consegue acessar
        UUID adminId = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/orders/{orderId}", orderId)
                        .with(jwt().jwt(j -> j
                                .subject(adminId.toString())
                                .claim("roles", java.util.List.of("ROLE_ADMIN")))))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.orderId").value(orderId.toString()));
    }
}
