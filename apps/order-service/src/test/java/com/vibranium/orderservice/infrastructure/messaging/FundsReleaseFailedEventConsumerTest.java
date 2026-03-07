package com.vibranium.orderservice.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.vibranium.contracts.enums.FailureReason;
import com.vibranium.contracts.enums.OrderStatus;
import com.vibranium.contracts.enums.OrderType;
import com.vibranium.contracts.events.order.OrderCancelledEvent;
import com.vibranium.contracts.events.wallet.FundsReleaseFailedEvent;
import com.vibranium.orderservice.domain.model.Order;
import com.vibranium.orderservice.domain.model.OrderOutboxMessage;
import com.vibranium.orderservice.domain.model.ProcessedEvent;
import com.vibranium.orderservice.domain.repository.OrderOutboxRepository;
import com.vibranium.orderservice.domain.repository.OrderRepository;
import com.vibranium.orderservice.application.service.EventStoreService;
import com.vibranium.orderservice.domain.repository.ProcessedEventRepository;
import com.vibranium.utils.jackson.VibraniumJacksonConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * [FASE RED → GREEN] — Testes unitários do {@link FundsReleaseFailedEventConsumer}.
 *
 * <h2>Critérios de aceite (Atividade 5)</h2>
 * <ol>
 *   <li>{@code FundsReleaseFailedEvent} deve cancelar a ordem com reason {@code RELEASE_FAILED}.</li>
 *   <li>{@code OrderCancelledEvent} deve ser gravado no outbox.</li>
 *   <li>Idempotência via {@code ProcessedEvent}: evento duplicado descartado com ACK.</li>
 *   <li>Log CRITICAL/ERROR emitido com todos os campos do evento.</li>
 *   <li>Métrica {@code vibranium.funds.release.failed} incrementada.</li>
 *   <li>Ordem já {@code CANCELLED} ou {@code FILLED} → ignorar silenciosamente (WARN + ACK).</li>
 * </ol>
 *
 * <h2>Histórico TDD</h2>
 * <p><strong>FASE RED:</strong> {@link FundsReleaseFailedEventConsumer} ainda
 * não existe — o teste não compila. Evidência formal do ciclo RED.</p>
 * <p><strong>FASE GREEN:</strong> Implementação criada — todos os testes verdes.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Atividade 5 — FundsReleaseFailedEventConsumer: cancelamento + outbox + métrica")
class FundsReleaseFailedEventConsumerTest {

    // -------------------------------------------------------------------------
    // Mocks e dependências
    // -------------------------------------------------------------------------

    @Mock private OrderRepository          orderRepository;
    @Mock private OrderOutboxRepository    outboxRepository;
    @Mock private ProcessedEventRepository processedEventRepository;
    @Mock private EventStoreService        eventStoreService;
    @Mock private Channel                  channel;

    private final ObjectMapper objectMapper = VibraniumJacksonConfig.configure(new ObjectMapper());
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Captor
    private ArgumentCaptor<OrderOutboxMessage> outboxCaptor;

    @Captor
    private ArgumentCaptor<Order> orderCaptor;

    private FundsReleaseFailedEventConsumer consumer;

    // -------------------------------------------------------------------------
    // Dados de teste reutilizados
    // -------------------------------------------------------------------------

    private UUID orderId;
    private UUID correlationId;
    private UUID walletId;

    @BeforeEach
    void setUp() {
        consumer = new FundsReleaseFailedEventConsumer(
                orderRepository, outboxRepository, processedEventRepository,
                objectMapper, meterRegistry, eventStoreService
        );

        orderId       = UUID.randomUUID();
        correlationId = UUID.randomUUID();
        walletId      = UUID.randomUUID();
    }

    // =========================================================================
    // Cenário 1 — Processamento normal: cancel + outbox + métrica
    // =========================================================================

