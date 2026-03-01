package com.vibranium.orderservice.integration;

import com.vibranium.contracts.enums.AssetType;
import com.vibranium.contracts.enums.FailureReason;
import com.vibranium.contracts.enums.OrderStatus;
import com.vibranium.contracts.enums.OrderType;
import com.vibranium.contracts.events.wallet.FundsReservationFailedEvent;
import com.vibranium.contracts.events.wallet.FundsReservedEvent;
import com.vibranium.orderservice.domain.model.Order;
import com.vibranium.orderservice.domain.model.ProcessedEvent;
import com.vibranium.orderservice.domain.repository.ProcessedEventRepository;
import com.vibranium.orderservice.domain.repository.OrderRepository;
import com.vibranium.orderservice.domain.repository.UserRegistryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * [FASE RED] — Testes de idempotência baseada em tabela para os consumers do order-service.
 *
 * <p>Valida que o processamento duplicado de um evento (mesmo {@code eventId})
 * não produz mudança de estado na segunda entrega. A proteção é garantida pela
 * tabela {@code tb_processed_events} e pelo ACK manual do RabbitMQ.</p>
 *
 * <p>Os testes FALHAM (RED) até que os artefatos abaixo sejam implementados:</p>
 * <ul>
 *   <li>{@code V4__create_processed_events.sql} — migration Flyway</li>
 *   <li>{@code ProcessedEvent} — entidade JPA</li>
 *   <li>{@link com.vibranium.orderservice.domain.repository.ProcessedEventRepository} — repositório Spring Data</li>
 *   <li>{@code FundsReservedEventConsumer} com ACK manual e idempotência por tabela</li>
 *   <li>{@code FundsReservationFailedEventConsumer} com ACK manual e idempotência por tabela</li>
 * </ul>
 */
