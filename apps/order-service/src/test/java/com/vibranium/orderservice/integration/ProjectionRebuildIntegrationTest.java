package com.vibranium.orderservice.integration;

import com.vibranium.contracts.enums.OrderType;
import com.vibranium.orderservice.application.query.model.OrderDocument;
import com.vibranium.orderservice.application.query.repository.OrderHistoryRepository;
import com.vibranium.orderservice.application.query.service.ProjectionRebuildService;
import com.vibranium.orderservice.domain.model.Order;
import com.vibranium.orderservice.domain.model.OrderOutboxMessage;
import com.vibranium.orderservice.domain.repository.OrderOutboxRepository;
import com.vibranium.orderservice.domain.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Testes de integração para o mecanismo de Replay/Rebuild da projeção MongoDB (AT-08).
 *
 * <p>Valida que o Read Model (MongoDB) pode ser completamente reconstruído a partir
 * da fonte de verdade (PostgreSQL Command Side + Outbox), garantindo recovery após
 * corrupção ou perda de dados no MongoDB.</p>
 *
 * <p>Cenários cobertos:</p>
 * <ol>
 *   <li><strong>Full Rebuild:</strong> 100 ordens no PG → 100 documentos no MongoDB.</li>
 *   <li><strong>Idempotência:</strong> 2 rebuilds consecutivos → resultado idêntico.</li>
 *   <li><strong>Segurança:</strong> requer role ADMIN (403 sem, 200 com).</li>
 *   <li><strong>Incremental:</strong> processa apenas ordens novas desde último rebuild.</li>
 *   <li><strong>Histórico:</strong> documentos reconstruídos contêm eventos do outbox.</li>
 * </ol>
 */
@DisplayName("Projection Rebuild — Admin Endpoint (AT-08)")
class ProjectionRebuildIntegrationTest extends AbstractMongoIntegrationTest {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderOutboxRepository orderOutboxRepository;

    @Autowired
    private OrderHistoryRepository orderHistoryRepository;

    @Autowired
    private ProjectionRebuildService projectionRebuildService;

    @BeforeEach
    void cleanup() {
        orderHistoryRepository.deleteAll();
        orderOutboxRepository.deleteAll();
        orderRepository.deleteAll();
    }

    // =========================================================================
    // 1. ProjectionRebuildTest — Full Rebuild
    // =========================================================================

    @Nested
    @DisplayName("Full Rebuild")
    class FullRebuildTest {

