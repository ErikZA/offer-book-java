package com.vibranium.orderservice.query.consumer;

import com.vibranium.contracts.events.order.MatchExecutedEvent;
import com.vibranium.contracts.events.order.OrderCancelledEvent;
import com.vibranium.contracts.events.order.OrderReceivedEvent;
import com.vibranium.contracts.events.wallet.FundsReservedEvent;
import com.vibranium.orderservice.config.RabbitMQConfig;
import com.vibranium.orderservice.query.model.OrderDocument;
import com.vibranium.orderservice.query.model.OrderDocument.OrderHistoryEntry;
import com.vibranium.orderservice.query.repository.OrderHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Consumer de projeção que constrói e mantém o Read Model de Ordens no MongoDB.
 *
 * <p>Cada listener consome uma fila dedicated de projeção — cópia independente do evento
 * via fanout no {@code vibranium.events} TopicExchange (filas declaradas em
 * {@link RabbitMQConfig}). Isso desacopla o Read Model do Command Side sem
 * nenhum acoplamento temporal.</p>
 *
 * <p><strong>Contrato de Idempotência:</strong> cada entrada no
 * {@link OrderDocument#getHistory() history} usa o {@code eventId} do
 * {@link com.vibranium.contracts.events.DomainEvent} como chave de deduplicação.
 * Mensagens re-entregues pelo RabbitMQ (broker restart, NACK etc.) são descartadas
 * silenciosamente se já foram processadas.</p>
 *
 * <p><strong>Resiliência a Ordem de Eventos — AT-05.1 Criação Lazy Determinística:</strong>
 * Eventos podem chegar fora de ordem (ex: {@code FUNDS_RESERVED} antes de
 * {@code ORDER_RECEIVED}). Quando o documento não existe, o consumer cria um stub mínimo
 * via {@link OrderDocument#createMinimalPending} para que o evento seja registrado no
 * histórico sem ser descartado. Quando {@code ORDER_RECEIVED} chegar posteriormente,
 * {@link OrderDocument#enrichFields} preenche os campos financeiros faltantes de forma
 * idempotente. Zero {@link IllegalStateException} por ausência de documento.
 * Zero {@code return} silencioso. Zero eventos descartados.</p>
 *
 * <p><strong>Decisão de design:</strong> MongoDB {@code findById} + {@code save}
 * em vez de {@code $push} nativo — mais legível, mesma performance para o volume
 * atual. Para alta frequência (>10k eventos/s), considerar {@code MongoTemplate.update}
 * com {@code $push} atômico e {@code addToSet} para idempotência.</p>
 */
@Component
// Criado apenas quando app.mongodb.enabled=true (ou quando a propriedade está ausente,
// comportamento de produção via matchIfMissing=true).
// Em testes do Command Side, AbstractIntegrationTest define app.mongodb.enabled=false
// para desabilitar este bean e evitar falha de injecão de OrderHistoryRepository.
@ConditionalOnProperty(name = "app.mongodb.enabled", matchIfMissing = true)
public class OrderEventProjectionConsumer {

    private static final Logger logger = LoggerFactory.getLogger(OrderEventProjectionConsumer.class);

    private final OrderHistoryRepository orderHistoryRepository;

    public OrderEventProjectionConsumer(OrderHistoryRepository orderHistoryRepository) {
        this.orderHistoryRepository = orderHistoryRepository;
    }

    // =========================================================================
    // 1. OrderReceivedEvent → cria documento PENDING
    // =========================================================================

    /**
     * Cria um novo {@link OrderDocument} no estado PENDING quando a ordem é recebida.
     *
     * <p>Se o documento já existir (re-entrega do evento), a entrada no history
     * é ignorada via {@link OrderDocument#appendHistory(OrderHistoryEntry)}.</p>
     *
     * @param event Evento publicado por {@code OrderCommandService.placeOrder()}.
     */
    @RabbitListener(queues = RabbitMQConfig.QUEUE_ORDER_PROJECTION_RECEIVED)
    public void onOrderReceived(OrderReceivedEvent event) {
        logger.debug("Projeção ORDER_RECEIVED: orderId={} userId={}",
                event.orderId(), event.userId());

        String orderId = event.orderId().toString();
        String userId  = event.userId().toString();

        // Idempotência: se o documento já existe (normal ou stub lazy), reaproveita-o.
        // Se não existe, cria com todos os dados financeiros.
        OrderDocument doc = orderHistoryRepository.findById(orderId)
                .orElseGet(() -> OrderDocument.createPending(
                        orderId,
                        userId,
                        event.orderType().name(),
                        event.price(),
                        event.amount(),
                        event.occurredOn()
                ));

        // AT-05.1: se o documento foi criado lazily por evento out-of-order (sem dados financeiros),
        // enriquece com os dados do ORDER_RECEIVED. enrich Fields() é idempotente.
        doc.enrichFields(userId, event.orderType().name(), event.price(), event.amount());

        OrderHistoryEntry entry = new OrderHistoryEntry(
                event.eventId().toString(),
                "ORDER_RECEIVED",
                "type=%s price=%s amount=%s".formatted(event.orderType(), event.price(), event.amount()),
                event.occurredOn()
        );

        boolean inserted = doc.appendHistory(entry);
        if (!inserted) {
            logger.debug("Evento duplicado ignorado: eventId={} orderId={}",
                    event.eventId(), orderId);
            return;
        }

        orderHistoryRepository.save(doc);
        logger.info("Read Model criado/enriquecido: orderId={} status={} userId={}", orderId, doc.getStatus(), userId);
    }

    // =========================================================================
    // 2. FundsReservedEvent → appenda FUNDS_RESERVED; status → OPEN
    // =========================================================================

    /**
     * Atualiza o documento para OPEN quando os fundos são confirmados pelo wallet-service.
     *
     * <p>Fanout: esta fila recebe a mesma mensagem que a fila do Command Side
     * ({@code order.events.funds-reserved}), mas de forma independente.</p>
     *
     * @param event Evento publicado pelo wallet-service.
     */
    @RabbitListener(queues = RabbitMQConfig.QUEUE_ORDER_PROJECTION_FUNDS)
    public void onFundsReserved(FundsReservedEvent event) {
        logger.debug("Projeção FUNDS_RESERVED: orderId={} correlationId={}",
                event.orderId(), event.correlationId());

        String orderId = event.orderId().toString();

        // AT-05.1 — Criação Lazy: se o documento não existe (FUNDS_RESERVED chegou antes de
        // ORDER_RECEIVED), cria stub mínimo em vez de lançar IllegalStateException.
        // ORDER_RECEIVED tardio enriquecerá os campos financeiros via enrichFields().
        OrderDocument doc = orderHistoryRepository.findById(orderId)
                .orElseGet(() -> {
                    logger.warn("FUNDS_RESERVED sem documento pai: orderId={} — criando stub lazy (AT-05.1)",
                            orderId);
                    return OrderDocument.createMinimalPending(orderId, event.occurredOn());
                });

        OrderHistoryEntry entry = new OrderHistoryEntry(
                event.eventId().toString(),
                "FUNDS_RESERVED",
                "asset=%s amount=%s walletId=%s".formatted(
                        event.asset(), event.reservedAmount(), event.walletId()),
                event.occurredOn()
        );

        boolean inserted = doc.appendHistory(entry);
        if (!inserted) {
            logger.debug("Evento duplicado ignorado: eventId={}", event.eventId());
            return;
        }

        doc.transitionStatus("OPEN");
        orderHistoryRepository.save(doc);
        logger.info("Read Model atualizado: orderId={} status=OPEN", orderId);
    }

    // =========================================================================
    // 3. MatchExecutedEvent → appenda MATCH_EXECUTED; status → FILLED ou PARTIAL
    // =========================================================================

    /**
     * Atualiza o documento ao cruzar uma ordem com uma contraparte.
     *
     * <p>O evento contém os dois lados do trade ({@code buyOrderId} e {@code sellOrderId}).
     * Este listener atualiza o documento correspondente ao orderId que for encontrado
     * no Mongo (independente de ser buyer ou seller).</p>
     *
     * <p><strong>Determinação de FILLED vs PARTIAL:</strong> decrementa
     * {@code remainingQty} pelo {@code matchAmount}. Se {@code remainingQty <= 0},
     * status → FILLED; senão → PARTIAL.</p>
     *
     * @param event Evento publicado por {@code FundsReservedEventConsumer.handleMatch()}.
     */
    @RabbitListener(queues = RabbitMQConfig.QUEUE_ORDER_PROJECTION_MATCH)
    public void onMatchExecuted(MatchExecutedEvent event) {
        logger.debug("Projeção MATCH_EXECUTED: matchId={} buyOrderId={} sellOrderId={}",
                event.matchId(), event.buyOrderId(), event.sellOrderId());

        // Atualiza o documento do comprador
        updateDocumentWithMatch(event.buyOrderId().toString(), event);
        // Atualiza o documento do vendedor
        updateDocumentWithMatch(event.sellOrderId().toString(), event);
    }

    /**
     * Atualiza o {@link OrderDocument} de um dos lados do match (buyer ou seller).
     *
     * @param orderId ID da ordem a atualizar.
     * @param event   Evento de match com os dados do cruzamento.
     */
    private void updateDocumentWithMatch(String orderId, MatchExecutedEvent event) {
        // AT-05.1 — Criação Lazy: se o documento não existe (MATCH_EXECUTED chegou antes de
        // ORDER_RECEIVED), cria stub mínimo garantindo que o evento seja registrado.
        // Zero return silencioso — critério de aceite AT-05.1.
        OrderDocument doc = orderHistoryRepository.findById(orderId)
                .orElseGet(() -> {
                    logger.warn("MATCH_EXECUTED sem documento no Read Model: orderId={} — criando stub lazy (AT-05.1)",
                            orderId);
                    return OrderDocument.createMinimalPending(orderId, event.occurredOn());
                });

        OrderHistoryEntry entry = new OrderHistoryEntry(
                // Prefixo do orderId garante eventId único por lado quando o mesmo matchId
                // gera 2 updates (buyer + seller) com o mesmo event.eventId()
                event.eventId().toString() + "-" + orderId,
                "MATCH_EXECUTED",
                "matchId=%s price=%s qty=%s".formatted(
                        event.matchId(), event.matchPrice(), event.matchAmount()),
                event.occurredOn()
        );

        boolean inserted = doc.appendHistory(entry);
        if (!inserted) {
            logger.debug("Evento duplicado ignorado: eventId={} orderId={}", event.eventId(), orderId);
            return;
        }

        // AT-05.1: documento lazy pode ter remainingQty=null (sem ORDER_RECEIVED prévio).
        // Assume qty residual=0 como best-effort. ORDER_RECEIVED tardio enriquecerá o doc.
        BigDecimal currentQty = doc.getRemainingQty() != null
                ? doc.getRemainingQty()
                : BigDecimal.ZERO;
        BigDecimal newRemaining = currentQty.subtract(event.matchAmount()).max(BigDecimal.ZERO);
        doc.updateRemainingQty(newRemaining);

        String newStatus = newRemaining.compareTo(BigDecimal.ZERO) <= 0 ? "FILLED" : "PARTIAL";
        doc.transitionStatus(newStatus);

        orderHistoryRepository.save(doc);
        logger.info("Read Model atualizado: orderId={} status={} remaining={}",
                orderId, newStatus, newRemaining);
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
     * @param event Evento publicado por {@code FundsReservedEventConsumer.cancelOrder()}.
     */
    @RabbitListener(queues = RabbitMQConfig.QUEUE_ORDER_PROJECTION_CANCELLED)
    public void onOrderCancelled(OrderCancelledEvent event) {
        logger.debug("Projeção ORDER_CANCELLED: orderId={} reason={}",
                event.orderId(), event.reason());

        String orderId = event.orderId().toString();

        // AT-05.1 — Criação Lazy: se o documento não existe (ORDER_CANCELLED chegou antes
        // de ORDER_RECEIVED), cria stub mínimo para registrar o cancelamento no histórico.
        // Cancelamentos são terminais — melhor registrar do que descartar silenciosamente.
        OrderDocument doc = orderHistoryRepository.findById(orderId)
                .orElseGet(() -> {
                    logger.warn("ORDER_CANCELLED sem documento pai: orderId={} reason={} — criando stub lazy (AT-05.1)",
                            orderId, event.reason());
                    return OrderDocument.createMinimalPending(orderId, event.occurredOn());
                });

        OrderHistoryEntry entry = new OrderHistoryEntry(
                event.eventId().toString(),
                "ORDER_CANCELLED",
                "reason=%s detail=%s".formatted(event.reason(), event.detail()),
                event.occurredOn()
        );

        boolean inserted = doc.appendHistory(entry);
        if (!inserted) {
            logger.debug("Evento duplicado ignorado: eventId={}", event.eventId());
            return;
        }

        doc.transitionStatus("CANCELLED");
        orderHistoryRepository.save(doc);
        logger.info("Read Model atualizado: orderId={} status=CANCELLED reason={}",
                orderId, event.reason());
    }
}
