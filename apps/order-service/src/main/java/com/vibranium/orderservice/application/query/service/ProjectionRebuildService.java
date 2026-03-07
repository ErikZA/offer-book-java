package com.vibranium.orderservice.application.query.service;

import com.vibranium.orderservice.application.query.model.OrderDocument;
import com.vibranium.orderservice.application.query.model.OrderDocument.OrderHistoryEntry;
import com.vibranium.orderservice.domain.model.Order;
import com.vibranium.orderservice.domain.model.OrderOutboxMessage;
import com.vibranium.orderservice.domain.repository.OrderOutboxRepository;
import com.vibranium.orderservice.domain.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Serviço de reconstrução da projeção MongoDB (Read Model) a partir do PostgreSQL (Command Side).
 *
 * <p>O Read Model ({@link OrderDocument}) é normalmente populado via projeção assíncrona de
 * eventos do RabbitMQ. Se o MongoDB for corrompido ou dados forem perdidos, os eventos já
 * foram ACK-ados e não são re-entregáveis. Este serviço reconstrói a projeção a partir da
 * fonte de verdade (PostgreSQL) + histórico de eventos no outbox.</p>
 *
 * <h3>Modos de operação</h3>
 * <ul>
 *   <li><strong>Full rebuild:</strong> itera todas as ordens no PostgreSQL e faz upsert no MongoDB.</li>
 *   <li><strong>Incremental rebuild:</strong> processa apenas ordens criadas/atualizadas desde o último rebuild.</li>
 * </ul>
 *
 * <h3>Garantias</h3>
 * <ul>
 *   <li><strong>Idempotente:</strong> usa upsert — executar 2x produz o mesmo resultado.</li>
 *   <li><strong>Non-blocking:</strong> não deleta documentos — leituras continuam durante rebuild.</li>
 *   <li><strong>Memória constante:</strong> usa {@code Stream<Order>} com cursor PostgreSQL
 *       (fetch size 500) em vez de carregar tudo em memória.</li>
 * </ul>
 *
 * @see OrderAtomicHistoryWriter
 * @see com.vibranium.orderservice.web.controller.AdminProjectionController
 */
@Service
@ConditionalOnProperty(name = "app.mongodb.enabled", matchIfMissing = true)
public class ProjectionRebuildService {

    private static final Logger logger = LoggerFactory.getLogger(ProjectionRebuildService.class);

    private final OrderRepository orderRepository;
    private final OrderOutboxRepository orderOutboxRepository;
    private final MongoTemplate mongoTemplate;

    @Value("${app.projection.rebuild.timeout-minutes:30}")
    private int timeoutMinutes;

    /** Timestamp do último rebuild completo — usado como base para rebuilds incrementais. */
    private volatile Instant lastRebuildAt;

