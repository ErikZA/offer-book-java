package com.vibranium.orderservice.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.vibranium.contracts.enums.AssetType;
import com.vibranium.contracts.enums.FailureReason;
import com.vibranium.contracts.enums.OrderType;
import com.vibranium.contracts.events.order.MatchExecutedEvent;
import com.vibranium.contracts.events.wallet.FundsSettlementFailedEvent;
import com.vibranium.orderservice.config.RabbitMQConfig;
import com.vibranium.orderservice.domain.model.Order;
import com.vibranium.orderservice.domain.model.OrderOutboxMessage;
import com.vibranium.orderservice.domain.model.ProcessedEvent;
import com.vibranium.orderservice.domain.repository.OrderOutboxRepository;
import com.vibranium.orderservice.domain.repository.OrderRepository;
import com.vibranium.orderservice.domain.repository.ProcessedEventRepository;
import com.vibranium.orderservice.application.service.EventStoreService;
import com.vibranium.utils.jackson.VibraniumJacksonConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * [FASE RED → GREEN] — Testes unitários do {@link FundsSettlementFailedEventConsumer}.
 *
 * <h2>Critérios de aceite (AT-1.1.4)</h2>
 * <ol>
 *   <li>{@code FundsSettlementFailedEvent} deve gerar <strong>exatamente 2</strong>
 *       {@code ReleaseFundsCommand} no outbox: um para o comprador (BRL) e um para
 *       o vendedor (VIBRANIUM).</li>
 *   <li>O {@code ReleaseFundsCommand} do comprador usa {@code AssetType.BRL} com
 *       valor {@code matchPrice × matchAmount}.</li>
 *   <li>O {@code ReleaseFundsCommand} do vendedor usa {@code AssetType.VIBRANIUM}
 *       com valor {@code matchAmount}.</li>
 *   <li>Evento duplicado (idempotência) descarta com ACK sem gravar no outbox.</li>
 *   <li>Ausência de {@code MatchExecutedEvent} no outbox resulta em NACK para DLQ.</li>
 * </ol>
 *
 * <h2>Histórico TDD</h2>
 * <p><strong>FASE RED (AT-1.1.4):</strong> {@link FundsSettlementFailedEventConsumer} ainda
 * não existe — o teste não compila. Evidência formal do ciclo RED.</p>
 * <p><strong>FASE GREEN (AT-1.1.4):</strong> Implementação criada — todos os testes verdes.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AT-1.1.4 — FundsSettlementFailedEventConsumer: compensação com 2x ReleaseFundsCommand")
class FundsSettlementFailedHandlerTest {

    // -------------------------------------------------------------------------
    // Mocks e dependências
    // -------------------------------------------------------------------------

    @Mock private OrderRepository          orderRepository;
    @Mock private OrderOutboxRepository    outboxRepository;
    @Mock private ProcessedEventRepository processedEventRepository;
    @Mock private EventStoreService        eventStoreService;
    @Mock private Channel                  channel;

    /**
     * ObjectMapper real configurado via {@link VibraniumJacksonConfig} — necessário para
     * que a desserialização do {@code MatchExecutedEvent} do payload do outbox funcione
     * corretamente, em especial os campos {@code Instant} com {@code @JsonFormat(NUMBER_INT)}.
     */
    private final ObjectMapper objectMapper = VibraniumJacksonConfig.configure(new ObjectMapper());

    @Captor
    private ArgumentCaptor<OrderOutboxMessage> outboxCaptor;

    private FundsSettlementFailedEventConsumer consumer;

    // -------------------------------------------------------------------------
    // Dados de teste reutilizados
    // -------------------------------------------------------------------------

    private UUID correlationId;
    private UUID matchId;
    private UUID buyOrderId;
    private UUID sellOrderId;
    private UUID buyerWalletId;
    private UUID sellerWalletId;
    private BigDecimal matchPrice;
    private BigDecimal matchAmount;
    private MatchExecutedEvent matchEvent;

    @BeforeEach
    void setUp() {
        consumer = new FundsSettlementFailedEventConsumer(
                orderRepository, outboxRepository, processedEventRepository, objectMapper, eventStoreService
        );

        correlationId    = UUID.randomUUID();
        matchId          = UUID.randomUUID();
        buyOrderId       = UUID.randomUUID();
        sellOrderId      = UUID.randomUUID();
        buyerWalletId    = UUID.randomUUID();
        sellerWalletId   = UUID.randomUUID();
        matchPrice       = new BigDecimal("500.00");
        matchAmount      = new BigDecimal("2.00000000");

        // MatchExecutedEvent com todos os lados do trade
        matchEvent = MatchExecutedEvent.of(
                correlationId,
                buyOrderId,
                sellOrderId,
                UUID.randomUUID(),   // buyerUserId
                UUID.randomUUID(),   // sellerUserId
                buyerWalletId,
                sellerWalletId,
                matchPrice,
                matchAmount
        );
    }

    // =========================================================================
    // AT-1.1.4 — Compensação: 2x ReleaseFundsCommand para buyer + seller
    // =========================================================================