        @Test
        @DisplayName("POST /admin/projections/rebuild deve reconstruir 100 OrderDocuments a partir do PostgreSQL")
        void rebuild_shouldReconstructAllDocumentsFromPostgreSQL() throws Exception {
            // Arrange — insere 100 ordens com estados variados no PostgreSQL
            List<Order> orders = createOrdersWithVariedStatuses(100);

            // Act — chama endpoint de rebuild com role ADMIN
            mockMvc.perform(post("/admin/projections/rebuild")
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.processed").value(100))
                    .andExpect(jsonPath("$.total").value(100));

            // Assert — verifica que 100 documentos existem no MongoDB
            List<OrderDocument> docs = orderHistoryRepository.findAll();
            assertThat(docs).hasSize(100);

            // Verifica que o status de cada documento corresponde ao PostgreSQL
            for (Order order : orders) {
                Optional<OrderDocument> doc = orderHistoryRepository.findByOrderId(order.getId().toString());
                assertThat(doc)
                        .as("Documento MongoDB para orderId=%s deve existir", order.getId())
                        .isPresent();
                assertThat(doc.get().getStatus())
                        .as("Status do documento deve corresponder ao PostgreSQL para orderId=%s", order.getId())
                        .isEqualTo(order.getStatus().name());
                assertThat(doc.get().getUserId())
                        .isEqualTo(order.getUserId());
                assertThat(doc.get().getOrderType())
                        .isEqualTo(order.getOrderType().name());
                assertThat(doc.get().getPrice())
                        .isEqualByComparingTo(order.getPrice());
                assertThat(doc.get().getOriginalQty())
                        .isEqualByComparingTo(order.getAmount());
                assertThat(doc.get().getRemainingQty())
                        .isEqualByComparingTo(order.getRemainingAmount());
            }
        }

        @Test
        @DisplayName("Rebuild deve incluir histórico de eventos do outbox nos documentos reconstruídos")
        void rebuild_shouldIncludeHistoryFromOutboxMessages() throws Exception {
            // Arrange — cria ordem com eventos no outbox
            UUID orderId = UUID.randomUUID();
            UUID correlationId = UUID.randomUUID();
            String userId = UUID.randomUUID().toString();
            UUID walletId = UUID.randomUUID();

            Order order = Order.create(orderId, correlationId, userId, walletId,
                    OrderType.BUY, new BigDecimal("50000.00"), new BigDecimal("10.0"));
            orderRepository.saveAndFlush(order);

            // Cria mensagens de outbox para esta ordem
            orderOutboxRepository.saveAndFlush(new OrderOutboxMessage(
                    orderId, "Order", "OrderReceivedEvent",
                    "vibranium.events", "order.received",
                    "{\"status\":\"PENDING\"}"));
            orderOutboxRepository.saveAndFlush(new OrderOutboxMessage(
                    orderId, "Order", "FundsReservedEvent",
                    "vibranium.events", "wallet.funds.reserved",
                    "{\"status\":\"OPEN\"}"));

            // Act
            mockMvc.perform(post("/admin/projections/rebuild")
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                    .andExpect(status().isOk());

            // Assert — documento deve conter histórico com 2 entradas do outbox
            Optional<OrderDocument> doc = orderHistoryRepository.findByOrderId(orderId.toString());
            assertThat(doc).isPresent();
            assertThat(doc.get().getHistory()).hasSize(2);
            assertThat(doc.get().getHistory().get(0).eventType()).isEqualTo("OrderReceivedEvent");
            assertThat(doc.get().getHistory().get(1).eventType()).isEqualTo("FundsReservedEvent");
        }
    }

    // =========================================================================
    // 2. ProjectionRebuildIdempotencyTest — Idempotência
    // =========================================================================

    @Nested
    @DisplayName("Idempotency")
    class IdempotencyTest {

        @Test
        @DisplayName("Dois rebuilds consecutivos devem produzir resultado idêntico (idempotente)")
        void rebuild_twiceConsecutive_shouldBeIdempotent() throws Exception {
            // Arrange — insere 50 ordens com outbox events
            List<Order> orders = createOrdersWithVariedStatuses(50);

            // Act — primeiro rebuild
            mockMvc.perform(post("/admin/projections/rebuild")
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.processed").value(50));

            List<OrderDocument> firstResult = orderHistoryRepository.findAll();
            assertThat(firstResult).hasSize(50);

            // Act — segundo rebuild (deve produzir resultado idêntico)
            mockMvc.perform(post("/admin/projections/rebuild")
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.processed").value(50));

            List<OrderDocument> secondResult = orderHistoryRepository.findAll();

            // Assert — mesmo número de documentos e mesmos dados
            assertThat(secondResult).hasSameSizeAs(firstResult);

            for (OrderDocument doc : secondResult) {
                Optional<OrderDocument> matchingFirst = firstResult.stream()
                        .filter(d -> d.getOrderId().equals(doc.getOrderId()))
                        .findFirst();
                assertThat(matchingFirst)
                        .as("Documento orderId=%s deve existir em ambos os resultados", doc.getOrderId())
                        .isPresent();
                assertThat(doc.getStatus()).isEqualTo(matchingFirst.get().getStatus());
                assertThat(doc.getUserId()).isEqualTo(matchingFirst.get().getUserId());
                assertThat(doc.getOrderType()).isEqualTo(matchingFirst.get().getOrderType());
                assertThat(doc.getHistory()).hasSameSizeAs(matchingFirst.get().getHistory());
            }
        }
    }

    // =========================================================================
    // 3. ProjectionRebuildSecurityTest — Segurança
    // =========================================================================

    @Nested
    @DisplayName("Security")
    class SecurityTest {

        @Test
        @DisplayName("POST /admin/projections/rebuild sem role ADMIN deve retornar 403 Forbidden")
        void rebuild_withoutAdminRole_shouldReturn403() throws Exception {
            mockMvc.perform(post("/admin/projections/rebuild")
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("POST /admin/projections/rebuild sem autenticação deve retornar 401 Unauthorized")
        void rebuild_withoutAuthentication_shouldReturn401() throws Exception {
            mockMvc.perform(post("/admin/projections/rebuild"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("POST /admin/projections/rebuild com role ADMIN deve retornar 200 OK")
        void rebuild_withAdminRole_shouldReturn200() throws Exception {
            mockMvc.perform(post("/admin/projections/rebuild")
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                    .andExpect(status().isOk());
        }
    }

    // =========================================================================
    // 4. ProjectionIncrementalRebuildTest — Rebuild Incremental
    // =========================================================================

    @Nested
    @DisplayName("Incremental Rebuild")
    class IncrementalRebuildTest {

        @Test
        @DisplayName("Rebuild incremental deve processar apenas ordens novas desde o último rebuild")
        void incrementalRebuild_shouldOnlyProcessNewOrders() throws Exception {
            // Arrange — insere 50 ordens e executa full rebuild
            createOrdersWithVariedStatuses(50);

            mockMvc.perform(post("/admin/projections/rebuild")
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.processed").value(50));

            assertThat(orderHistoryRepository.findAll()).hasSize(50);

            // Insere 10 ordens novas (após o full rebuild)
            createOrdersWithVariedStatuses(10);

            // Act — rebuild incremental (deve processar apenas as 10 novas)
            mockMvc.perform(post("/admin/projections/rebuild")
                            .param("mode", "incremental")
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.processed").value(10));

            // Assert — total de 60 documentos no MongoDB
            assertThat(orderHistoryRepository.findAll()).hasSize(60);
        }
    }

    // =========================================================================
    // Helpers — criação de dados de teste
    // =========================================================================

    /**
     * Cria ordens no PostgreSQL com estados variados (PENDING, OPEN, PARTIAL, FILLED)
     * e uma mensagem de outbox para cada ordem.
     *
     * <p>Distribuição de estados (rotação por índice mod 4):</p>
     * <ul>
     *   <li>i % 4 == 0 → PENDING</li>
     *   <li>i % 4 == 1 → OPEN</li>
     *   <li>i % 4 == 2 → PARTIAL (executou 5 de 10)</li>
     *   <li>i % 4 == 3 → FILLED (executou 10 de 10)</li>
     * </ul>
     */
    private List<Order> createOrdersWithVariedStatuses(int count) {
        List<Order> orders = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            UUID orderId = UUID.randomUUID();
            UUID correlationId = UUID.randomUUID();
            String userId = UUID.randomUUID().toString();
            UUID walletId = UUID.randomUUID();
            OrderType type = (i % 2 == 0) ? OrderType.BUY : OrderType.SELL;

            Order order = Order.create(orderId, correlationId, userId, walletId,
                    type, new BigDecimal("50000.00"), new BigDecimal("10.0"));

            // Salva no estado PENDING inicial.
            // saveAndFlush + merge retorna entidade gerenciada com version atualizado;
            // reatribuir é obrigatório para evitar StaleObjectStateException no próximo save.
            order = orderRepository.saveAndFlush(order);

            // Transiciona para estados variados
            if (i % 4 == 1) {
                order.markAsOpen();
                order = orderRepository.saveAndFlush(order);
            } else if (i % 4 == 2) {
                order.markAsOpen();
                order = orderRepository.saveAndFlush(order);
                order.applyMatch(new BigDecimal("5.0"));
                order = orderRepository.saveAndFlush(order);
            } else if (i % 4 == 3) {
                order.markAsOpen();
                order = orderRepository.saveAndFlush(order);
                order.applyMatch(new BigDecimal("10.0"));
                order = orderRepository.saveAndFlush(order);
            }

            // Cria mensagem de outbox para reconstrução de histórico
            orderOutboxRepository.saveAndFlush(new OrderOutboxMessage(
                    orderId, "Order", "OrderReceivedEvent",
                    "vibranium.events", "order.received",
                    "{\"orderId\":\"" + orderId + "\"}"));

            orders.add(order);
        }
        return orders;
    }
}
