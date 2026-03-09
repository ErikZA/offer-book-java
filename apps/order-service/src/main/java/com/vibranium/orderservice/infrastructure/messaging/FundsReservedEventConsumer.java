package com.vibranium.orderservice.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.vibranium.contracts.commands.wallet.SettleFundsCommand;
import com.vibranium.contracts.enums.FailureReason;
import com.vibranium.contracts.enums.OrderStatus;
import com.vibranium.contracts.events.order.MatchExecutedEvent;
import com.vibranium.contracts.events.order.OrderAddedToBookEvent;
import com.vibranium.contracts.events.order.OrderCancelledEvent;
import com.vibranium.contracts.events.order.OrderFilledEvent;
import com.vibranium.contracts.events.order.OrderPartiallyFilledEvent;
import com.vibranium.contracts.events.wallet.FundsReservedEvent;
import com.vibranium.orderservice.infrastructure.redis.RedisMatchEngineAdapter;
import com.vibranium.orderservice.infrastructure.redis.RedisMatchEngineAdapter.MatchResult;
import com.vibranium.orderservice.config.RabbitMQConfig;
import com.vibranium.orderservice.domain.model.Order;
import com.vibranium.orderservice.domain.model.OrderOutboxMessage;
import com.vibranium.orderservice.domain.model.ProcessedEvent;
import com.vibranium.orderservice.domain.repository.OrderOutboxRepository;
import com.vibranium.orderservice.domain.repository.OrderRepository;
import com.vibranium.orderservice.domain.repository.ProcessedEventRepository;
import com.vibranium.orderservice.application.service.EventStoreService;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Duration;
import java.time.Instant;

/**
 * Consumidor do evento que confirma o bloqueio de fundos na carteira.
 *
 * <p>Quando o wallet-service bloqueia os fundos com sucesso, publica um
 * {@link FundsReservedEvent}. Este consumidor recebe esse evento e executa
 * o fluxo Saga TCC em 3 fases:</p>
 * <ol>
 *   <li><strong>Fase 1 — TX JPA ({@code txTemplate}):</strong>
 *     <ul>
 *       <li>Insere {@code eventId} em {@code tb_order_idempotency_keys} (idempotencia).</li>
 *       <li>Localiza a ordem pelo {@code correlationId}.</li>
 *       <li>Transita para OPEN e persiste a ordem. Commit imediato.</li>
 *     </ul>
 *   </li>
 *   <li><strong>Fase 2 — Redis (sem @Transactional):</strong>
 *     <ul>
 *       <li>Executa o Script Lua atomicamente no Redis Sorted Set ({@code tryMatch()}).</li>
 *       <li>Redis NAO participa da TX JPA — isolamento explícito evita ilusao de atomicidade
 *           cross-store (padrao TCC).</li>
 *       <li>Falha aqui: compensacao via {@code cancelOrder()} em nova TX.</li>
 *     </ul>
 *   </li>
 *   <li><strong>Fase 3 — TX JPA nova ({@code txTemplate}):</strong>
 *     <ul>
 *       <li>Se houver match: grava {@link MatchExecutedEvent} + fill event no Outbox;
 *           atualiza status para FILLED ou PARTIAL.</li>
 *       <li>Se nao houver match: grava {@link OrderAddedToBookEvent} no Outbox.</li>
 *       <li>Falha aqui: compensacao Redis ({@code removeFromBook} se no-match)
 *           + {@code cancelOrder()} em nova TX.</li>
 *     </ul>
 *   </li>
 *   <li>Apos sucesso total: envia {@code basicAck} manual ao RabbitMQ.</li>
 * </ol>
 *
 * <p><strong>Outbox Pattern (AT-02.1):</strong> Nenhum evento e publicado diretamente no broker
 * dentro desta classe. Todos os eventos sao gravados em {@code tb_order_outbox} na mesma
 * transacao JPA que atualiza a {@link Order}, eliminando o Dual Write e garantindo atomicidade
 * financeira. O relay assincrono e feito pelo
 * {@link com.vibranium.orderservice.application.service.OrderOutboxPublisherService}.</p>
 *
 * <p><strong>Domain Events de preenchimento (AT-15.1):</strong> {@link OrderFilledEvent} e
 * {@link OrderPartiallyFilledEvent} sao publicados explicitamente no Outbox apos
 * {@code order.applyMatch()}, com base no status resultante da ordem. Isso elimina a divergencia
 * entre o contrato declarado em {@code common-contracts} e a implementacao, permitindo que sistemas
 * downstream reajam a transicoes de status sem precisar inferir via {@link MatchExecutedEvent}.</p>
 *
 * <p><strong>Estrategia de idempotencia:</strong> INSERT na tabela {@code tb_order_idempotency_keys}
 * com {@code eventId} como PK. Se ja existir ({@link DataIntegrityViolationException}),
 * a mensagem e duplicata e descartada com ACK sem reprocessar. Isso resolve a janela
 * de inconsistencia do check por status: a chave e gravada atomicamente com a mudanca
 * de estado, portanto mesmo uma retentativa apos rollback nao passa pelo check.</p>
 *
 * <p><strong>Rastreabilidade (AT-14.2):</strong> {@link org.slf4j.MDC} é populado com
 * {@code correlationId} e {@code orderId} no início de {@code onFundsReserved} via
 * {@code try-with-resources}, garantindo que todas as linhas de log — incluindo caminhos
 * de duplicata, erro de Redis e compensação — incluam os campos de correlação da Saga.</p>
 *
 * <p><strong>Sequencia garantida (Saga TCC AT-2.1.1):</strong>
 * {@code [TX1: idempotency + OPEN] -> [Redis: match] -> [TX2: outbox + status] -> basicAck}</p>
 * <p><strong>Sequencia garantida (match):</strong>
 * {@code TX1-commit -> tryMatch -> TX2(applyMatch + MatchExecutedEvent + FillEvent) -> basicAck}</p>
 */