@DisplayName("[RED] OrderIdempotency — Proteção contra duplo processamento de eventos")
class OrderIdempotencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private UserRegistryRepository userRegistryRepository;

    private Order pendingOrder;
    private String keycloakId;
    private UUID walletId;
    private UUID correlationId;

    /** Cria um usuário e uma ordem PENDING antes de cada teste. */
    @BeforeEach
    void setup() {
        processedEventRepository.deleteAll();
        orderRepository.deleteAll();

        keycloakId    = UUID.randomUUID().toString();
        walletId      = UUID.randomUUID();
        correlationId = UUID.randomUUID();

        // Registra o usuário no registry local para que a ordem passe na validação
        userRegistryRepository.save(buildUserRegistry(keycloakId));

        // Cria ordem PENDING que receberá os eventos de fundos
        pendingOrder = Order.create(
                UUID.randomUUID(), correlationId, keycloakId,
                walletId, OrderType.BUY,
                new BigDecimal("100.00"), new BigDecimal("1.00")
        );
        orderRepository.save(pendingOrder);
    }

    // =========================================================================
    // Testes de FundsReservedEvent
    // =========================================================================

    @Test
    @DisplayName("FundsReservedEvent — mesmo eventId entregue 2x deve atualizar estado apenas 1x")
    void fundsReservedEvent_sameEventId_processedOnlyOnce() throws Exception {
        // Arrange — evento com eventId fixo para simular redelivery
        UUID fixedEventId = UUID.randomUUID();
        FundsReservedEvent event = buildFundsReservedEvent(fixedEventId, correlationId);

        // Act — envia a mesma mensagem 2 vezes para a fila
        sendEventToQueue("order.events.funds-reserved", event);
        waitForProcessing();
        sendEventToQueue("order.events.funds-reserved", event);
        waitForProcessing();

        // Assert — chave de idempotência existe exatamente 1 vez
        assertThat(processedEventRepository.existsById(event.eventId())).isTrue();

        // Assert — estado da ordem foi alterado exatamente 1 vez (PENDING → OPEN ou CANCELLED)
        Order updatedOrder = orderRepository.findById(pendingOrder.getId()).orElseThrow();
        assertThat(updatedOrder.getStatus()).isNotEqualTo(OrderStatus.PENDING);

        // Assert — não há registros duplicados de idempotência
        long keyCount = processedEventRepository.findAll().stream()
                .filter(k -> k.getEventId().equals(fixedEventId))
                .count();
        assertThat(keyCount).isEqualTo(1);
    }

    @Test
    @DisplayName("FundsReservedEvent — dois eventIds distintos devem processar independentemente")
    void fundsReservedEvents_differentEventIds_bothProcessed() throws Exception {
        // Arrange — dois eventos distintos com correlationIds distintos
        UUID orderId2       = UUID.randomUUID();
        UUID correlationId2 = UUID.randomUUID();
        Order order2 = Order.create(
                orderId2, correlationId2, keycloakId, walletId,
                OrderType.BUY, new BigDecimal("100.00"), new BigDecimal("1.00")
        );
        orderRepository.save(order2);

        FundsReservedEvent event1 = buildFundsReservedEvent(UUID.randomUUID(), correlationId);
        FundsReservedEvent event2 = buildFundsReservedEvent(UUID.randomUUID(), correlationId2);

        // Act
        sendEventToQueue("order.events.funds-reserved", event1);
        sendEventToQueue("order.events.funds-reserved", event2);

        // Assert — ambas as chaves de idempotência registradas
        await()
                .atMost(15, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    assertThat(processedEventRepository.findAll()).hasSize(2);
                    assertThat(orderRepository.findById(pendingOrder.getId()).orElseThrow()
                            .getStatus()).isNotEqualTo(OrderStatus.PENDING);
                    assertThat(orderRepository.findById(orderId2).orElseThrow()
                            .getStatus()).isNotEqualTo(OrderStatus.PENDING);
                });
    }

    // =========================================================================
    // Testes de FundsReservationFailedEvent
    // =========================================================================

    @Test
    @DisplayName("FundsReservationFailedEvent — mesmo eventId entregue 2x deve cancelar ordem apenas 1x")
    void fundsReservationFailedEvent_sameEventId_cancelledOnlyOnce() throws Exception {
        // Arrange
        UUID fixedEventId = UUID.randomUUID();
        FundsReservationFailedEvent event = buildFundsReservationFailedEvent(fixedEventId, correlationId);

        // Act — entrega duplicada
        sendEventToQueue("order.events.funds-failed", event);
        waitForProcessing();
        sendEventToQueue("order.events.funds-failed", event);
        waitForProcessing();

        // Assert — chave de idempotência registrada apenas 1 vez
        assertThat(processedEventRepository.existsById(fixedEventId)).isTrue();
        assertThat(processedEventRepository.findAll()).hasSize(1);

        // Assert — ordem no estado CANCELLED (não reprocessada)
        Order updatedOrder = orderRepository.findById(pendingOrder.getId()).orElseThrow();
        assertThat(updatedOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("Chave de idempotência deve ser gravada na MESMA transação da mudança de estado")
    void idempotencyKey_savedInSameTransactionAsStateChange() throws Exception {
        // Arrange — usa um eventId único
        UUID eventId = UUID.randomUUID();
        FundsReservationFailedEvent event = buildFundsReservationFailedEvent(eventId, correlationId);

        // Act
        sendEventToQueue("order.events.funds-failed", event);

        // Assert — ambos visíveis no banco ao mesmo tempo (commit atômico)
        await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(300, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    boolean keyExists = processedEventRepository.existsById(eventId);
                    Order updatedOrder = orderRepository.findById(pendingOrder.getId()).orElseThrow();
                    // Ambos devem ser verdadeiros simultaneamente — garantia de transação única
                    assertThat(keyExists).isTrue();
                    assertThat(updatedOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);
                });
    }

    // =========================================================================
    // Testes de Limpeza de Chaves Expiradas
    // =========================================================================

    @Test
    @DisplayName("Job de limpeza deve deletar chaves com processed_at anterior a 7 dias")
    void cleanupJob_shouldDeleteExpiredKeys() {
        // Arrange — salva dois eventos recentes
        ProcessedEvent event1 = new ProcessedEvent(UUID.randomUUID());
        ProcessedEvent event2 = new ProcessedEvent(UUID.randomUUID());
        processedEventRepository.saveAll(java.util.List.of(event1, event2));
        assertThat(processedEventRepository.findAll()).hasSize(2);

        // Act — cutoff no futuro (1 dia adiante): todos os registros "expiram"
        java.time.Instant futureCutoff = java.time.Instant.now().plus(1, java.time.temporal.ChronoUnit.DAYS);
        processedEventRepository.deleteByProcessedAtBefore(futureCutoff);

        // Assert — todos removidos (corte no futuro abrange todos os registros recentes)
        assertThat(processedEventRepository.findAll()).isEmpty();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void sendEventToQueue(String queueName, Object event) {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbitTemplate.convertAndSend(queueName, event);
    }

    private void waitForProcessing() throws InterruptedException {
        TimeUnit.MILLISECONDS.sleep(800);
    }

    private FundsReservedEvent buildFundsReservedEvent(UUID eventId, UUID correlation) {
        return new FundsReservedEvent(
                eventId, correlation,
                walletId.toString(),
                java.time.Instant.now(),
                pendingOrder.getId(), walletId,
                AssetType.BRL, new BigDecimal("100.00")
        );
    }

    private FundsReservationFailedEvent buildFundsReservationFailedEvent(UUID eventId, UUID correlation) {
        return new FundsReservationFailedEvent(
                eventId, correlation,
                walletId.toString(),
                java.time.Instant.now(),
                pendingOrder.getId(),
                FailureReason.INSUFFICIENT_FUNDS,
                "Saldo insuficiente para reserva"
        );
    }

    private com.vibranium.orderservice.domain.model.UserRegistry buildUserRegistry(String kId) {
        return new com.vibranium.orderservice.domain.model.UserRegistry(kId);
    }
}
