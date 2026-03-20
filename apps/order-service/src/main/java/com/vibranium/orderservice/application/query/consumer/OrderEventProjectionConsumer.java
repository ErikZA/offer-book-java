package com.vibranium.orderservice.application.query.consumer;

import com.mongodb.MongoCommandException;
import com.mongodb.MongoException;
import com.vibranium.contracts.events.order.MatchExecutedEvent;
import com.vibranium.contracts.events.order.OrderCancelledEvent;
import com.vibranium.contracts.events.order.OrderReceivedEvent;
import com.vibranium.contracts.events.wallet.FundsReservedEvent;
import com.vibranium.orderservice.config.RabbitMQConfig;
import com.vibranium.orderservice.application.query.model.OrderDocument;
import com.vibranium.orderservice.application.query.model.OrderDocument.OrderHistoryEntry;
import com.vibranium.orderservice.application.query.service.OrderAtomicHistoryWriter;
import org.bson.types.Decimal128;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Consumer de projeção que constrói e mantém o Read Model de Ordens no MongoDB.
 *
 * <p>Cada listener consome uma fila dedicada de projeção — cópia independente do evento
 * via fanout no {@code vibranium.events} TopicExchange (filas declaradas em
 * {@link RabbitMQConfig}). Isso desacopla o Read Model do Command Side sem
 * nenhum acoplamento temporal.</p>
 *
 * <h3>Idempotência Atômica — AT-05.2</h3>
 * <p>O padrão anterior ({@code findById + appendHistory + save}) sofria de
 * <em>lost update</em> sob concorrência: dois consumers liam o mesmo documento,
 * ambos appendavam em memória, e o último {@code save} sobrescrvia o primeiro.</p>
 *
 * <p>Substituído por {@link OrderAtomicHistoryWriter}, que usa
 * {@code mongoTemplate.updateFirst()} com filtro idempotente por {@code eventId}:</p>
 * <pre>
 *   updateFirst(
 *     { _id: orderId, "history.eventId": { $ne: eventId } },
 *     { $push: { history: entry }, $set: { ... } }
 *   )
 * </pre>
 * <p>O MongoDB aplica <em>document-level locking</em>: a operação é 100% atômica —
 * zero lost update, zero scan O(n) em memória, idempotência garantida no banco.</p>
 *
 * <h3>Resiliência a Ordem de Eventos — AT-05.1 Criação Lazy Determinística</h3>
 * <p>Eventos fora de ordem (ex: {@code FUNDS_RESERVED} antes de {@code ORDER_RECEIVED})
 * são tratados via {@code upsert=true}: se o documento não existe, é criado com os campos
 * mínimos disponíveis ({@code $setOnInsert}). Quando {@code ORDER_RECEIVED} chegar,
 * {@link OrderAtomicHistoryWriter#enrichOrderFieldsIfAbsent} preencherá os campos nulos
 * de forma idempotente sem sobrescrever dados já existentes.</p>
 *
 * <h3>Rastreabilidade — AT-14.2 MDC por listener</h3>
 * <p>Cada método {@code @RabbitListener} envolve seu corpo em
 * {@code try (MDC.putCloseable("correlationId", ...))} de modo que todas as linhas de log
 * emitidas durante o processamento da projeção incluam automaticamente
 * {@code correlationId} e {@code orderId} (ou {@code matchId} para
 * {@link MatchExecutedEvent}, que afeta dois lados sem {@code orderId} único).
 * O MDC é limpo ao final do bloco, sem memory leak em threads do pool AMQP reutilizadas.</p>
 */
@Component
// Criado apenas quando app.mongodb.enabled=true (ou quando a propriedade está ausente,
// comportamento de produção via matchIfMissing=true).
// Em testes do Command Side, AbstractIntegrationTest define app.mongodb.enabled=false
// para desabilitar este bean e evitar falha de injecão dos repositórios MongoDB.
@ConditionalOnProperty(name = "app.mongodb.enabled", matchIfMissing = true)
public class OrderEventProjectionConsumer {

    private static final Logger logger = LoggerFactory.getLogger(OrderEventProjectionConsumer.class);
    private static final int MONGO_WRITE_CONFLICT_CODE = 112;
    private static final String TRANSIENT_TRANSACTION_ERROR_LABEL = "TransientTransactionError";
    private static final String UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL = "UnknownTransactionCommitResult";

    // OrderAtomicHistoryWriter encapsula todas as escritas atômicas via MongoTemplate.
    // OrderHistoryRepository (MongoRepository.save) foi removido do caminho de escrita
    // para evitar o padrão replace-document não-atômico.
    private final OrderAtomicHistoryWriter atomicWriter;
    private final TransactionTemplate mongoTransactionTemplate;

    @Value("${app.projection.match.retry.max-attempts:6}")
    private int matchRetryMaxAttempts;

    @Value("${app.projection.match.retry.initial-delay-ms:80}")
    private long matchRetryInitialDelayMs;

    @Value("${app.projection.match.retry.max-delay-ms:1200}")
    private long matchRetryMaxDelayMs;

    @Value("${app.projection.match.retry.multiplier:2.0}")
    private double matchRetryMultiplier;

    @Value("${app.projection.match.retry.jitter-factor:0.35}")
    private double matchRetryJitterFactor;

    public OrderEventProjectionConsumer(OrderAtomicHistoryWriter atomicWriter,
                                        @Qualifier("mongoTransactionManager")
                                        PlatformTransactionManager mongoTransactionManager) {
        this.atomicWriter = atomicWriter;
        this.mongoTransactionTemplate = new TransactionTemplate(mongoTransactionManager);
    }

    // =========================================================================
    // 1. OrderReceivedEvent → cria documento PENDING ou enriquece stub lazy
    // =========================================================================

    /**
     * Cria um novo {@link OrderDocument} no estado PENDING ou enriquece um stub
     * criado lazily por evento anterior (AT-05.1).
     *
     * <p><strong>Operação atômica (AT-05.2):</strong> {@code upsertAndAppend} executa um
     * único {@code updateFirst}/{@code upsert} no MongoDB — sem read-modify-write.</p>
     *
     * <p><strong>Enriquecimento de stub (AT-05.1):</strong> após o append atômico,
     * {@link OrderAtomicHistoryWriter#enrichOrderFieldsIfAbsent} preenche campos nulos
     * (userId, orderType, price, originalQty, remainingQty) com dois {@code updateFirst}
     * condicionais — nunca sobrescreve valores já preenchidos.</p>
     *
     * @param event Evento publicado por {@code OrderCommandService.placeOrder()}.
     */
    @RabbitListener(
            queues = RabbitMQConfig.QUEUE_ORDER_PROJECTION_RECEIVED,
            // AT-1.2.1: AUTO ACK — projeção é idempotente (filtro $ne por eventId).
            // Usando factory explícito para não herdar o MANUAL global do application.yaml,
            // que causaria acumulação de mensagens unacknowledged no broker em produção.
            containerFactory = "autoAckContainerFactory"
    )
    public void onOrderReceived(OrderReceivedEvent event) {
        String orderId = event.orderId().toString();
        String userId  = event.userId().toString();

        // AT-14.2: MDC garante correlationId e orderId em todos os logs desta projeção.
        try (var ignoredCorr = MDC.putCloseable("correlationId", event.correlationId().toString())) {
            MDC.put("orderId", orderId);
            try {
                logger.debug("Projeção ORDER_RECEIVED: orderId={} userId={}", orderId, userId);

                OrderHistoryEntry entry = new OrderHistoryEntry(
                        event.eventId().toString(),
                        "ORDER_RECEIVED",
                        "type=%s price=%s amount=%s".formatted(
                                event.orderType(), event.price(), event.amount()),
                        event.occurredOn()
                );

                // Upsert atômico: cria o documento com todos os campos de negócio via $setOnInsert
                // se o documento não existir. Se existir (stub lazy do AT-05.1), apenas appenda
                // ao histórico (os $setOnInsert não são aplicados a documentos existentes).
                boolean appended = atomicWriter.upsertAndAppend(
                        orderId, entry, event.occurredOn(),
                        // $setOnInsert — somente na criação do documento (nunca sobrescreve).
                        // Decimal128 explícito: garante tipo BSON numérico (não String) para
                        // campos BigDecimal — necessário para $inc posterior sem TypeMismatch.
                        upsert -> upsert
                                .setOnInsert("userId",       userId)
                                .setOnInsert("orderType",    event.orderType().name())
                                .setOnInsert("price",        new Decimal128(event.price()))
                                .setOnInsert("originalQty",  new Decimal128(event.amount()))
                                .setOnInsert("remainingQty", new Decimal128(event.amount()))
                                .setOnInsert("status",       "PENDING"),
                        null  // sem extraUpdates: ORDER_RECEIVED não transiciona status em documentos existentes
                );

                if (!appended) {
                    logger.debug("Evento ORDER_RECEIVED duplicado ignorado: eventId={} orderId={}",
                            event.eventId(), orderId);
                    return;
                }

                // AT-05.1: enriquece campos nulos de stub lazy (criado por evento out-of-order anterior).
                // Dois updateFirst condicionais — não afetam documentos já completos (campos não null).
                atomicWriter.enrichOrderFieldsIfAbsent(
                        orderId, userId, event.orderType().name(), event.price(), event.amount());

                logger.info("Read Model criado/enriquecido atomicamente: orderId={} userId={}", orderId, userId);

            } finally {
                MDC.remove("orderId");
            }
        }
    }

    // =========================================================================
    // 2. FundsReservedEvent → appenda FUNDS_RESERVED; status → OPEN
    // =========================================================================

    /**
     * Atualiza o documento para OPEN quando os fundos são confirmados pelo wallet-service.
     *
     * <p><strong>Operação atômica (AT-05.2):</strong> {@code upsertAndAppend} com
     * {@code $set("status", "OPEN")} — aplica status OPEN tanto na criação do stub
     * (out-of-order) quanto na atualização do documento existente.</p>
     *
     * <p>Fanout: esta fila recebe a mesma mensagem que a fila do Command Side
     * ({@link RabbitMQConfig#QUEUE_FUNDS_RESERVED}), mas de forma independente.</p>
     *
     * @param event Evento publicado pelo wallet-service.
     */
    @RabbitListener(
            queues = RabbitMQConfig.QUEUE_ORDER_PROJECTION_FUNDS,
            // AT-1.2.1: AUTO ACK — idempotência garantida por $ne no eventId.
            containerFactory = "autoAckContainerFactory"
    )
    public void onFundsReserved(FundsReservedEvent event) {
        String orderId = event.orderId().toString();

        // AT-14.2: MDC popula correlationId e orderId para logs da projeção FUNDS_RESERVED.
        try (var ignoredCorr = MDC.putCloseable("correlationId", event.correlationId().toString())) {
            MDC.put("orderId", orderId);
            try {
                logger.debug("Projeção FUNDS_RESERVED: orderId={} correlationId={}",
                        orderId, event.correlationId());

                OrderHistoryEntry entry = new OrderHistoryEntry(
                        event.eventId().toString(),
                        "FUNDS_RESERVED",
                        "asset=%s amount=%s walletId=%s".formatted(
                                event.asset(), event.reservedAmount(), event.walletId()),
                        event.occurredOn()
                );

                // AT-05.1 + AT-05.2: upsert atômico com idempotência por eventId.
                // Se documento não existe (out-of-order), cria stub com status OPEN.
                // FUNDS_RESERVED significa que os fundos foram reservados → status = OPEN é correto
                // mesmo para stubs criados lazily.
                boolean appended = atomicWriter.upsertAndAppend(
                        orderId, entry, event.occurredOn(),
                        null,   // sem $setOnInsert além de createdAt (já no upsertAndAppend base)
                        extra -> extra.set("status", "OPEN")  // sempre transiciona para OPEN
                );

                if (!appended) {
                    logger.debug("Evento FUNDS_RESERVED duplicado ignorado: eventId={}", event.eventId());
                    return;
                }

                logger.info("Read Model atualizado atomicamente: orderId={} status=OPEN", orderId);

            } finally {
                MDC.remove("orderId");
            }
        }
    }

    // =========================================================================
    // 3. MatchExecutedEvent → appenda MATCH_EXECUTED; status → FILLED ou PARTIAL
    // =========================================================================

    /**
     * Atualiza o documento ao cruzar uma ordem com uma contraparte.
     *
     * <p>O evento contém os dois lados do trade ({@code buyOrderId} e {@code sellOrderId}).
     * Este listener atualiza ambos os documentos.</p>
     *
     * <p><strong>Determinação de FILLED vs PARTIAL (AT-05.2):</strong>
     * {@link OrderAtomicHistoryWriter#appendMatchAndDecrement} usa {@code findAndModify}
     * com {@code $inc("remainingQty", -matchAmount)} e retorna o documento pós-modificação
     * com o {@code remainingQty} resultante. Se {@code remainingQty <= 0} → FILLED;
     * senão → PARTIAL. Tanto o decremento quanto o {@code $push} no histórico ocorrem
     * na mesma operação atômica — zero race condition em decrementos concorrentes.</p>
     *
     * @param event Evento publicado por {@code FundsReservedEventConsumer.handleMatch()}.
     */
    @RabbitListener(
            queues = RabbitMQConfig.QUEUE_ORDER_PROJECTION_MATCH,
            // AT-1.2.1: AUTO ACK — idempotência garantida por prefixo orderId no eventId
            // (buyer e seller geram eventIds distintos — veja updateDocumentWithMatch).
            containerFactory = "autoAckContainerFactory"
    )
    public void onMatchExecuted(MatchExecutedEvent event) {
        // AT-14.2: para MatchExecutedEvent não há um orderId único (afeta buyer e seller).
        // Usamos matchId como orderId no MDC — identifica o match que está sendo projetado.
        try (var ignoredCorr = MDC.putCloseable("correlationId", event.correlationId().toString())) {
            MDC.put("orderId", event.matchId().toString());
            try {
                logger.debug("Projeção MATCH_EXECUTED: matchId={} buyOrderId={} sellOrderId={}",
                        event.matchId(), event.buyOrderId(), event.sellOrderId());

                // Atualiza buyer + seller na MESMA transação MongoDB, com retry local
                // somente para conflitos transientes de escrita (WriteConflict).
                processMatchWithRetry(event);

            } finally {
                MDC.remove("orderId");
            }
        }
    }

    /**
     * Atualiza o {@link OrderDocument} de um dos lados do match (buyer ou seller).
     *
     * <p><strong>Atomicidade AT-05.2:</strong> {@code appendMatchAndDecrement} realiza
     * {@code $push} + {@code $inc} em uma única operação {@code findAndModify} —
     * impossível para dois threads decrementarem o mesmo valor base.</p>
     *
     * @param orderId ID da ordem a atualizar.
     * @param event   Evento de match com os dados do cruzamento.
     */
    private void updateDocumentWithMatch(String orderId, MatchExecutedEvent event) {
        OrderHistoryEntry entry = new OrderHistoryEntry(
                // Prefixo do orderId garante eventId único por lado: mesmo matchId
                // gera 2 writes (buyer + seller) com os mesmos event.eventId().
                // O sufixo "-orderId" diferencia buyer de seller na chave de idempotência.
                event.eventId().toString() + "-" + orderId,
                "MATCH_EXECUTED",
                "matchId=%s price=%s qty=%s".formatted(
                        event.matchId(), event.matchPrice(), event.matchAmount()),
                event.occurredOn()
        );

        // AT-05.1 + AT-05.2: appendMatchAndDecrement cria stub se necessário (out-of-order),
        // appenda ao histórico com filtro idempotente E decrementa remainingQty com $inc —
        // tudo em uma única operação findAndModify atômica.
        // Retorna o documento pós-modificação para determinar o status final.
        OrderDocument updated = atomicWriter.appendMatchAndDecrement(
                orderId, entry, event.matchAmount(), event.occurredOn());

        if (updated == null) {
            // null = eventId já no histórico (duplicata) → idempotência garantida
            logger.debug("MATCH_EXECUTED duplicado ignorado: eventId={} orderId={}",
                    event.eventId(), orderId);
            return;
        }

        logger.info("Read Model atualizado atomicamente: orderId={} status={} remaining={}",
                orderId, updated.getStatus(), updated.getRemainingQty());
    }

    /**
     * Processa MATCH_EXECUTED dentro de uma transação MongoDB com retry local
     * para conflitos transientes (WriteConflict / TransientTransactionError).
     *
     * <p>Objetivo: reduzir mensagens encaminhadas para DLQ quando o conflito é
     * temporário e pode ser resolvido com curto backoff.</p>
     */
    private void processMatchWithRetry(MatchExecutedEvent event) {
        RuntimeException lastException = null;

        for (int attempt = 1; attempt <= matchRetryMaxAttempts; attempt++) {
            try {
                mongoTransactionTemplate.executeWithoutResult(status -> {
                    updateDocumentWithMatch(event.buyOrderId().toString(), event);
                    updateDocumentWithMatch(event.sellOrderId().toString(), event);
                });

                if (attempt > 1) {
                    logger.info(
                            "MATCH_EXECUTED aplicado após retry local: attempt={} eventId={} matchId={}",
                            attempt, event.eventId(), event.matchId()
                    );
                }
                return;
            } catch (RuntimeException ex) {
                lastException = ex;
                boolean transientConflict = isTransientMongoConflict(ex);

                if (!transientConflict || attempt >= matchRetryMaxAttempts) {
                    throw ex;
                }

                long backoffMs = computeBackoffWithJitter(attempt);
                logger.warn(
                        "Conflito transiente no MATCH_EXECUTED; retry local {}/{} em {}ms. eventId={} matchId={}",
                        attempt, matchRetryMaxAttempts, backoffMs, event.eventId(), event.matchId(), ex
                );
                sleepBackoff(backoffMs);
            }
        }

        if (lastException != null) {
            throw lastException;
        }
    }

    private boolean isTransientMongoConflict(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof MongoCommandException commandException) {
                if (commandException.getErrorCode() == MONGO_WRITE_CONFLICT_CODE) {
                    return true;
                }
                if (commandException.hasErrorLabel(TRANSIENT_TRANSACTION_ERROR_LABEL)
                        || commandException.hasErrorLabel(UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL)) {
                    return true;
                }
            }
            if (current instanceof MongoException mongoException) {
                if (mongoException.getCode() == MONGO_WRITE_CONFLICT_CODE) {
                    return true;
                }
                if (mongoException.hasErrorLabel(TRANSIENT_TRANSACTION_ERROR_LABEL)
                        || mongoException.hasErrorLabel(UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL)) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private long computeBackoffWithJitter(int attempt) {
        double exponentialDelay = matchRetryInitialDelayMs * Math.pow(matchRetryMultiplier, Math.max(0, attempt - 1));
        long baseDelay = Math.min((long) exponentialDelay, matchRetryMaxDelayMs);
        long jitterWindow = Math.max(1L, Math.round(baseDelay * matchRetryJitterFactor));
        long jitter = ThreadLocalRandom.current().nextLong(-jitterWindow, jitterWindow + 1);
        return Math.max(1L, baseDelay + jitter);
    }

    private static void sleepBackoff(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Retry de projeção interrompido", interrupted);
        }
    }

    // =========================================================================
    // 4. OrderCancelledEvent → appenda ORDER_CANCELLED; status → CANCELLED
    // =========================================================================

    /**
     * Marca o documento como CANCELLED ao receber o evento de cancelamento.
     *
     * <p>Se o documento não existir (ordem cancelada antes de ser projetada),
     * cria um stub mínimo para que o cancelamento seja registrado no histórico.
     * Cancelamentos são eventos terminais — registrar o fato é preferível a perder a informação.</p>
     *
     * <p><strong>Operação atômica (AT-05.2):</strong> {@code upsertAndAppend} com
     * {@code $set("status", "CANCELLED")} garante que nem o append nem a transição
     * de status podem ser perdidos por lost update.</p>
     *
     * @param event Evento publicado por {@code FundsReservedEventConsumer.cancelOrder()}.
     */
    @RabbitListener(
            queues = RabbitMQConfig.QUEUE_ORDER_PROJECTION_CANCELLED,
            // AT-1.2.1: AUTO ACK — cancelamentos são eventos terminais; idempotência
            // garantida por $ne no eventId. Perda de evento é preferível a
            // acumulação indefinida no broker.
            containerFactory = "autoAckContainerFactory"
    )
    public void onOrderCancelled(OrderCancelledEvent event) {
        String orderId = event.orderId().toString();

        // AT-14.2: MDC popula correlationId e orderId para logs do cancelamento na projeção.
        try (var ignoredCorr = MDC.putCloseable("correlationId", event.correlationId().toString())) {
            MDC.put("orderId", orderId);
            try {
                logger.debug("Projeção ORDER_CANCELLED: orderId={} reason={}", orderId, event.reason());

                OrderHistoryEntry entry = new OrderHistoryEntry(
                        event.eventId().toString(),
                        "ORDER_CANCELLED",
                        "reason=%s detail=%s".formatted(event.reason(), event.detail()),
                        event.occurredOn()
                );

                // AT-05.1 + AT-05.2: upsert atômico. Cancelamentos são terminais —
                // status CANCELLED mesmo em stubs criados lazily.
                boolean appended = atomicWriter.upsertAndAppend(
                        orderId, entry, event.occurredOn(),
                        null,   // sem $setOnInsert adicional
                        extra -> extra.set("status", "CANCELLED")
                );

                if (!appended) {
                    logger.debug("Evento ORDER_CANCELLED duplicado ignorado: eventId={}", event.eventId());
                    return;
                }

                logger.info("Read Model atualizado atomicamente: orderId={} status=CANCELLED reason={}",
                        orderId, event.reason());

            } finally {
                MDC.remove("orderId");
            }
        }
    }
}