@Component
public class FundsReservedEventConsumer {

    private static final Logger logger = LoggerFactory.getLogger(FundsReservedEventConsumer.class);

    private final OrderRepository          orderRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final RedisMatchEngineAdapter  matchEngine;
    // Outbox Pattern: publicação indireta via tabela tb_order_outbox.
    // O OrderOutboxPublisherService (scheduler) faz o relay assíncrono.
    // Isso garante que a atualização da Order e a gravação do evento
    // ocorram na MESMA transação, eliminando o Dual Write.
    private final OrderOutboxRepository    outboxRepository;
    private final ObjectMapper             objectMapper;
    // AT-14: Event Store imutável — gravação complementar ao outbox na mesma TX.
    private final EventStoreService        eventStoreService;
    // AT-14.1: Micrometer Tracing — enriquece o span ativo com atributos de domínio.
    // O span é criado automaticamente pelo Spring AMQP (RabbitListenerObservation) ao
    // receber a mensagem. Tracer.currentSpan() retorna null se não houver span ativo
    // (ex.: execução fora de contexto AMQP observado), por isso a checagem isNotNull.
    private final Tracer                   tracer;
    // AT-15.2: MeterRegistry para métricas de negócio (orders.matched, orders.cancelled)
    private final MeterRegistry            meterRegistry;
    // AT-2.1.1: TransactionTemplate para separação explícita de fases Saga.
    // O consumer extrai tryMatch() para fora de qualquer TX JPA,
    // eliminando a ilusão de atomicidade cross-store Redis+JPA.
    private final TransactionTemplate      txTemplate;

