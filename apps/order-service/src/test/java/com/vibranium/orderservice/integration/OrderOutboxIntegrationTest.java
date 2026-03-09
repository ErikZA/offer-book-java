package com.vibranium.orderservice.integration;

import com.vibranium.contracts.enums.OrderType;
import com.vibranium.orderservice.domain.model.Order;
import com.vibranium.orderservice.domain.repository.OrderOutboxRepository;
import com.vibranium.orderservice.domain.repository.OrderRepository;
import com.vibranium.orderservice.domain.repository.UserRegistryRepository;
import com.vibranium.orderservice.application.dto.PlaceOrderRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * [FASE RED] — Testa o padrão Outbox no {@code order-service}.
 *
 * <p>Valida que {@code OrderCommandService.placeOrder()} persiste o
 * {@link com.vibranium.contracts.commands.wallet.ReserveFundsCommand} na tabela
 * {@code tb_order_outbox} em vez de publicar diretamente no broker,
 * garantindo que o comando não seja perdido mesmo após falha transiente do RabbitMQ.</p>
 *
 * <p>Os testes FALHAM (RED) até que sejam implementados:</p>
 * <ul>
 *   <li>{@code V5__create_order_outbox.sql} — migration Flyway</li>
 *   <li>{@code OrderOutboxMessage} — entidade JPA</li>
 *   <li>{@link OrderOutboxRepository} — repositório Spring Data</li>
 *   <li>{@code OrderOutboxPublisherService} — scheduler de relay</li>
 *   <li>Modificação de {@code OrderCommandService.placeOrder()} para usar outbox</li>
 * </ul>
 */
