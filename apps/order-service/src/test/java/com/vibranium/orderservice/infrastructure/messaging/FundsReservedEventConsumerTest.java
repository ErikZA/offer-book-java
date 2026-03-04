package com.vibranium.orderservice.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.vibranium.contracts.enums.AssetType;
import com.vibranium.contracts.enums.OrderType;
import com.vibranium.contracts.events.wallet.FundsReservedEvent;
import com.vibranium.orderservice.infrastructure.redis.RedisMatchEngineAdapter;
import com.vibranium.orderservice.infrastructure.redis.RedisMatchEngineAdapter.MatchResult;
import java.util.List;
import com.vibranium.orderservice.config.RabbitMQConfig;
import com.vibranium.orderservice.domain.model.Order;
import com.vibranium.orderservice.domain.model.OrderOutboxMessage;
import com.vibranium.orderservice.domain.model.ProcessedEvent;
import com.vibranium.orderservice.domain.repository.OrderOutboxRepository;
import com.vibranium.orderservice.domain.repository.OrderRepository;
import com.vibranium.orderservice.domain.repository.ProcessedEventRepository;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/**
 * [VERDE] — Testes unitários para {@link FundsReservedEventConsumer}.
 *
 * <h2>Critérios de aceite validados</h2>
 * <ol>
 *   <li>{@code handleMatch()} NÃO invoca {@link RabbitTemplate} — o broker não é
 *       atingido diretamente dentro da transação (Outbox Pattern).</li>
 *   <li>{@code handleMatch()} com match total: persiste {@code MatchExecutedEvent} +
 *       {@code OrderFilledEvent} (2 saves) na mesma transação — AT-15.1.</li>
 *   <li>{@code handleMatch()} com match parcial: persiste {@code MatchExecutedEvent} +
 *       {@code OrderPartiallyFilledEvent} (2 saves) na mesma transação — AT-15.1.</li>
 *   <li>Se uma {@link RuntimeException} for lançada antes do commit,
 *       {@code outboxRepository.save()} não é chamado — garantindo atomicidade.</li>
 *   <li>{@code handleNoMatch()} grava apenas {@code OrderAddedToBookEvent} (1 save);
 *       nenhum fill event é publicado.</li>
 *   <li>{@code cancelOrder()} grava {@code OrderCancelledEvent} sem invocar
 *       {@link RabbitTemplate}.</li>
 * </ol>
 *
 * <h2>Histórico TDD</h2>
 * <ul>
 *   <li><strong>RED (Outbox refactor — AT-02.1):</strong> construtor sem {@code RabbitTemplate}
 *       não compilava; evidência formal do ciclo RED.</li>
 *   <li><strong>GREEN (AT-02.1):</strong> refatoração para Outbox Pattern — todos os eventos
 *       gravados no outbox na mesma transação JPA.</li>
 *   <li><strong>RED → GREEN (AT-15.1):</strong> {@code handleMatch()} não publicava fill events;
 *       testes AT15-01/AT15-02 falhavam em {@code times(2)}.
 *       Implementação corrigida — ambos os cenários verdes.</li>
 * </ul>
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

    // AT-14.1: Tracer mock necessário após adição da dependência no construtor.
    // NoOpTracer: tracer.currentSpan() retorna null, o enriquecimento é ignorado nos unit tests.
    @Mock
    private Tracer tracer;

    // AT-2.1.1: TransactionTemplate mock — necessário após extração do fluxo Saga.
    // O stub padrão em setUp executa o callback diretamente, simulando a TX.
    @Mock
    private TransactionTemplate txTemplate;

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
        // AT-2.1.1: stub LENIENT — txTemplate.execute() executa o callback diretamente,
        // simulando uma TX real sem infraestrutura de banco.
        // Lenient: evita UnnecessaryStubbingException em testes RED (código ainda não usa
        // txTemplate) e em testes cujo caminho de execução não aciona o template.
        lenient().when(txTemplate.execute(any()))
                .thenAnswer(inv -> ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(null));

        consumer = new FundsReservedEventConsumer(
                orderRepository,
                processedEventRepository,
                matchEngine,
                outboxRepository,
                objectMapper,
                tracer,    // AT-14.1: tracer injetado para enriquecimento de spans
                txTemplate // AT-2.1.1: transactionTemplate para separação de fases Saga
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
                    .willReturn(List.of(buildMatchResult()));
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
        @DisplayName("RED-02: handleMatch() deve persistir MatchExecutedEvent + OrderFilledEvent (match total)")
        void handleMatch_shouldPersist_outboxMessage_withCorrectMetadata() throws Exception {
            // buildOrder() cria ordem com amount=2.00 e buildMatchResult() com matchedQty=2.00 → FILLED
            Order order = buildOrder(OrderType.BUY);
            FundsReservedEvent event = buildFundsReservedEvent();

            given(processedEventRepository.saveAndFlush(any(ProcessedEvent.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(orderRepository.findByCorrelationId(CORRELATION_ID))
                    .willReturn(Optional.of(order));
            given(matchEngine.tryMatch(any(), any(), any(), any(), any(), any(), any()))
                    .willReturn(List.of(buildMatchResult()));
            given(orderRepository.save(any(Order.class))).willAnswer(inv -> inv.getArgument(0));
            given(objectMapper.writeValueAsString(any())).willReturn("{\"ok\":true}");
            given(outboxRepository.save(any(OrderOutboxMessage.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            consumer.onFundsReserved(event, channel, DELIVERY_TAG);

            /*
             * [FASE GREEN — AT-15.1]
             * Após a implementação, handleMatch() grava 2 mensagens no outbox:
             *  1. MatchExecutedEvent  — informacional/infra para projeção MongoDB
             *  2. OrderFilledEvent    — Domain Event explícito para sistemas downstream
             *
             * Em FASE RED: apenas 1 save → teste falha em times(2).
             */
            then(outboxRepository).should(times(2)).save(outboxCaptor.capture());
            List<OrderOutboxMessage> saved = outboxCaptor.getAllValues();

            assertThat(saved)
                    .extracting(OrderOutboxMessage::getEventType)
                    .as("Outbox deve conter MatchExecutedEvent e OrderFilledEvent")
                    .containsExactlyInAnyOrder("MatchExecutedEvent", "OrderFilledEvent");

            OrderOutboxMessage matchMsg = saved.stream()
                    .filter(m -> "MatchExecutedEvent".equals(m.getEventType()))
                    .findFirst().orElseThrow();
            assertThat(matchMsg.getRoutingKey()).isEqualTo(RabbitMQConfig.RK_MATCH_EXECUTED);
            assertThat(matchMsg.getExchange()).isEqualTo(RabbitMQConfig.EVENTS_EXCHANGE);
            assertThat(matchMsg.getPublishedAt()).isNull();
            assertThat(matchMsg.getAggregateId()).isEqualTo(ORDER_ID);

            OrderOutboxMessage fillMsg = saved.stream()
                    .filter(m -> "OrderFilledEvent".equals(m.getEventType()))
                    .findFirst().orElseThrow();
            assertThat(fillMsg.getRoutingKey()).isEqualTo(RabbitMQConfig.RK_ORDER_FILLED);
            assertThat(fillMsg.getExchange()).isEqualTo(RabbitMQConfig.EVENTS_EXCHANGE);
            assertThat(fillMsg.getPublishedAt()).isNull();
            assertThat(fillMsg.getPayload()).isNotBlank();
        }

        @Test
        @DisplayName("RED-03: handleMatch() — falha antes do commit não deve salvar outbox")
        void handleMatch_exceptionBeforeCommit_shouldNot_saveOutbox() throws Exception {
            /*
             * Simula falha em orderRepository.save() na Fase 1 (markAsOpen + save).
             * Como save() lança antes de tryMatch() ser chamado, a Fase 2 e a Fase 3
             * nunca são executadas. O outboxRepository.save() nunca deve ser chamado.
             *
             * AT-2.1.1: a exceção propaga da Fase 1 (txTemplate.execute()) e não é
             * capturada por nenhum catch interno do consumer — portanto o método lança.
             */
            Order order = buildOrder(OrderType.BUY);
            FundsReservedEvent event = buildFundsReservedEvent();

            given(processedEventRepository.saveAndFlush(any(ProcessedEvent.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(orderRepository.findByCorrelationId(CORRELATION_ID))
                    .willReturn(Optional.of(order));
            // matchEngine.tryMatch NÃO é stubado: Fase 1 lança antes de atingir Fase 2
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
                    .willReturn(List.of());
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
    // AT-15.1 — OrderFilledEvent e OrderPartiallyFilledEvent no handleMatch()
    // =========================================================================

    @Nested
    @DisplayName("AT-15.1 — Domain Events de preenchimento no Outbox")
    class FillEventTests {

        /**
         * Cenário 1 (AT-15.1) — Match parcial.
         *
         * <p>Ordem criada com amount=100. Match executado de 40 → status PARTIAL.
         * Outbox deve conter {@code OrderPartiallyFilledEvent} além do {@code MatchExecutedEvent}.</p>
         *
         * <p><strong>FASE RED:</strong> falha em {@code times(2)} porque handleMatch()
         * ainda não publica o evento de preenchimento parcial.</p>
         */
        @Test
        @DisplayName("AT15-01: match parcial → outbox persiste OrderPartiallyFilledEvent")
        void handleMatch_partialMatch_shouldPersist_partiallyFilledEvent() throws Exception {
            Order order = Order.create(
                    ORDER_ID, CORRELATION_ID, USER_ID, WALLET_ID,
                    OrderType.BUY,
                    new BigDecimal("500.00"),
                    new BigDecimal("100.00")          // amount total = 100
            );
            FundsReservedEvent event = buildFundsReservedEvent();

            // Match parcial: 40 de 100 → remainingAmount = 60 → PARTIAL
            MatchResult partialResult = new MatchResult(
                    true,
                    COUNTERPART_ID,
                    COUNTERPART_USER,
                    COUNTERPART_WALLET,
                    new BigDecimal("40.00"),           // matchedQty
                    new BigDecimal("60.00"),            // remainingCounterpartQty
                    "PARTIAL_ASK"
            );

            given(processedEventRepository.saveAndFlush(any(ProcessedEvent.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(orderRepository.findByCorrelationId(CORRELATION_ID))
                    .willReturn(Optional.of(order));
            given(matchEngine.tryMatch(any(), any(), any(), any(), any(), any(), any()))
                    .willReturn(List.of(partialResult));
            given(orderRepository.save(any(Order.class))).willAnswer(inv -> inv.getArgument(0));
            given(objectMapper.writeValueAsString(any())).willReturn("{\"ok\":true}");
            given(outboxRepository.save(any(OrderOutboxMessage.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            consumer.onFundsReserved(event, channel, DELIVERY_TAG);

            // Espera 2 saves: MatchExecutedEvent + OrderPartiallyFilledEvent
            then(outboxRepository).should(times(2)).save(outboxCaptor.capture());
            List<OrderOutboxMessage> allMessages = outboxCaptor.getAllValues();

            assertThat(allMessages)
                    .extracting(OrderOutboxMessage::getEventType)
                    .as("Outbox deve conter MatchExecutedEvent e OrderPartiallyFilledEvent")
                    .containsExactlyInAnyOrder("MatchExecutedEvent", "OrderPartiallyFilledEvent");

            OrderOutboxMessage partialMsg = allMessages.stream()
                    .filter(m -> "OrderPartiallyFilledEvent".equals(m.getEventType()))
                    .findFirst().orElseThrow();

            assertThat(partialMsg.getRoutingKey())
                    .as("routing key deve ser " + RabbitMQConfig.RK_ORDER_PARTIALLY_FILLED)
                    .isEqualTo(RabbitMQConfig.RK_ORDER_PARTIALLY_FILLED);
            assertThat(partialMsg.getExchange()).isEqualTo(RabbitMQConfig.EVENTS_EXCHANGE);
            assertThat(partialMsg.getPublishedAt())
                    .as("publishedAt deve ser null — aguarda relay assíncrono")
                    .isNull();
            assertThat(partialMsg.getAggregateId()).isEqualTo(ORDER_ID);
            assertThat(partialMsg.getPayload()).isNotBlank();
        }

        /**
         * Cenário 2 (AT-15.1) — Match total.
         *
         * <p>Ordem criada com amount=100. Match executado de 100 → status FILLED.
         * Outbox deve conter {@code OrderFilledEvent} além do {@code MatchExecutedEvent}.</p>
         *
         * <p><strong>FASE RED:</strong> falha em {@code times(2)} porque handleMatch()
         * ainda não publica o evento de preenchimento total.</p>
         */
        @Test
        @DisplayName("AT15-02: match total → outbox persiste OrderFilledEvent")
        void handleMatch_fullMatch_shouldPersist_filledEvent() throws Exception {
            Order order = Order.create(
                    ORDER_ID, CORRELATION_ID, USER_ID, WALLET_ID,
                    OrderType.BUY,
                    new BigDecimal("500.00"),
                    new BigDecimal("100.00")          // amount total = 100
            );
            FundsReservedEvent event = buildFundsReservedEvent();

            // Match total: 100 de 100 → remainingAmount = 0 → FILLED
            MatchResult fullResult = new MatchResult(
                    true,
                    COUNTERPART_ID,
                    COUNTERPART_USER,
                    COUNTERPART_WALLET,
                    new BigDecimal("100.00"),          // matchedQty = amount total
                    BigDecimal.ZERO,
                    "FULL"
            );

            given(processedEventRepository.saveAndFlush(any(ProcessedEvent.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(orderRepository.findByCorrelationId(CORRELATION_ID))
                    .willReturn(Optional.of(order));
            given(matchEngine.tryMatch(any(), any(), any(), any(), any(), any(), any()))
                    .willReturn(List.of(fullResult));
            given(orderRepository.save(any(Order.class))).willAnswer(inv -> inv.getArgument(0));
            given(objectMapper.writeValueAsString(any())).willReturn("{\"ok\":true}");
            given(outboxRepository.save(any(OrderOutboxMessage.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            consumer.onFundsReserved(event, channel, DELIVERY_TAG);

            // Espera 2 saves: MatchExecutedEvent + OrderFilledEvent
            then(outboxRepository).should(times(2)).save(outboxCaptor.capture());
            List<OrderOutboxMessage> allMessages = outboxCaptor.getAllValues();

            assertThat(allMessages)
                    .extracting(OrderOutboxMessage::getEventType)
                    .as("Outbox deve conter MatchExecutedEvent e OrderFilledEvent")
                    .containsExactlyInAnyOrder("MatchExecutedEvent", "OrderFilledEvent");

            OrderOutboxMessage fillMsg = allMessages.stream()
                    .filter(m -> "OrderFilledEvent".equals(m.getEventType()))
                    .findFirst().orElseThrow();

            assertThat(fillMsg.getRoutingKey())
                    .as("routing key deve ser " + RabbitMQConfig.RK_ORDER_FILLED)
                    .isEqualTo(RabbitMQConfig.RK_ORDER_FILLED);
            assertThat(fillMsg.getExchange()).isEqualTo(RabbitMQConfig.EVENTS_EXCHANGE);
            assertThat(fillMsg.getPublishedAt())
                    .as("publishedAt deve ser null — aguarda relay assíncrono")
                    .isNull();
            assertThat(fillMsg.getAggregateId()).isEqualTo(ORDER_ID);
            assertThat(fillMsg.getPayload()).isNotBlank();
        }

        /**
         * Garante que match sem transição de status (ex: OPEN → OPEN) não publica
         * nenhum fill event. Proteção contra eventos duplicados em cenários de
         * reprocessamento idempotente.
         *
         * <p>Nota: este cenário não deve ocorrer em produção, pois {@code applyMatch()}
         * sempre transiciona para FILLED ou PARTIAL. Teste documentado para garantia de
         * que exclusivamente o status da ordem controla a publicação.</p>
         */
        @Test
        @DisplayName("AT15-03: não deve publicar eventos de preenchimento para NoMatch")
        void handleNoMatch_shouldNot_persist_fillEvent() throws Exception {
            Order order = buildOrder(OrderType.BUY);
            FundsReservedEvent event = buildFundsReservedEvent();

            given(processedEventRepository.saveAndFlush(any(ProcessedEvent.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(orderRepository.findByCorrelationId(CORRELATION_ID))
                    .willReturn(Optional.of(order));
            given(matchEngine.tryMatch(any(), any(), any(), any(), any(), any(), any()))
                    .willReturn(List.of());
            given(orderRepository.save(any(Order.class))).willAnswer(inv -> inv.getArgument(0));
            given(objectMapper.writeValueAsString(any())).willReturn("{\"ok\":true}");
            given(outboxRepository.save(any(OrderOutboxMessage.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            consumer.onFundsReserved(event, channel, DELIVERY_TAG);

            then(outboxRepository).should(times(1)).save(outboxCaptor.capture());
            OrderOutboxMessage saved = outboxCaptor.getValue();

            assertThat(saved.getEventType())
                    .as("NoMatch deve gravar apenas OrderAddedToBookEvent")
                    .isEqualTo("OrderAddedToBookEvent");
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

    // =========================================================================
    // AT-2.1.1 — Saga TCC: tryMatch() fora de @Transactional + compensação
    // =========================================================================

    /**
     * Testes RED para o critério AT-2.1.1:
     * <ul>
     *   <li>Redis (tryMatch) executado fora de qualquer TX JPA.</li>
     *   <li>Compensação {@code removeFromBook()} se persistência da Fase 3 falhar.</li>
     * </ul>
     *
     * <h2>Por que estes testes falham em FASE RED</h2>
     * <p>O código ainda executa {@code tryMatch()} dentro do mesmo {@code @Transactional}
     * que as operações JPA, sem a separação em fases via {@link TransactionTemplate}.</p>
     * <ul>
     *   <li><strong>AT22-01:</strong> {@code removeFromBook()} nunca é chamado no código atual
     *       após falha de persistência — a compensação Redis não existe.</li>
     *   <li><strong>AT22-02:</strong> {@code txTemplate.execute()} nunca é invocado no código atual
     *       (apenas inserido no construtor porém não utilizado); a verificação de ordem falha.</li>
     * </ul>
     */
    @Nested
    @DisplayName("AT-2.1.1 — Saga TCC: separação de fases e compensação Redis")
    class SagaTccTests {

        /**
         * AT22-01 — Compensação Redis: se a Fase 3 (persistência do resultado) falhar
         * em um cenário de no-match, {@code removeFromBook()} deve ser invocado para
         * desfazer a inserção da ordem no livro Redis.
         *
         * <p><strong>FASE RED:</strong> no código atual não há bloco try-catch ao redor
         * da Fase 3, nem chamada a {@code removeFromBook()} — o teste falha na
         * verificação {@code should(times(1)).removeFromBook(...)}.</p>
         *
         * <p><strong>Fluxo esperado (FASE GREEN):</strong></p>
         * <ol>
         *   <li>Fase 1 (TX): idempotência + findOrder + markAsOpen + save(OPEN).</li>
         *   <li>Fase 2 (sem TX): {@code tryMatch()} retorna noMatch
         *       — ordem inserida no livro Redis.</li>
         *   <li>Fase 3 (TX): {@code handleNoMatch()} → {@code outboxRepository.save()} LANÇA.</li>
         *   <li>Compensação: {@code removeFromBook(orderId, orderType)} chamado.</li>
         *   <li>Compensação: {@code cancelOrder()} salva {@code OrderCancelledEvent}.</li>
         *   <li>ACK enviado ao broker.</li>
         * </ol>
         */
        @Test
        @DisplayName("AT22-01 [RED]: falha na Fase 3 (no-match) aciona removeFromBook como compensação")
        void onFundsReserved_noMatchJpaFails_compensatesWithRemoveFromBook() throws Exception {
            Order order = buildOrder(OrderType.BUY);
            FundsReservedEvent event = buildFundsReservedEvent();

            // Fase 1: sucesso
            given(processedEventRepository.saveAndFlush(any(ProcessedEvent.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(orderRepository.findByCorrelationId(CORRELATION_ID))
                    .willReturn(Optional.of(order));
            given(orderRepository.save(any(Order.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            // Fase 2: no-match — ordem inserida no livro Redis
            given(matchEngine.tryMatch(any(), any(), any(), any(), any(), any(), any()))
                    .willReturn(List.of());

            // objectMapper necessário para handleNoMatch e para cancelOrder na compensação
            given(objectMapper.writeValueAsString(any())).willReturn("{\"ok\":true}");

            // Fase 3: outboxRepository.save LANÇA na 1ª chamada (Fase 3);
            //         sucede na 2ª chamada (compensação cancelOrder)
            given(outboxRepository.save(any(OrderOutboxMessage.class)))
                    .willThrow(new RuntimeException("Phase 3 DB failure"))
                    .willAnswer(inv -> inv.getArgument(0));

            // findById usado na compensação para recarregar ordem do banco
            given(orderRepository.findById(ORDER_ID))
                    .willReturn(Optional.of(buildOrder(OrderType.BUY)));

            // Executa — em FASE GREEN a compensação é acionada e o método NÃO lança
            try {
                consumer.onFundsReserved(event, channel, DELIVERY_TAG);
            } catch (Exception ignored) {
                // Em FASE RED o método pode lançar; a compensação ainda não existe
            }

            // Critério de aceite: removeFromBook deve ter sido chamado como compensação
            then(matchEngine).should(times(1)).removeFromBook(ORDER_ID, OrderType.BUY);
            // ACK enviado ao broker mesmo após falha na Fase 3
            then(channel).should(times(1)).basicAck(DELIVERY_TAG, false);
        }

        /**
         * AT22-02 — {@code tryMatch()} deve ser executado ENTRE duas chamadas
         * a {@code txTemplate.execute()}, provando que o Redis opera fora de
         * qualquer escopo transacional JPA.
         *
         * <p><strong>FASE RED:</strong> {@code txTemplate.execute()} nunca é invocado
         * (código ainda usa {@code @Transactional} único) — a verificação de ordem
         * de chamada no {@link InOrder} falha porque o mock {@code txTemplate} não
         * registra nenhuma interação.</p>
         *
         * <p><strong>FASE GREEN:</strong>
         * <ol>
         *   <li>{@code txTemplate.execute()} — Fase 1 (JPA: idempotência + OPEN).</li>
         *   <li>{@code matchEngine.tryMatch()} — Fase 2 (Redis, sem TX).</li>
         *   <li>{@code txTemplate.execute()} — Fase 3 (JPA: persist match result).</li>
         * </ol>
         * </p>
         */
        @Test
        @DisplayName("AT22-02 [RED]: tryMatch() executado entre Fase 1-TX e Fase 3-TX (fora de @Transactional)")
        void onFundsReserved_tryMatchCalledOutsideTransaction() throws Exception {
            Order order = buildOrder(OrderType.BUY);
            FundsReservedEvent event = buildFundsReservedEvent();

            // Configuração completa do caminho feliz
            given(processedEventRepository.saveAndFlush(any(ProcessedEvent.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(orderRepository.findByCorrelationId(CORRELATION_ID))
                    .willReturn(Optional.of(order));
            given(matchEngine.tryMatch(any(), any(), any(), any(), any(), any(), any()))
                    .willReturn(List.of(buildMatchResult()));
            given(orderRepository.save(any(Order.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(objectMapper.writeValueAsString(any())).willReturn("{\"ok\":true}");
            given(outboxRepository.save(any(OrderOutboxMessage.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            consumer.onFundsReserved(event, channel, DELIVERY_TAG);

            // Critério de aceite: ordem obrigatória txTemplate → tryMatch → txTemplate
            // Prova que tryMatch é chamado FORA do escopo transacional JPA
            InOrder inOrder = inOrder(txTemplate, matchEngine);
            inOrder.verify(txTemplate).execute(any());                              // Fase 1 TX
            inOrder.verify(matchEngine).tryMatch(any(), any(), any(), any(), any(), any(), any()); // Fase 2 sem TX
            inOrder.verify(txTemplate).execute(any());                              // Fase 3 TX
        }
    }
}