    public FundsReservedEventConsumer(OrderRepository orderRepository,
                                      ProcessedEventRepository processedEventRepository,
                                      RedisMatchEngineAdapter matchEngine,
                                      OrderOutboxRepository outboxRepository,
                                      ObjectMapper objectMapper,
                                      EventStoreService eventStoreService,
                                      Tracer tracer,
                                      MeterRegistry meterRegistry,
                                      TransactionTemplate txTemplate) {
        this.orderRepository          = orderRepository;
        this.processedEventRepository = processedEventRepository;
        this.matchEngine              = matchEngine;
        this.outboxRepository         = outboxRepository;
        this.objectMapper             = objectMapper;
        this.eventStoreService        = eventStoreService;
        this.tracer                   = tracer;
        this.meterRegistry            = meterRegistry;
        this.txTemplate               = txTemplate;
    }

    /**
     * Recebe o evento de fundos reservados com ACK manual e idempotencia por tabela.
     *
     * <p>O {@code containerFactory = "manualAckContainerFactory"} habilita ACK manual
     * neste listener. O ACK so e enviado apos o commit JPA, eliminando a janela de
     * duplicacao entre commit e ACK do at-least-once delivery.</p>
     *
     * <p>Concurrency {@code 5} mantem o throughput para o cenario de 500 ordens simultaneas.</p>
     *
     * <p><strong>Instrumentação MDC (AT-14.2):</strong> {@code correlationId} e {@code orderId}
     * são colocados no MDC via {@code try(MDC.putCloseable(...))} antes do primeiro log.
     * O par {@code try/finally} interno garante que {@code orderId} seja removido mesmo
     * em excepções — o try-with-resources remove {@code correlationId} automaticamente.</p>
     *
     * <p><strong>Execução em 3 fases Saga TCC (AT-2.1.1):</strong></p>
     * <ol>
     *   <li><strong>Fase 1 (TX JPA):</strong> idempotência, localiza ordem, marca como OPEN
     *       e persiste estado no PostgreSQL. Commit imediato via {@code txTemplate}.</li>
     *   <li><strong>Fase 2 (sem TX):</strong> {@code tryMatch()} executado inteiramente fora
     *       de qualquer {@code @Transactional} — Redis não participa da TX JPA.
     *       Falha aqui aciona compensação: {@code cancelOrder} em nova TX.</li>
     *   <li><strong>Fase 3 (TX JPA nova):</strong> persiste resultado do match no Outbox.
     *       Falha aqui aciona compensação Redis ({@code removeFromBook} se no-match)
     *       mais {@code cancelOrder} em nova TX.</li>
     * </ol>
     *
     * @param event       Evento publicado pelo wallet-service confirmando o bloqueio.
     * @param channel     Canal AMQP para envio do ACK/NACK manual.
     * @param deliveryTag Tag de entrega fornecida pelo broker.
     */
    @RabbitListener(
            queues = RabbitMQConfig.QUEUE_FUNDS_RESERVED,
            concurrency = "5",
            containerFactory = "manualAckContainerFactory"
    )
    public void onFundsReserved(FundsReservedEvent event,
                                Channel channel,
                                @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws Exception {
        String eventId = event.eventId().toString();

        // AT-14.2: MDC popula correlationId e orderId para rastreabilidade em todas as letras de log.
        try (var ignoredCorr = MDC.putCloseable("correlationId", event.correlationId().toString())) {
            MDC.put("orderId", event.orderId().toString());
            try {
                logger.info("FundsReservedEvent recebido: eventId={} correlationId={} orderId={}",
                        eventId, event.correlationId(), event.orderId());

                // ================================================================
                // FASE 1 — TX JPA: idempotência + localiza ordem + markAsOpen + save
                // ================================================================
                // DataIntegrityViolationException (duplicata) propaga para fora do lambda
                // e é capturada pelo catch externo, evitando reprocessamento.
                final Optional<Order> orderOpt;
                try {
                    orderOpt = txTemplate.execute(s -> {
                        // Idempotência por tabela: INSERT com eventId como PK única.
                        // DIVE indica duplicata → propaga automaticamente para o catch externo.
                        processedEventRepository.saveAndFlush(new ProcessedEvent(event.eventId()));

                        // Localiza a ordem pelo correlationId da Saga
                        Optional<Order> opt = orderRepository.findByCorrelationId(event.correlationId());
                        if (opt.isEmpty()) {
                            logger.warn("Ordem nao encontrada para correlationId={} -- descartando FundsReservedEvent",
                                    event.correlationId());
                            return Optional.empty();
                        }

                        Order o = opt.get();

                        // AT-14.1: Enriquece o span ativo com atributos de domínio da Saga.
                        io.micrometer.tracing.Span currentSpan = tracer.currentSpan();
                        if (currentSpan != null) {
                            currentSpan
                                    .tag("saga.correlation_id", event.correlationId().toString())
                                    .tag("order.id",            o.getId().toString());
                        }

                        // Fase 1 de domínio: transita de PENDING → OPEN e persiste.
                        // O commit desta TX garante que o estado OPEN seja visível a outros
                        // consumidores antes de executar o match no Redis.
                        o.markAsOpen();
                        orderRepository.save(o);
                        return Optional.of(o);
                    });
                } catch (DataIntegrityViolationException ex) {
                    // Evento duplicado: idempotência por tabela bloqueou o reprocessamento.
                    logger.info("FundsReservedEvent duplicado (idempotente): eventId={}", eventId);
                    channel.basicAck(deliveryTag, false);
                    return;
                }

                if (orderOpt == null || orderOpt.isEmpty()) {
                    channel.basicAck(deliveryTag, false);
                    return;
                }

                final Order order = orderOpt.get();

                // ================================================================
                // FASE 2 — Redis: tryMatch() SEM @Transactional
                // ================================================================
                // Redis NÃO participa de transações JPA. Executar tryMatch() dentro de
                // @Transactional cria ilusão de atomicidade cross-store.
                // Isolamento explícito aqui garante que o commit JPA da Fase 1 ocorreu
                // ANTES do match e que qualquer falha Redis não contamina a TX JPA.
                final List<MatchResult> result;
                try {
                    result = matchEngine.tryMatch(
                            order.getId(),
                            order.getUserId(),
                            order.getWalletId(),
                            order.getOrderType(),
                            order.getPrice(),
                            order.getRemainingAmount(),
                            order.getCorrelationId()
                    );
                } catch (CallNotPermittedException cbEx) {
                    // AT-11: Circuit breaker OPEN — falha rápida sem tentar Redis.
                    // Cancela a ordem imediatamente com reason específica.
                    logger.error("Circuit breaker OPEN — falha rápida: orderId={} cbState={}",
                            order.getId(), cbEx.getCausingCircuitBreakerName());
                    txTemplate.execute(s -> {
                        cancelOrder(order, FailureReason.INTERNAL_ERROR,
                                "REDIS_UNAVAILABLE: Circuit breaker is OPEN");
                        return null;
                    });
                    channel.basicAck(deliveryTag, false);
                    return;
                } catch (Exception redisEx) {
                    // Redis indisponível ou timeout: compensa cancelando a ordem em nova TX.
                    // A Fase 1 já commitou OPEN; esta TX reverte para CANCELLED com registro
                    // no Outbox para que sistemas downstream sejam notificados.
                    logger.error("Falha no Redis match engine: orderId={} error={}",
                            order.getId(), redisEx.getMessage());
                    txTemplate.execute(s -> {
                        cancelOrder(order, FailureReason.INTERNAL_ERROR,
                                "REDIS_UNAVAILABLE: " + redisEx.getMessage());
                        return null;
                    });
                    channel.basicAck(deliveryTag, false);
                    return;
                }

                // ================================================================
                // AT-16: Deduplicação Redis — orderId já existe no book.
                // ALREADY_IN_BOOK é resultado válido (não erro): a ordem já foi
                // inserida no book por uma execução anterior. ACK idempotente.
                // ================================================================
                if (result.size() == 1 && result.get(0).isAlreadyInBook()) {
                    logger.debug("Ordem {} já existe no book (ALREADY_IN_BOOK) — ACK idempotente",
                            order.getId());
                    channel.basicAck(deliveryTag, false);
                    return;
                }

                // ================================================================
                // FASE 3 — TX JPA: persiste resultado do match no Outbox
                // ================================================================
                try {
                    final List<MatchResult> finalResult = result;
                    txTemplate.execute(s -> {
                        if (!finalResult.isEmpty()) {
                            handleMatches(order, finalResult, event);
                        } else {
                            handleNoMatch(order);
                        }
                        return null;
                    });
                } catch (Exception phase3Ex) {
                    // Falha de persistência pós-match: padrão de compensação TCC.
                    logger.error("Fase 3 falhou — falha ao persistir resultado: orderId={} matched={} error={}",
                            order.getId(), !result.isEmpty(), phase3Ex.getMessage(), phase3Ex);

                    // AT-17: Compensação Redis diferenciada por resultado do match.
                    if (result.isEmpty()) {
                        // Sem match: a ordem foi inserida no livro pelo Lua. Remove do livro.
                        try {
                            matchEngine.removeFromBook(order.getId(), order.getOrderType());
                            logger.info("Compensação Redis removeFromBook executada: orderId={}", order.getId());
                        } catch (Exception removeEx) {
                            logger.error("Compensação removeFromBook falhou (Redis indisponível): orderId={} error={}",
                                    order.getId(), removeEx.getMessage());
                        }
                    } else {
                        // COM match: o Lua consumiu/modificou contrapartes. Executar undo_match.lua
                        // para restaurar o estado anterior no Redis.
                        // Obter preços das contrapartes do PostgreSQL para calcular os scores.
                        try {
                            java.util.Map<UUID, java.math.BigDecimal> counterpartPrices = new java.util.HashMap<>();
                            for (MatchResult mr : result) {
                                orderRepository.findById(mr.counterpartId())
                                        .ifPresent(cp -> counterpartPrices.put(
                                                cp.getId(), cp.getPrice()));
                            }
                            int restored = matchEngine.undoMatch(
                                    order.getOrderType(), order.getId(),
                                    result, counterpartPrices);
                            logger.info("Compensação Redis undoMatch executada: orderId={} restored={}",
                                    order.getId(), restored);
                        } catch (Exception undoEx) {
                            // CRITICAL: compensação falhou — inconsistência residual.
                            // NÃO faz retry (regra AT-17). Alertar para resolução manual.
                            logger.error("CRITICAL: undoMatch falhou — inconsistência residual " +
                                            "inevitável. orderId={} error={}",
                                    order.getId(), undoEx.getMessage(), undoEx);
                        }
                    }

                    // Compensação JPA: recarrega ordem do banco para obter estado real commitado
                    // (Fase 3 pode ter chamado applyMatch() mutando o objeto em memória antes
                    // de falhar, tornando o in-memory stale em relação ao banco).
                    try {
                        txTemplate.execute(s -> {
                            Order freshOrder = orderRepository.findById(order.getId()).orElse(order);
                            cancelOrder(freshOrder, FailureReason.INTERNAL_ERROR,
                                    "FASE3_PERSISTENCE_FAILURE: " + phase3Ex.getMessage());
                            return null;
                        });
                    } catch (Exception cancelEx) {
                        logger.error("Compensação cancelOrder falhou: orderId={} error={}",
                                order.getId(), cancelEx.getMessage());
                    }

                    channel.basicAck(deliveryTag, false);
                    return;
                }

                // Todas as fases concluídas com sucesso: ACK após commit JPA da Fase 3.
                channel.basicAck(deliveryTag, false);

            } finally {
                // Remove orderId explicitamente; correlationId é removido pelo try-with-resources.
                MDC.remove("orderId");
            }
        }
    }

    // =========================================================================
    // Handlers internos
    // =========================================================================

    /**
     * Processa todos os matches executados: aplica cada match na ordem e grava um
     * {@link MatchExecutedEvent} por contraparte no Outbox. Ao final, emite exatamente
     * um evento de preenchimento ({@link OrderFilledEvent} ou {@link OrderPartiallyFilledEvent})
     * baseado no status final da ordem após todos os matches.
     *
     * <p><strong>AT-3.1.1 Multi-match:</strong> o script Lua pode retornar N matches em um único
     * tick atômico. Este método itera sobre todos eles, acumulando o estado da ordem até o
     * estado final, e então emite um único evento de fill — evitando eventos intermediários
     * espúrios de PARTIAL para cada contraparte intermediária.</p>
     *
     * <p><strong>AT-2.1.1:</strong> {@code markAsOpen()} NÃO é chamado aqui.
     * A Fase 1 ({@link TransactionTemplate}) já transitou a ordem de PENDING → OPEN
     * e commitou antes de {@code tryMatch()}. Este método recebe a ordem no estado
     * OPEN e aplica diretamente {@code applyMatch()} → FILLED / PARTIAL.</p>
     */
    private void handleMatches(Order order, List<MatchResult> results, FundsReservedEvent event) {
        boolean isBuyOrder       = order.getOrderType().name().equals("BUY");
        UUID    orderUserUUID    = UUID.fromString(order.getUserId());

        // 1. Itera sobre cada contraparte: aplica o match e grava um MatchExecutedEvent por par.
        //    order.applyMatch() acumula as subtrações sobre remainingAmount — OPEN→PARTIAL→FILLED.
        for (MatchResult result : results) {
            order.applyMatch(result.matchedQty());

            UUID counterpartUserUUID = UUID.fromString(result.counterpartUserId());

            MatchExecutedEvent matchEvent = MatchExecutedEvent.of(
                    order.getCorrelationId(),
                    isBuyOrder ? order.getId()                : result.counterpartId(),
                    isBuyOrder ? result.counterpartId()       : order.getId(),
                    isBuyOrder ? orderUserUUID                : counterpartUserUUID,
                    isBuyOrder ? counterpartUserUUID          : orderUserUUID,
                    isBuyOrder ? order.getWalletId()          : result.counterpartWalletId(),
                    isBuyOrder ? result.counterpartWalletId() : order.getWalletId(),
                    order.getPrice(),
                    result.matchedQty()
            );

            saveToOutbox(
                    order.getId(),
                    "MatchExecutedEvent",
                    RabbitMQConfig.EVENTS_EXCHANGE,
                    RabbitMQConfig.RK_MATCH_EXECUTED,
                    matchEvent
            );

            // Saga Step 4: emite SettleFundsCommand para wallet-service liquidar o trade.
            // Todos os campos necessários vêm do MatchExecutedEvent criado acima.
            SettleFundsCommand settleCmd = new SettleFundsCommand(
                    matchEvent.correlationId(),
                    matchEvent.matchId(),
                    matchEvent.buyOrderId(),
                    matchEvent.sellOrderId(),
                    matchEvent.buyerWalletId(),
                    matchEvent.sellerWalletId(),
                    matchEvent.matchPrice(),
                    matchEvent.matchAmount(),
                    1
            );

            saveToOutbox(
                    order.getId(),
                    "SettleFundsCommand",
                    RabbitMQConfig.WALLET_COMMANDS_EXCHANGE,
                    RabbitMQConfig.RK_SETTLE_FUNDS,
                    settleCmd
            );

            logger.info("Match executado (outbox): correlationId={} orderId={} qty={} fillType={}",
                    order.getCorrelationId(), order.getId(), result.matchedQty(), result.fillType());

            // AT-15.2: incrementa contador de matches com tag fillType (FULL|PARTIAL_ASK|PARTIAL_BID)
            Counter.builder("vibranium.orders.matched")
                    .tag("fillType", result.fillType())
                    .register(meterRegistry)
                    .increment();
        }

        // 2. Persiste a ordem com o estado final acumulado (único save para todos os matches).
        orderRepository.save(order);

        // 3. [AT-15.1] Emite exatamente UM evento de fill baseado no status FINAL da ordem.
        //    Regra: somente FILLED ou PARTIAL são possíveis após applyMatch().
        //    Último MatchResult usado como referência para o matchId do evento PARTIAL.
        MatchResult lastResult = results.get(results.size() - 1);

        if (order.getStatus() == OrderStatus.FILLED) {
            saveToOutbox(
                    order.getId(),
                    "OrderFilledEvent",
                    RabbitMQConfig.EVENTS_EXCHANGE,
                    RabbitMQConfig.RK_ORDER_FILLED,
                    OrderFilledEvent.of(
                            order.getCorrelationId(),
                            order.getId(),
                            order.getAmount(),     // totalFilled = quantidade original (remainder = 0)
                            order.getPrice()       // averagePrice = preço limite da ordem
                    )
            );
        } else if (order.getStatus() == OrderStatus.PARTIAL) {
            saveToOutbox(
                    order.getId(),
                    "OrderPartiallyFilledEvent",
                    RabbitMQConfig.EVENTS_EXCHANGE,
                    RabbitMQConfig.RK_ORDER_PARTIALLY_FILLED,
                    OrderPartiallyFilledEvent.of(
                            order.getCorrelationId(),
                            order.getId(),
                            lastResult.counterpartId(),    // matchId = ID da última contraparte
                            lastResult.matchedQty(),       // filledAmount = qty executada no último match
                            order.getRemainingAmount()     // remainingAmount = saldo total restante
                    )
            );
        }

        // AT-15.2: registra duração da Saga (criação → match) com outcome=MATCHED
        if (order.getCreatedAt() != null) {
            Timer.builder("vibranium.saga.duration")
                    .tag("outcome", "MATCHED")
                    .register(meterRegistry)
                    .record(Duration.between(order.getCreatedAt(), Instant.now()));
        }
    }

    /**
     * Processa ausencia de contraparte: registra {@link OrderAddedToBookEvent} no Outbox.
     *
     * <p><strong>AT-2.1.1:</strong> {@code markAsOpen()} e {@code orderRepository.save()}
     * NÃO são chamados aqui. A Fase 1 ({@link TransactionTemplate}) já transitou a ordem
     * PENDING → OPEN, persistiu e commitou. A Fase 2 ({@code tryMatch()}) inseriu a ordem
     * no livro Redis. Este método apenas registra o evento de domínio no Outbox.</p>
     *
     * <p>O estado OPEN já está no banco. A invariante
     * {@code remainingAmount == originalAmount} é mantida (sem applyMatch).</p>
     */
    private void handleNoMatch(Order order) {
        // AT-2.1.1: markAsOpen() e save(order) omitidos — já executados e commitados na Fase 1.
        // Apenas registra o evento no Outbox para relay assíncrono pelo scheduler.
        OrderAddedToBookEvent addedEvent = OrderAddedToBookEvent.of(
                order.getCorrelationId(),
                order.getId(),
                order.getOrderType(),
                order.getPrice(),
                order.getRemainingAmount()
        );

        saveToOutbox(
                order.getId(),
                "OrderAddedToBookEvent",
                RabbitMQConfig.EVENTS_EXCHANGE,
                RabbitMQConfig.RK_ORDER_ADDED_TO_BOOK,
                addedEvent
        );

        logger.info("Ordem adicionada ao livro (outbox): orderId={} type={} price={}",
                order.getId(), order.getOrderType(), order.getPrice());
    }

    /**
     * Cancela a ordem e grava {@link OrderCancelledEvent} no Outbox dentro da mesma transação.
     *
     * @param order   Ordem a ser cancelada.
     * @param reason  Razao padronizada.
     * @param detail  Detalhe tecnico.
     */
    private void cancelOrder(Order order, FailureReason reason, String detail) {
        order.cancel(detail);
        orderRepository.save(order);

        OrderCancelledEvent cancelledEvent = OrderCancelledEvent.of(
                order.getCorrelationId(),
                order.getId(),
                reason,
                detail
        );

        saveToOutbox(
                order.getId(),
                "OrderCancelledEvent",
                RabbitMQConfig.EVENTS_EXCHANGE,
                RabbitMQConfig.RK_ORDER_CANCELLED,
                cancelledEvent
        );

        logger.warn("Ordem cancelada (outbox): orderId={} reason={} detail={}",
                order.getId(), reason, detail);

        // AT-15.2: incrementa contador de cancelamentos com tag reason
        Counter.builder("vibranium.orders.cancelled")
                .tag("reason", reason.name())
                .register(meterRegistry)
                .increment();

        // AT-15.2: registra duração da Saga (criação → cancelamento) com outcome=CANCELLED
        if (order.getCreatedAt() != null) {
            Timer.builder("vibranium.saga.duration")
                    .tag("outcome", "CANCELLED")
                    .register(meterRegistry)
                    .record(Duration.between(order.getCreatedAt(), Instant.now()));
        }
    }

    // =========================================================================
    // Helpers privados
    // =========================================================================

    /**
     * Serializa {@code payload} e persiste um {@link OrderOutboxMessage} na mesma
     * transação JPA já aberta pelo chamador ({@code onFundsReserved}).
     *
     * <p>Atomicidade garantida: como este método é chamado a partir de um contexto
     * já anotado com {@code @Transactional}, o save do outbox participa da mesma
     * unidade de trabalho. Se qualquer operação anterior falhar e resultar em
     * rollback, esta gravação também é desfeita — sem mensagem órfã no outbox.</p>
     *
     * @param aggregateId  UUID da ordem (chave de rastreamento do agregado).
     * @param eventType    Nome do tipo do evento (ex.: {@code "MatchExecutedEvent"}).
     * @param exchange     Exchange RabbitMQ de destino.
     * @param routingKey   Routing key de destino.
     * @param eventPayload Objeto a ser serializado como JSON no payload.
     * @throws IllegalStateException se a serialização Jackson falhar.
     */
    private void saveToOutbox(UUID aggregateId, String eventType,
                              String exchange, String routingKey,
                              Object eventPayload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(eventPayload);
        } catch (JsonProcessingException e) {
            // Falha de serialização é não-recuperável: propagar como erro de sistema.
            // O @Transactional irá fazer rollback, mantendo consistência.
            throw new IllegalStateException(
                    "Falha ao serializar " + eventType + " para o Outbox", e);
        }
        outboxRepository.save(new OrderOutboxMessage(
                aggregateId,
                "Order",
                eventType,
                exchange,
                routingKey,
                json
        ));

        // AT-14: grava o evento também no Event Store imutável (mesma TX).
        // Extrai metadados do DomainEvent se disponível; caso contrário, usa defaults.
        UUID eventId = UUID.randomUUID();
        UUID correlationId = null;
        Instant occurredOn = Instant.now();
        int schemaVersion = 1;
        if (eventPayload instanceof com.vibranium.contracts.events.DomainEvent de) {
            eventId = de.eventId();
            if (de.correlationId() != null) correlationId = de.correlationId();
            if (de.occurredOn() != null) occurredOn = de.occurredOn();
            schemaVersion = de.schemaVersion();
        } else if (eventPayload instanceof com.vibranium.contracts.commands.Command cmd) {
            if (cmd.correlationId() != null) correlationId = cmd.correlationId();
            schemaVersion = cmd.schemaVersion();
        }
        if (correlationId == null) correlationId = UUID.randomUUID();
        eventStoreService.append(
                eventId, aggregateId.toString(), "Order",
                eventType, json, occurredOn, correlationId, schemaVersion
        );
    }
}
