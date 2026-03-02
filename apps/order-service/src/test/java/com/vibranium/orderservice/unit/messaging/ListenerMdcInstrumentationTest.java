package com.vibranium.orderservice.unit.messaging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.rabbitmq.client.Channel;
import com.vibranium.contracts.enums.FailureReason;
import com.vibranium.contracts.enums.OrderStatus;
import com.vibranium.contracts.events.wallet.FundsReservationFailedEvent;
import com.vibranium.orderservice.adapter.messaging.FundsReservationFailedEventConsumer;
import com.vibranium.orderservice.domain.model.Order;
import com.vibranium.orderservice.domain.repository.OrderRepository;
import com.vibranium.orderservice.domain.repository.ProcessedEventRepository;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AT-14.2 — Fase RED: Verifica que listeners AMQP populam MDC com
 * {@code correlationId} e {@code orderId} em todas as linhas de log.
 *
 * <h2>Por que este teste falha ANTES da implementação</h2>
 * <p>Sem {@code MDC.putCloseable("correlationId", ...)} nos listeners,
 * o {@link org.slf4j.MDC} permanece vazio durante a execução.
 * O {@link ListAppender} captura os eventos Logback com o mapa MDC vazio —
 * {@code logEvent.getMDCPropertyMap()} retorna {@code {}} — e as asserções
 * {@code containsEntry("correlationId", ...)} falham.
 * <strong>Fase RED confirmada.</strong></p>
 *
 * <h2>Por que este teste passa APÓS a implementação</h2>
 * <p>Com {@code try (var ignored = MDC.putCloseable("correlationId", event.correlationId().toString()))}
 * no início do listener:</p>
 * <ol>
 *   <li>O MDC é populado com {@code correlationId} antes do primeiro log.</li>
 *   <li>O {@code finally} remove {@code orderId} após o processamento.</li>
 *   <li>O try-with-resources remove {@code correlationId} ao fechar o bloco.</li>
 *   <li>Todos os eventos de log capturados pelo {@link ListAppender} têm os
 *       dois campos no {@code getMDCPropertyMap()}.</li>
 *   <li>Após a execução, {@code MDC.get("correlationId")} retorna {@code null}
 *       — sem memory leak de ThreadLocal.</li>
 * </ol>
 *
 * <h2>O que é MDC e por que importa</h2>
 * <p><strong>MDC (Mapped Diagnostic Context)</strong> é um mecanismo do SLF4J/Logback
 * baseado em {@link ThreadLocal}: pares chave-valor anexados ao contexto da thread
 * atual e incluídos automaticamente em cada linha de log via {@code %X{chave}}
 * no pattern do appender. Permite que {@code grep correlationId=<uuid>} retorne
 * todas as linhas de log da mesma Saga, em qualquer serviço.</p>
 *
 * <p><strong>Riscos de não limpar o MDC:</strong> threads do pool Spring AMQP são
 * reutilizadas. Se o MDC não for limpo (via try-with-resources ou {@code finally}),
 * o {@code correlationId} de uma mensagem anterior "vaza" para a próxima, gerando
 * logs com IDs incorretos — falso positivo em rastreabilidade e diagnósticos.</p>
 *
 * <p><strong>Diferença MDC × Tracing (AT-14.1):</strong></p>
 * <ul>
 *   <li><strong>Tracing</strong>: enriquece spans no Jaeger com tags de domínio
 *       ({@code saga.correlation_id}). Visível via UI do Jaeger. Requer bridge OTel.</li>
 *   <li><strong>MDC</strong>: enriquece linhas de log com campos de contexto.
 *       Visível via {@code grep} em logs JSON/texto. Funciona sem infraestrutura extra.</li>
 * </ul>
 * <p>Os dois mecanismos são complementares: MDC para logs, tracing para spans.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AT-14.2 — Instrumentação MDC em listeners AMQP")
class ListenerMdcInstrumentationTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private Tracer tracer;

    @Mock
    private Channel channel;

    private FundsReservationFailedEventConsumer consumer;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger consumerLogger;

    @BeforeEach
    void setUp() {
        consumer = new FundsReservationFailedEventConsumer(
                orderRepository, processedEventRepository, tracer);

        // Configura ListAppender para capturar eventos de log do listener,
        // incluindo o mapas MDC gravado no momento de cada logger.info/warn/debug.
        consumerLogger = (Logger) LoggerFactory.getLogger(FundsReservationFailedEventConsumer.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        consumerLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        consumerLogger.detachAppender(logAppender);
        // Limpeza defensiva: garante MDC limpo entre testes para não interferir
        // em outras classes executadas no mesmo pool de threads (surefire-forks).
        MDC.clear();
    }

    // =========================================================================
    // Cenário principal: MDC deve conter correlationId e orderId
    // =========================================================================

    /**
     * Verifica que onFundsReservationFailed popula MDC com correlationId e orderId
     * em todas as linhas de log INFO/WARN emitidas durante o processamento.
     *
     * <p><strong>RED antes de AT-14.2:</strong> MDC permanece vazio → todas as asserções
     * {@code containsEntry("correlationId", ...)} lançam {@code AssertionError}.</p>
     *
     * <p><strong>GREEN após AT-14.2:</strong> {@code try(MDC.putCloseable(...))} popula
     * o MDC → {@code getMDCPropertyMap()} retorna o mapa completo para cada log.</p>
     */
    @Test
    @DisplayName("RED: logs INFO/WARN devem conter 'correlationId' e 'orderId' no MDC durante o processamento")
    void onFundsReservationFailed_shouldPopulateMDC_withCorrelationIdAndOrderId() throws Exception {
        // GIVEN: evento com correlationId e orderId conhecidos
        UUID correlationId = UUID.randomUUID();
        UUID orderId       = UUID.randomUUID();
        UUID eventId       = UUID.randomUUID();

        FundsReservationFailedEvent event = new FundsReservationFailedEvent(
                eventId,
                correlationId,
                orderId.toString(),     // aggregateId
                Instant.now(),
                orderId,
                FailureReason.INSUFFICIENT_FUNDS,
                "Saldo insuficiente para reserva"
        );

        // Mock: idempotência passa (não lança DataIntegrityViolationException)
        when(processedEventRepository.saveAndFlush(any())).thenReturn(null);

        // Mock: ordem encontrada e em estado PENDING (passível de cancelamento)
        Order order = mock(Order.class);
        when(order.getId()).thenReturn(orderId);
        when(order.getStatus()).thenReturn(OrderStatus.PENDING);
        when(orderRepository.findByCorrelationId(correlationId)).thenReturn(Optional.of(order));

        // Mock: sem span ativo (evita NullPointerException no AT-14.1 span enrichment)
        when(tracer.currentSpan()).thenReturn(null);

        // Pré-condição: MDC está limpo antes da execução do listener
        assertThat(MDC.get("correlationId"))
                .as("Pré-condição: MDC.correlationId deve ser null ANTES da execução do listener")
                .isNull();

        // WHEN: invoca o listener como o Spring AMQP faria
        consumer.onFundsReservationFailed(event, channel, 1L);

        // THEN: ao menos um evento de log foi emitido
        assertThat(logAppender.list)
                .as("AT-14.2: O listener deve emitir ao menos um log INFO durante o processamento")
                .isNotEmpty();

        // THEN: todos os logs INFO e WARN devem ter correlationId no MDC
        // =====================================================================
        // RED antes da implementação (AT-14.2):
        //   - getMDCPropertyMap() == {} (vazio)
        //   - containsEntry("correlationId", ...) lança AssertionError → RED ✅
        //
        // GREEN após implementação (AT-14.2):
        //   - try(MDC.putCloseable("correlationId", ...) popula o MDC
        //   - getMDCPropertyMap() == {correlationId=..., orderId=...}
        //   - assertThat PASSA → GREEN ✅
        // =====================================================================
        assertThat(logAppender.list)
                .filteredOn(e -> e.getLevel().isGreaterOrEqual(Level.INFO))
                .as("AT-14.2 RED: Logs INFO/WARN do listener não contêm 'correlationId' no MDC.\n"
                        + "Para corrigir: envolver o corpo do método em\n"
                        + "  try (var ignored = MDC.putCloseable(\"correlationId\", "
                        +        "event.correlationId().toString())) {\n"
                        + "      MDC.put(\"orderId\", event.orderId().toString());\n"
                        + "      try { /* corpo */ } finally { MDC.remove(\"orderId\"); }\n"
                        + "  }")
                .allSatisfy(logEvent -> {
                    assertThat(logEvent.getMDCPropertyMap())
                            .as("Log '%s' deve conter correlationId no MDC",
                                    logEvent.getFormattedMessage())
                            .containsEntry("correlationId", correlationId.toString());

                    assertThat(logEvent.getMDCPropertyMap())
                            .as("Log '%s' deve conter orderId no MDC",
                                    logEvent.getFormattedMessage())
                            .containsEntry("orderId", orderId.toString());
                });

        // THEN: MDC deve estar limpo APÓS execução (sem memory leak de ThreadLocal)
        assertThat(MDC.get("correlationId"))
                .as("AT-14.2: MDC.correlationId deve ser null APÓS execução — sem memory leak")
                .isNull();

        assertThat(MDC.get("orderId"))
                .as("AT-14.2: MDC.orderId deve ser null APÓS execução — sem memory leak")
                .isNull();
    }

    // =========================================================================
    // Cenário de duplicata: MDC deve ser populado mesmo ao descartar com ACK
    // =========================================================================

    /**
     * Verifica que o MDC é populado também em cenários de descarte por idempotência.
     *
     * <p>O correlationId e orderId são extraídos do evento antes de qualquer verificação,
     * portanto o primeiro log "duplicado" deve conter os campos no MDC.</p>
     */
    @Test
    @DisplayName("RED: log de duplicata idempotente também deve conter correlationId no MDC")
    void onFundsReservationFailed_shouldPopulateMDC_evenOnIdempotentDiscard() throws Exception {
        // GIVEN
        UUID correlationId = UUID.randomUUID();
        UUID orderId       = UUID.randomUUID();
        UUID eventId       = UUID.randomUUID();

        FundsReservationFailedEvent event = new FundsReservationFailedEvent(
                eventId, correlationId, orderId.toString(),
                Instant.now(), orderId, FailureReason.INSUFFICIENT_FUNDS, "Duplicata"
        );

        // Mock: simula duplicata — lança DataIntegrityViolationException
        when(processedEventRepository.saveAndFlush(any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key"));

        // WHEN
        consumer.onFundsReservationFailed(event, channel, 2L);

        // THEN: o log de descarte ("duplicado") deve ter correlationId no MDC
        assertThat(logAppender.list)
                .filteredOn(e -> e.getFormattedMessage().contains("duplicado"))
                .as("AT-14.2 RED: Log de duplicata idempotente deve conter correlationId no MDC")
                .allSatisfy(logEvent ->
                        assertThat(logEvent.getMDCPropertyMap())
                                .containsEntry("correlationId", correlationId.toString()));

        // Sem memory leak após descarte por idempotência
        assertThat(MDC.get("correlationId")).isNull();
    }
}