    @Test
    @DisplayName("[AT-1.1.4 RED→GREEN] FundsSettlementFailedEvent deve emitir 2x ReleaseFundsCommand (buyer + seller)")
    void testSettlementFailed_emitsReleaseFundsForBothWallets() throws Exception {
        // Arrange: monta a ordem BUY que disparou o match
        Order buyOrder = Order.create(
                buyOrderId, correlationId,
                UUID.randomUUID().toString(), // userId
                buyerWalletId,
                OrderType.BUY,
                matchPrice,
                matchAmount
        );

        FundsSettlementFailedEvent failedEvent = FundsSettlementFailedEvent.of(
                correlationId, matchId, FailureReason.INTERNAL_ERROR, "Teste de compensação"
        );

        // Serializa o MatchExecutedEvent para simular o que ficou gravado no outbox
        String matchEventJson = objectMapper.writeValueAsString(matchEvent);
        OrderOutboxMessage matchOutboxMessage = new OrderOutboxMessage(
                buyOrderId, "Order", "MatchExecutedEvent",
                "vibranium.events", RabbitMQConfig.RK_MATCH_EXECUTED,
                matchEventJson
        );

        // Configura mocks: sem duplicata, ordem encontrada, MatchExecutedEvent no outbox
        given(processedEventRepository.saveAndFlush(any()))
                .willReturn(new ProcessedEvent(failedEvent.eventId()));
        given(orderRepository.findByCorrelationId(correlationId))
                .willReturn(Optional.of(buyOrder));
        given(outboxRepository.findFirstByAggregateIdAndEventType(buyOrderId, "MatchExecutedEvent"))
                .willReturn(Optional.of(matchOutboxMessage));

        // Act
        consumer.onFundsSettlementFailed(failedEvent, channel, 1L);

        // Assert: 2 saves no outbox (um para buyer, um para seller)
        verify(outboxRepository, times(2)).save(outboxCaptor.capture());
        List<OrderOutboxMessage> saved = outboxCaptor.getAllValues();

        assertThat(saved)
                .as("[AT-1.1.4] Devem ser gravados exatamente 2 ReleaseFundsCommand no outbox")
                .hasSize(2);
        assertThat(saved.stream().map(OrderOutboxMessage::getEventType))
                .as("[AT-1.1.4] Ambos devem ser do tipo ReleaseFundsCommand")
                .containsOnly("ReleaseFundsCommand");

        // Verifica que os payloads contêm as wallets corretas
        String allPayloads = saved.get(0).getPayload() + saved.get(1).getPayload();
        assertThat(allPayloads)
                .as("[AT-1.1.4] Payloads devem referenciar buyerWalletId")
                .contains(buyerWalletId.toString());
        assertThat(allPayloads)
                .as("[AT-1.1.4] Payloads devem referenciar sellerWalletId")
                .contains(sellerWalletId.toString());
        assertThat(allPayloads)
                .as("[AT-1.1.4] Um payload deve ter AssetType BRL (buyer)")
                .contains(AssetType.BRL.name());
        assertThat(allPayloads)
                .as("[AT-1.1.4] Um payload deve ter AssetType VIBRANIUM (seller)")
                .contains(AssetType.VIBRANIUM.name());

        // Verifica ACK enviado após commit
        verify(channel).basicAck(1L, false);
    }

    @Test
    @DisplayName("[AT-1.1.4] Evento duplicado deve ser descartado com ACK (idempotência)")
    void testSettlementFailed_duplicateEvent_discardedWithAck() throws Exception {
        // Arrange: saveAndFlush lança DataIntegrityViolationException → duplicata
        FundsSettlementFailedEvent failedEvent = FundsSettlementFailedEvent.of(
                correlationId, matchId, FailureReason.INTERNAL_ERROR, "Duplicado"
        );
        given(processedEventRepository.saveAndFlush(any()))
                .willThrow(new org.springframework.dao.DataIntegrityViolationException("dup key"));

        // Act
        consumer.onFundsSettlementFailed(failedEvent, channel, 2L);

        // Assert: nenhum save no outbox, ACK enviado
        then(outboxRepository).shouldHaveNoMoreInteractions();
        verify(channel).basicAck(2L, false);
    }

    @Test
    @DisplayName("[AT-1.1.4] MatchExecutedEvent não encontrado no outbox deve gerar NACK para DLQ")
    void testSettlementFailed_matchEventNotFound_nackToDlq() throws Exception {
        // Arrange
        Order buyOrder = Order.create(
                buyOrderId, correlationId, UUID.randomUUID().toString(),
                buyerWalletId, OrderType.BUY, matchPrice, matchAmount
        );
        FundsSettlementFailedEvent failedEvent = FundsSettlementFailedEvent.of(
                correlationId, matchId, FailureReason.INTERNAL_ERROR, "Match não encontrado"
        );

        given(processedEventRepository.saveAndFlush(any()))
                .willReturn(new ProcessedEvent(failedEvent.eventId()));
        given(orderRepository.findByCorrelationId(correlationId))
                .willReturn(Optional.of(buyOrder));
        // MatchExecutedEvent não está no outbox
        given(outboxRepository.findFirstByAggregateIdAndEventType(buyOrderId, "MatchExecutedEvent"))
                .willReturn(Optional.empty());

        // Act
        consumer.onFundsSettlementFailed(failedEvent, channel, 3L);

        // Assert: nenhum save no outbox, NACK enviado (sem re-enqueue → vai para DLQ)
        verify(outboxRepository, times(0)).save(any());
        verify(channel).basicNack(3L, false, false);
    }
}