    @Test
    @DisplayName("[RED→GREEN] FundsReleaseFailedEvent deve cancelar ordem com RELEASE_FAILED e gravar OrderCancelledEvent no outbox")
    void testReleaseFailed_cancelsOrderAndWritesOutbox() throws Exception {
        // Arrange: ordem OPEN que falhou na liberação de fundos
        Order order = Order.create(
                orderId, correlationId,
                UUID.randomUUID().toString(),
                walletId,
                OrderType.BUY,
                new BigDecimal("500.00"),
                new BigDecimal("10.00000000")
        );
        order.markAsOpen();

        FundsReleaseFailedEvent event = FundsReleaseFailedEvent.of(
                correlationId, orderId, walletId.toString(),
                FailureReason.RELEASE_DB_ERROR, "DB write failed during release"
        );

        given(processedEventRepository.saveAndFlush(any()))
                .willReturn(new ProcessedEvent(event.eventId()));
        given(orderRepository.findByCorrelationId(correlationId))
                .willReturn(Optional.of(order));

        // Act
        consumer.onFundsReleaseFailed(event, channel, 1L);

        // Assert: ordem cancelada com reason RELEASE_FAILED
        verify(orderRepository).save(orderCaptor.capture());
        Order savedOrder = orderCaptor.getValue();
        assertThat(savedOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(savedOrder.getCancellationReason()).contains("RELEASE_FAILED");

        // Assert: OrderCancelledEvent gravado no outbox
        verify(outboxRepository).save(outboxCaptor.capture());
        OrderOutboxMessage outboxMsg = outboxCaptor.getValue();
        assertThat(outboxMsg.getEventType()).isEqualTo("OrderCancelledEvent");
        assertThat(outboxMsg.getPayload()).contains(orderId.toString());

        // Assert: desserializa o payload para validar campos
        OrderCancelledEvent cancelledEvent = objectMapper.readValue(
                outboxMsg.getPayload(), OrderCancelledEvent.class
        );
        assertThat(cancelledEvent.orderId()).isEqualTo(orderId);
        assertThat(cancelledEvent.reason()).isEqualTo(FailureReason.RELEASE_DB_ERROR);

        // Assert: ACK enviado após commit
        verify(channel).basicAck(1L, false);

        // Assert: métrica incrementada
        Counter counter = meterRegistry.find("vibranium.funds.release.failed").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    // =========================================================================
    // Cenário 2 — Idempotência: evento duplicado descartado com ACK
    // =========================================================================

    @Test
    @DisplayName("[RED→GREEN] Evento duplicado deve ser descartado com ACK (idempotência)")
    void testReleaseFailed_duplicateEvent_discardedWithAck() throws Exception {
        FundsReleaseFailedEvent event = FundsReleaseFailedEvent.of(
                correlationId, orderId, walletId.toString(),
                FailureReason.RELEASE_DB_ERROR, "Duplicado"
        );

        given(processedEventRepository.saveAndFlush(any()))
                .willThrow(new org.springframework.dao.DataIntegrityViolationException("dup key"));

        // Act
        consumer.onFundsReleaseFailed(event, channel, 2L);

        // Assert: nenhum save no outbox ou order, ACK enviado
        then(outboxRepository).shouldHaveNoInteractions();
        then(orderRepository).shouldHaveNoMoreInteractions();
        verify(channel).basicAck(2L, false);
    }

    // =========================================================================
    // Cenário 3 — Idempotência: processar o mesmo evento 2x → apenas 1 cancel
    // =========================================================================

    @Test
    @DisplayName("[RED→GREEN] Processar mesmo evento 2x deve resultar em apenas 1 cancel")
    void testReleaseFailed_sameEventTwice_onlyOneCancel() throws Exception {
        Order order = Order.create(
                orderId, correlationId,
                UUID.randomUUID().toString(),
                walletId,
                OrderType.BUY,
                new BigDecimal("500.00"),
                new BigDecimal("10.00000000")
        );
        order.markAsOpen();

        FundsReleaseFailedEvent event = FundsReleaseFailedEvent.of(
                correlationId, orderId, walletId.toString(),
                FailureReason.RELEASE_DB_ERROR, "Release failed"
        );

        // 1ª chamada: sucesso
        given(processedEventRepository.saveAndFlush(any()))
                .willReturn(new ProcessedEvent(event.eventId()));
        given(orderRepository.findByCorrelationId(correlationId))
                .willReturn(Optional.of(order));

        consumer.onFundsReleaseFailed(event, channel, 1L);

        // 2ª chamada: idempotência
        given(processedEventRepository.saveAndFlush(any()))
                .willThrow(new org.springframework.dao.DataIntegrityViolationException("dup key"));

        consumer.onFundsReleaseFailed(event, channel, 2L);

        // Assert: apenas 1 save de order, 1 outbox — o 2º foi descartado
        verify(orderRepository, times(1)).save(any());
        verify(outboxRepository, times(1)).save(any());
        verify(channel).basicAck(1L, false);
        verify(channel).basicAck(2L, false);
    }

    // =========================================================================
    // Cenário 4 — Ordem já CANCELLED → ignorar silenciosamente (WARN + ACK)
    // =========================================================================

    @Test
    @DisplayName("[RED→GREEN] Ordem já CANCELLED deve ser ignorada com WARN + ACK")
    void testReleaseFailed_orderAlreadyCancelled_ignoredWithAck() throws Exception {
        Order order = Order.create(
                orderId, correlationId,
                UUID.randomUUID().toString(),
                walletId,
                OrderType.BUY,
                new BigDecimal("500.00"),
                new BigDecimal("10.00000000")
        );
        order.cancel("PREVIOUS_FAILURE");

        FundsReleaseFailedEvent event = FundsReleaseFailedEvent.of(
                correlationId, orderId, walletId.toString(),
                FailureReason.RELEASE_DB_ERROR, "Already cancelled"
        );

        given(processedEventRepository.saveAndFlush(any()))
                .willReturn(new ProcessedEvent(event.eventId()));
        given(orderRepository.findByCorrelationId(correlationId))
                .willReturn(Optional.of(order));

        // Act
        consumer.onFundsReleaseFailed(event, channel, 3L);

        // Assert: nenhum save de order ou outbox, ACK enviado
        verify(orderRepository, never()).save(any());
        verify(outboxRepository, never()).save(any());
        verify(channel).basicAck(3L, false);
    }

    // =========================================================================
    // Cenário 5 — Ordem FILLED → ignorar silenciosamente (WARN + ACK)
    // =========================================================================

    @Test
    @DisplayName("[RED→GREEN] Ordem FILLED deve ser ignorada com WARN + ACK")
    void testReleaseFailed_orderAlreadyFilled_ignoredWithAck() throws Exception {
        Order order = Order.create(
                orderId, correlationId,
                UUID.randomUUID().toString(),
                walletId,
                OrderType.SELL,
                new BigDecimal("100.00"),
                new BigDecimal("5.00000000")
        );
        order.markAsOpen();
        order.applyMatch(new BigDecimal("5.00000000"));
        // order is now FILLED

        FundsReleaseFailedEvent event = FundsReleaseFailedEvent.of(
                correlationId, orderId, walletId.toString(),
                FailureReason.RELEASE_DB_ERROR, "Order already filled"
        );

        given(processedEventRepository.saveAndFlush(any()))
                .willReturn(new ProcessedEvent(event.eventId()));
        given(orderRepository.findByCorrelationId(correlationId))
                .willReturn(Optional.of(order));

        // Act
        consumer.onFundsReleaseFailed(event, channel, 4L);

        // Assert: nenhum save de order ou outbox, ACK enviado
        verify(orderRepository, never()).save(any());
        verify(outboxRepository, never()).save(any());
        verify(channel).basicAck(4L, false);
    }

    // =========================================================================
    // Cenário 6 — Ordem não encontrada → WARN + ACK (descarta)
    // =========================================================================

    @Test
    @DisplayName("[RED→GREEN] Ordem não encontrada deve descartar evento com WARN + ACK")
    void testReleaseFailed_orderNotFound_discardedWithAck() throws Exception {
        FundsReleaseFailedEvent event = FundsReleaseFailedEvent.of(
                correlationId, orderId, walletId.toString(),
                FailureReason.RELEASE_DB_ERROR, "No order"
        );

        given(processedEventRepository.saveAndFlush(any()))
                .willReturn(new ProcessedEvent(event.eventId()));
        given(orderRepository.findByCorrelationId(correlationId))
                .willReturn(Optional.empty());

        // Act
        consumer.onFundsReleaseFailed(event, channel, 5L);

        // Assert: nenhum save de order ou outbox, ACK enviado
        verify(orderRepository, never()).save(any());
        verify(outboxRepository, never()).save(any());
        verify(channel).basicAck(5L, false);
    }
}