    public ProjectionRebuildService(OrderRepository orderRepository,
                                     OrderOutboxRepository orderOutboxRepository,
                                     MongoTemplate mongoTemplate) {
        this.orderRepository = orderRepository;
        this.orderOutboxRepository = orderOutboxRepository;
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * Reconstrói a projeção MongoDB completa a partir do PostgreSQL.
     *
     * <p>Itera todas as ordens usando cursor server-side (fetch size 500) e faz upsert
     * de cada {@link OrderDocument} no MongoDB. O histórico é reconstruído a partir
     * das mensagens do outbox ({@code tb_order_outbox}).</p>
     *
     * @return Resultado com contagem de ordens processadas e total.
     */
    @Transactional(readOnly = true)
    public RebuildResult rebuildFull() {
        return doRebuildFull();
    }

    /**
     * Reconstrói apenas ordens criadas ou atualizadas desde o último rebuild.
     *
     * <p>Se nenhum rebuild anterior foi executado, processa todas as ordens
     * (equivalente a um rebuild completo).</p>
     *
     * @return Resultado com contagem de ordens processadas.
     */
    @Transactional(readOnly = true)
    public RebuildResult rebuildIncremental() {
        Instant since = lastRebuildAt != null ? lastRebuildAt : Instant.EPOCH;
        return doRebuildIncremental(since);
    }

    /**
     * Job agendado para rebuild incremental automático.
     *
     * <p>Desabilitado por padrão (cron = "-"). Para habilitar, configure
     * {@code app.projection.rebuild.incremental-cron} com uma expressão cron válida
     * (ex: {@code "0 0 * * * *"} para execução a cada hora).</p>
     *
     * <p>Só executa se um rebuild completo anterior tiver sido feito (para ter
     * um ponto de referência temporal).</p>
     */
    @Scheduled(cron = "${app.projection.rebuild.incremental-cron:-}")
    @Transactional(readOnly = true)
    public void scheduledIncrementalRebuild() {
        if (lastRebuildAt == null) {
            logger.debug("Skipping scheduled incremental rebuild: no previous full rebuild recorded");
            return;
        }
        logger.info("Scheduled incremental rebuild triggered");
        doRebuildIncremental(lastRebuildAt);
    }

    // =========================================================================
    // Lógica interna — compartilhada entre métodos públicos e scheduled
    // =========================================================================

    private RebuildResult doRebuildFull() {
        long total = orderRepository.count();
        logger.info("Starting full projection rebuild: {} orders to process", total);

        Instant deadline = Instant.now().plusSeconds(timeoutMinutes * 60L);
        AtomicLong processed = new AtomicLong(0);
        Instant rebuildStart = Instant.now();

        try (Stream<Order> orders = orderRepository.streamAll()) {
            orders.forEach(order -> {
                checkDeadline(deadline, processed.get(), total);
                upsertOrderDocument(order);
                long current = processed.incrementAndGet();
                if (current % 1000 == 0) {
                    logger.info("Rebuild progress: {}/{}", current, total);
                }
            });
        }

        lastRebuildAt = rebuildStart;
        logger.info("Full projection rebuild completed: {}/{}", processed.get(), total);
        return new RebuildResult(processed.get(), total);
    }

    private RebuildResult doRebuildIncremental(Instant since) {
        long total = orderRepository.countModifiedAfter(since);
        logger.info("Starting incremental projection rebuild: {} orders modified since {}", total, since);

        Instant deadline = Instant.now().plusSeconds(timeoutMinutes * 60L);
        AtomicLong processed = new AtomicLong(0);
        Instant rebuildStart = Instant.now();

        try (Stream<Order> orders = orderRepository.streamModifiedAfter(since)) {
            orders.forEach(order -> {
                checkDeadline(deadline, processed.get(), total);
                upsertOrderDocument(order);
                long current = processed.incrementAndGet();
                if (current % 1000 == 0) {
                    logger.info("Incremental rebuild progress: {}/{}", current, total);
                }
            });
        }

        lastRebuildAt = rebuildStart;
        logger.info("Incremental projection rebuild completed: {}/{}", processed.get(), total);
        return new RebuildResult(processed.get(), total);
    }

    /**
     * Reconstrói um {@link OrderDocument} no MongoDB via upsert a partir de uma {@link Order} do PostgreSQL.
     *
     * <p>O upsert garante:</p>
     * <ul>
     *   <li>Idempotência: executar 2x para a mesma ordem produz o mesmo documento.</li>
     *   <li>Non-blocking: leituras continuam durante o rebuild (sem delete + recreate).</li>
     *   <li>{@code setOnInsert("createdAt")} preserva o timestamp original se o documento já existir.</li>
     * </ul>
     */
    private void upsertOrderDocument(Order order) {
        String orderId = order.getId().toString();

        // Reconstrói histórico a partir dos eventos no outbox
        List<OrderOutboxMessage> outboxMessages =
                orderOutboxRepository.findByAggregateIdOrderByCreatedAtAsc(order.getId());

        List<OrderHistoryEntry> historyEntries = outboxMessages.stream()
                .map(msg -> new OrderHistoryEntry(
                        msg.getId().toString(),
                        msg.getEventType(),
                        "Rebuilt from outbox: " + msg.getRoutingKey(),
                        msg.getCreatedAt()))
                .toList();

        Query query = Query.query(Criteria.where("_id").is(orderId));
        Update update = new Update()
                .set("userId", order.getUserId())
                .set("orderType", order.getOrderType().name())
                .set("status", order.getStatus().name())
                .set("price", order.getPrice())
                .set("originalQty", order.getAmount())
                .set("remainingQty", order.getRemainingAmount())
                .set("updatedAt", Instant.now())
                .set("history", historyEntries)
                .setOnInsert("createdAt", order.getCreatedAt());

        mongoTemplate.upsert(query, update, OrderDocument.class);
    }

    private void checkDeadline(Instant deadline, long processed, long total) {
        if (Instant.now().isAfter(deadline)) {
            throw new ProjectionRebuildTimeoutException(
                    "Rebuild timeout exceeded: %d minutes (processed %d/%d)"
                            .formatted(timeoutMinutes, processed, total));
        }
    }

    /** Resultado de uma operação de rebuild. */
    public record RebuildResult(long processed, long total) {}

    /**
     * Exceção lançada quando o rebuild excede o timeout configurado.
     */
    public static class ProjectionRebuildTimeoutException extends RuntimeException {
        public ProjectionRebuildTimeoutException(String message) {
            super(message);
        }
    }
}
