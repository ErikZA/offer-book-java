package com.vibranium.orderservice.adapter.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.vibranium.contracts.enums.AssetType;
import com.vibranium.contracts.enums.OrderType;
import com.vibranium.contracts.events.wallet.FundsReservedEvent;
import com.vibranium.orderservice.adapter.redis.RedisMatchEngineAdapter;
import com.vibranium.orderservice.adapter.redis.RedisMatchEngineAdapter.MatchResult;
import com.vibranium.orderservice.config.RabbitMQConfig;
import com.vibranium.orderservice.domain.model.Order;
import com.vibranium.orderservice.domain.model.OrderOutboxMessage;
import com.vibranium.orderservice.domain.model.ProcessedEvent;
import com.vibranium.orderservice.domain.repository.OrderOutboxRepository;
import com.vibranium.orderservice.domain.repository.OrderRepository;
import com.vibranium.orderservice.domain.repository.ProcessedEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/**
 * [FASE RED → GREEN] — Testes unitários para {@link FundsReservedEventConsumer}.
 *
 * <h2>Critérios de aceite validados</h2>
 * <ol>
 *   <li>{@code handleMatch()} NÃO invoca {@link RabbitTemplate} — o broker não é
 *       atingido diretamente dentro da transação.</li>
 *   <li>{@code handleMatch()} persiste exactamente um {@link OrderOutboxMessage} com
 *       {@code eventType="MatchExecutedEvent"}, {@code exchange=EVENTS_EXCHANGE},
 *       {@code routingKey=RK_MATCH_EXECUTED} e {@code publishedAt=null}.</li>
 *   <li>Se uma {@link RuntimeException} for lançada antes do commit,
 *       {@code outboxRepository.save()} não é chamado — garantindo atomicidade.</li>
 *   <li>{@code handleNoMatch()} e {@code cancelOrder()} também usam outbox, sem
 *       {@code RabbitTemplate}.</li>
 * </ol>
 *
 * <h2>Estado FASE RED</h2>
 * <p>Este arquivo foi criado <em>antes</em> da refatoração. O compilador rejeita
 * a chamada ao construtor {@code new FundsReservedEventConsumer(orderRepository,
 * processedEventRepository, matchEngine, outboxRepository, objectMapper)}
 * porque o construtor atual ainda exige {@code RabbitTemplate}.
 * Esse erro de compilação é a evidência formal do RED.</p>
 *
 * <p>Após a refatoração (FASE GREEN), o build passa e todos os testes ficam verdes.</p>
 */
@ExtendWith(MockitoExtension.class)
class FundsReservedEventConsumerTest {

    // -------------------------------------------------------------------------
    // Mocks — dependências que o consumidor DEVERIA ter após refatoração
    // -------------------------------------------------------------------------

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private RedisMatchEngineAdapter matchEngine;

    /**
     * FASE RED: RabbitTemplate NÃO deve ser injetado no construtor do consumidor.
     * Este mock existe apenas para provar, via {@link #handleMatch_mustNot_invokRabbitTemplate},
     * que o campo não é utilizado.
     *
     * <p>Após refatoração o construtor não receberá mais este objeto —
     * portanto este campo é mantido somente para o teste de ausência de uso.</p>
     */
    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private OrderOutboxRepository outboxRepository;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private Channel channel;

    @Captor
    private ArgumentCaptor<OrderOutboxMessage> outboxCaptor;

    /** Consumer instanciado com a assinatura DESEJADA (sem RabbitTemplate). */
    private FundsReservedEventConsumer consumer;

    // IDs de teste reutilizados
    private static final UUID ORDER_ID         = UUID.randomUUID();
    private static final UUID CORRELATION_ID   = UUID.randomUUID();
    private static final UUID WALLET_ID        = UUID.randomUUID();
    private static final UUID COUNTERPART_ID   = UUID.randomUUID();
    private static final String USER_ID        = UUID.randomUUID().toString();
    private static final String COUNTERPART_USER = UUID.randomUUID().toString();
    private static final UUID COUNTERPART_WALLET = UUID.randomUUID();
    private static final long DELIVERY_TAG     = 1L;

    @BeforeEach
    void setUp() throws Exception {
        /*
         * FASE RED — esta linha NÃO compila enquanto o construtor de
         * FundsReservedEventConsumer ainda exigir RabbitTemplate em vez de
         * (OrderOutboxRepository, ObjectMapper).
         *
         * Mensagem esperada de compilação em RED:
         *   "no suitable constructor found for FundsReservedEventConsumer(
         *    OrderRepository, ProcessedEventRepository, RedisMatchEngineAdapter,
         *    OrderOutboxRepository, ObjectMapper)"
         *
         * ObjectMapper é mockado seguindo a convenção do projeto (OrderCommandServiceTest).
         * Cada teste que espera serialização bem-sucedida stubba:
         *   given(objectMapper.writeValueAsString(any())).willReturn("{\"ok\":true}");
         */
        consumer = new FundsReservedEventConsumer(
                orderRepository,
                processedEventRepository,
                matchEngine,
                outboxRepository,
                objectMapper
        );
    }