@DisplayName("OrderOutbox — Garantia de entrega via tabela de outbox")
class OrderOutboxIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderOutboxRepository outboxRepository;

    @Autowired
    private UserRegistryRepository userRegistryRepository;

    private String keycloakId;
    private UUID walletId;

    @BeforeEach
    void setup() {
        outboxRepository.deleteAll();
        orderRepository.deleteAll();

        keycloakId = UUID.randomUUID().toString();
        walletId   = UUID.randomUUID();

        userRegistryRepository.save(buildUserRegistry(keycloakId));
    }

    // =========================================================================
    // Testes do Outbox Pattern
    // =========================================================================

    @Test
    @DisplayName("placeOrder() deve persistir ReserveFundsCommand E OrderReceivedEvent na tabela outbox (AT-01.1)")
    void placeOrder_shouldPersistCommandInOutboxWithinSameTransaction() throws Exception {
        // Act — realiza a chamada REST de colocação de ordem
        mockMvc.perform(post("/api/v1/orders")
                        .contentType("application/json")
                        .content(buildPlaceOrderJson(walletId, "BUY", "100.00", "1.00"))
                        .with(jwt().jwt(j -> j.subject(keycloakId))))
                .andExpect(status().isAccepted());

        // Assert — exatamente 2 mensagens (ReserveFundsCommand + OrderReceivedEvent)
        // AT-01.1: OrderReceivedEvent agora persiste via Outbox, eliminando o Dual Write
        var messages = outboxRepository.findAll();
        assertThat(messages).hasSize(2);

        var eventTypes = messages.stream()
                .map(m -> m.getEventType())
                .toList();
        assertThat(eventTypes)
                .containsExactlyInAnyOrder("ReserveFundsCommand", "OrderReceivedEvent");

        // Ambas com published_at=null (pendentes para o scheduler)
        assertThat(messages).allMatch(m -> m.getPublishedAt() == null);
        assertThat(messages).allMatch(m -> m.getAggregateType().equals("Order"));
    }

    @Test
    @DisplayName("Ordem PENDING deve existir no banco ANTES do outbox publisher enviar a mensagem")
    void placeOrder_orderPersistedBeforeOutboxPublished() throws Exception {
        // Act
        mockMvc.perform(post("/api/v1/orders")
                        .contentType("application/json")
                        .content(buildPlaceOrderJson(walletId, "SELL", "200.00", "0.50"))
                        .with(jwt().jwt(j -> j.subject(keycloakId))))
                .andExpect(status().isAccepted());

        // Assert — ordem persistida imediatamente com status PENDING
        await()
                .atMost(3, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    var orders = orderRepository.findAll();
                    assertThat(orders).hasSize(1);
                    assertThat(orders.get(0).getStatus())
                            .isEqualTo(com.vibranium.contracts.enums.OrderStatus.PENDING);
                });

        // Assert — outbox possui o comando em estado não publicado
        assertThat(outboxRepository.findByPublishedAtIsNull()).isNotEmpty();
    }

    @Test
    @DisplayName("OutboxPublisherService deve publicar mensagens pendentes e marcar published_at")
    void outboxPublisher_shouldPublishPendingMessages() throws Exception {
        // Arrange — coloca ordem para gerar mensagem no outbox
        mockMvc.perform(post("/api/v1/orders")
                        .contentType("application/json")
                        .content(buildPlaceOrderJson(walletId, "BUY", "150.00", "2.00"))
                        .with(jwt().jwt(j -> j.subject(keycloakId))))
                .andExpect(status().isAccepted());

        // Assert — aguarda o scheduler publicar ambas as mensagens (max 15s para cobrir o fixedDelay padrão)
        // AT-01.1: cada placeOrder gera 2 entradas (ReserveFundsCommand + OrderReceivedEvent)
        await()
                .atMost(15, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    var messages = outboxRepository.findAll();
                    assertThat(messages).hasSize(2);
                    // Todas as mensagens devem ter published_at preenchido após o relay
                    assertThat(messages).allMatch(m -> m.getPublishedAt() != null);
                });
    }

    @Test
    @DisplayName("Mensagem AMQP publicada deve conter messageId para garantir idempotência no consumidor")
    void outboxPublisher_shouldSetMessageIdOnAmqpMessage() throws Exception {
        // Act — coloca ordem para gerar ReserveFundsCommand no outbox
        mockMvc.perform(post("/api/v1/orders")
                        .contentType("application/json")
                        .content(buildPlaceOrderJson(walletId, "BUY", "100.00", "1.00"))
                        .with(jwt().jwt(j -> j.subject(keycloakId))))
                .andExpect(status().isAccepted());

        // Assert — aguarda o scheduler publicar mensagens (published_at preenchido)
        await()
                .atMost(15, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    var messages = outboxRepository.findAll();
                    var reserveCmd = messages.stream()
                            .filter(m -> m.getEventType().equals("ReserveFundsCommand"))
                            .findFirst().orElseThrow();

                    // Verifica que foi publicada (published_at != null)
                    assertThat(reserveCmd.getPublishedAt())
                            .as("ReserveFundsCommand deve ter sido publicada pelo scheduler")
                            .isNotNull();

                    // O ID da mensagem outbox (UUID) é o que buildAmqpMessage() usa como
                    // messageId no AMQP — verificado pelo unit test shouldSetMessageIdOnAmqpMessage
                    // em AbstractOutboxPublisherTest. Aqui validamos que o ID existe e é UUID válido.
                    assertThat(reserveCmd.getId())
                            .as("ID da mensagem outbox (usado como messageId AMQP) deve ser UUID não-nulo")
                            .isNotNull();
                    assertThat(reserveCmd.getId().toString())
                            .as("ID deve ser UUID válido (formato padrão)")
                            .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
                });
    }

    @Test
    @DisplayName("Duas ordens distintas devem gerar duas mensagens independentes no outbox")
    void twoDistinctOrders_shouldGenerateTwoOutboxMessages() throws Exception {
        // Arrange — segundo usuário
        String keycloakId2 = UUID.randomUUID().toString();
        UUID walletId2     = UUID.randomUUID();
        userRegistryRepository.save(buildUserRegistry(keycloakId2));

        // Act
        mockMvc.perform(post("/api/v1/orders")
                        .contentType("application/json")
                        .content(buildPlaceOrderJson(walletId, "BUY", "100.00", "1.00"))
                        .with(jwt().jwt(j -> j.subject(keycloakId))))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/api/v1/orders")
                        .contentType("application/json")
                        .content(buildPlaceOrderJson(walletId2, "SELL", "100.00", "1.00"))
                        .with(jwt().jwt(j -> j.subject(keycloakId2))))
                .andExpect(status().isAccepted());

        // Assert — 4 mensagens no outbox (2 ordens × 2 mensagens cada: ReserveFundsCommand + OrderReceivedEvent)
        // AT-01.1: cada placeOrder persiste atomicamente ReserveFundsCommand + OrderReceivedEvent
        var messages = outboxRepository.findAll();
        assertThat(messages).hasSize(4);
        assertThat(messages.stream().filter(m -> m.getEventType().equals("ReserveFundsCommand")).count())
                .isEqualTo(2);
        assertThat(messages.stream().filter(m -> m.getEventType().equals("OrderReceivedEvent")).count())
                .isEqualTo(2);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private String buildPlaceOrderJson(UUID wId, String type, String price, String amount) {
        return """
                {
                  "walletId": "%s",
                  "orderType": "%s",
                  "price": %s,
                  "amount": %s
                }
                """.formatted(wId, type, price, amount);
    }

    private com.vibranium.orderservice.domain.model.UserRegistry buildUserRegistry(String kId) {
        return new com.vibranium.orderservice.domain.model.UserRegistry(kId);
    }
}