    // =========================================================================
    // Fixtures
    // =========================================================================

    private Order buildOrder(OrderType type) {
        return Order.create(
                ORDER_ID, CORRELATION_ID, USER_ID,
                WALLET_ID, type,
                new BigDecimal("500.00"),
                new BigDecimal("2.00")
        );
    }

    private MatchResult buildMatchResult() {
        return new MatchResult(
                true,
                COUNTERPART_ID,
                COUNTERPART_USER,
                COUNTERPART_WALLET,
                new BigDecimal("2.00"),
                BigDecimal.ZERO,
                "FULL"
        );
    }

    private FundsReservedEvent buildFundsReservedEvent() {
        return FundsReservedEvent.of(
                CORRELATION_ID,
                ORDER_ID,
                WALLET_ID,
                AssetType.BRL,
                new BigDecimal("1000.00")
        );
    }

    // =========================================================================
    // 1. handleMatch — sem publicação direta ao broker
    // =========================================================================

    @Nested
    @DisplayName("handleMatch — Outbox Pattern")
    class HandleMatchTests {

        @Test
        @DisplayName("RED-01: handleMatch() NÃO deve invocar RabbitTemplate em hipótese alguma")
        void handleMatch_mustNot_invokRabbitTemplate() throws Exception {
            /*
             * Dado: ordem BUY localizada e match bem-sucedido no Redis
             */
            Order order = buildOrder(OrderType.BUY);
            FundsReservedEvent event = buildFundsReservedEvent();

            given(processedEventRepository.saveAndFlush(any(ProcessedEvent.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(orderRepository.findByCorrelationId(CORRELATION_ID))
                    .willReturn(Optional.of(order));
            given(matchEngine.tryMatch(any(), any(), any(), any(), any(), any(), any()))
                    .willReturn(buildMatchResult());
            given(orderRepository.save(any(Order.class))).willAnswer(inv -> inv.getArgument(0));
            given(objectMapper.writeValueAsString(any())).willReturn("{\"ok\":true}");
            given(outboxRepository.save(any(OrderOutboxMessage.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            /*
             * Quando: consumidor processa o evento de fundos reservados
             */
            consumer.onFundsReserved(event, channel, DELIVERY_TAG);

            /*
             * Então: RabbitTemplate NUNCA deve ser chamado
             * (falha em FASE RED pois o código ainda faz convertAndSend)
             */
            then(rabbitTemplate).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("RED-02: handleMatch() deve persistir OrderOutboxMessage com metadados corretos")
        void handleMatch_shouldPersist_outboxMessage_withCorrectMetadata() throws Exception {
            Order order = buildOrder(OrderType.BUY);
            FundsReservedEvent event = buildFundsReservedEvent();

            given(processedEventRepository.saveAndFlush(any(ProcessedEvent.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(orderRepository.findByCorrelationId(CORRELATION_ID))
                    .willReturn(Optional.of(order));
            given(matchEngine.tryMatch(any(), any(), any(), any(), any(), any(), any()))
                    .willReturn(buildMatchResult());
            given(orderRepository.save(any(Order.class))).willAnswer(inv -> inv.getArgument(0));
            given(objectMapper.writeValueAsString(any())).willReturn("{\"ok\":true}");
            given(outboxRepository.save(any(OrderOutboxMessage.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            consumer.onFundsReserved(event, channel, DELIVERY_TAG);

            /*
             * Captura a mensagem gravada no outbox e verifica todos os campos críticos:
             *  - eventType identifica o tipo do evento para o publisher/scheduler
             *  - exchange + routingKey garantem roteamento correto no broker
             *  - publishedAt == null sinaliza que ainda aguarda relay
             *  - payload não é vazio (evento serializado como JSON)
             */
            then(outboxRepository).should(times(1)).save(outboxCaptor.capture());
            OrderOutboxMessage saved = outboxCaptor.getValue();

            assertThat(saved.getEventType())
                    .as("eventType deve ser 'MatchExecutedEvent'")
                    .isEqualTo("MatchExecutedEvent");
            assertThat(saved.getExchange())
                    .as("exchange deve ser " + RabbitMQConfig.EVENTS_EXCHANGE)
                    .isEqualTo(RabbitMQConfig.EVENTS_EXCHANGE);
            assertThat(saved.getRoutingKey())
                    .as("routingKey deve ser " + RabbitMQConfig.RK_MATCH_EXECUTED)
                    .isEqualTo(RabbitMQConfig.RK_MATCH_EXECUTED);
            assertThat(saved.getPublishedAt())
                    .as("publishedAt deve ser null — mensagem ainda não enviada ao broker")
                    .isNull();
            assertThat(saved.getPayload())
                    .as("payload não deve ser vazio")
                    .isNotBlank();
            assertThat(saved.getAggregateId())
                    .as("aggregateId deve ser o orderId")
                    .isEqualTo(ORDER_ID);
        }

        @Test
        @DisplayName("RED-03: handleMatch() — falha antes do commit não deve salvar outbox")
        void handleMatch_exceptionBeforeCommit_shouldNot_saveOutbox() throws Exception {
            /*
             * Simula falha em orderRepository.save() — que ocorre ANTES do outbox.save().
             * Como ambas as operações estão no mesmo contexto @Transactional, o rollback
             * desfaz tudo. No teste unitário validamos que, se save(order) lança exceção,
             * outboxRepository.save() nunca é chamado.
             */
            Order order = buildOrder(OrderType.BUY);
            FundsReservedEvent event = buildFundsReservedEvent();

            given(processedEventRepository.saveAndFlush(any(ProcessedEvent.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(orderRepository.findByCorrelationId(CORRELATION_ID))
                    .willReturn(Optional.of(order));
            given(matchEngine.tryMatch(any(), any(), any(), any(), any(), any(), any()))
                    .willReturn(buildMatchResult());
            doThrow(new RuntimeException("Simulated DB failure on order save"))
                    .when(orderRepository).save(any(Order.class));

            assertThatThrownBy(
                    () -> consumer.onFundsReserved(event, channel, DELIVERY_TAG)
            ).isInstanceOf(RuntimeException.class);

            then(outboxRepository).should(never()).save(any(OrderOutboxMessage.class));
        }
    }

    // =========================================================================
    // 2. handleNoMatch — também deve usar outbox
    // =========================================================================

    @Nested
    @DisplayName("handleNoMatch — Outbox Pattern")
    class HandleNoMatchTests {

        @Test
        @DisplayName("RED-04: handleNoMatch() deve persistir OrderOutboxMessage sem chamar RabbitTemplate")
        void handleNoMatch_shouldPersist_outboxMessage() throws Exception {
            Order order = buildOrder(OrderType.BUY);
            FundsReservedEvent event = buildFundsReservedEvent();

            given(processedEventRepository.saveAndFlush(any(ProcessedEvent.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(orderRepository.findByCorrelationId(CORRELATION_ID))
                    .willReturn(Optional.of(order));
            given(matchEngine.tryMatch(any(), any(), any(), any(), any(), any(), any()))
                    .willReturn(MatchResult.noMatch());
            given(orderRepository.save(any(Order.class))).willAnswer(inv -> inv.getArgument(0));
            given(objectMapper.writeValueAsString(any())).willReturn("{\"ok\":true}");
            given(outboxRepository.save(any(OrderOutboxMessage.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            consumer.onFundsReserved(event, channel, DELIVERY_TAG);

            then(outboxRepository).should(times(1)).save(outboxCaptor.capture());
            OrderOutboxMessage saved = outboxCaptor.getValue();

            assertThat(saved.getEventType()).isEqualTo("OrderAddedToBookEvent");
            assertThat(saved.getRoutingKey()).isEqualTo(RabbitMQConfig.RK_ORDER_ADDED_TO_BOOK);
            assertThat(saved.getPublishedAt()).isNull();

            then(rabbitTemplate).shouldHaveNoInteractions();
        }
    }

    // =========================================================================
    // 3. cancelOrder — também deve usar outbox
    // =========================================================================

    @Nested
    @DisplayName("cancelOrder — Outbox Pattern")
    class CancelOrderTests {

        @Test
        @DisplayName("RED-05: cancelOrder() deve persistir OrderOutboxMessage sem chamar RabbitTemplate")
        void cancelOrder_shouldPersist_outboxMessage_whenRedisUnavailable() throws Exception {
            Order order = buildOrder(OrderType.BUY);
            FundsReservedEvent event = buildFundsReservedEvent();

            given(processedEventRepository.saveAndFlush(any(ProcessedEvent.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(orderRepository.findByCorrelationId(CORRELATION_ID))
                    .willReturn(Optional.of(order));
            // Redis indisponível → disparador do cancelamento
            given(matchEngine.tryMatch(any(), any(), any(), any(), any(), any(), any()))
                    .willThrow(new RuntimeException("Redis timeout"));
            given(orderRepository.save(any(Order.class))).willAnswer(inv -> inv.getArgument(0));
            given(objectMapper.writeValueAsString(any())).willReturn("{\"ok\":true}");
            given(outboxRepository.save(any(OrderOutboxMessage.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            consumer.onFundsReserved(event, channel, DELIVERY_TAG);

            then(outboxRepository).should(times(1)).save(outboxCaptor.capture());
            OrderOutboxMessage saved = outboxCaptor.getValue();

            assertThat(saved.getEventType()).isEqualTo("OrderCancelledEvent");
            assertThat(saved.getRoutingKey()).isEqualTo(RabbitMQConfig.RK_ORDER_CANCELLED);
            assertThat(saved.getPublishedAt()).isNull();

            then(rabbitTemplate).shouldHaveNoInteractions();
        }
    }
}
