package com.vibranium.orderservice.adapter.messaging;

import com.vibranium.contracts.enums.FailureReason;
import com.vibranium.contracts.enums.OrderStatus;
import com.vibranium.contracts.events.order.MatchExecutedEvent;
import com.vibranium.contracts.events.order.OrderAddedToBookEvent;
import com.vibranium.contracts.events.order.OrderCancelledEvent;
import com.vibranium.contracts.events.wallet.FundsReservedEvent;
import com.vibranium.orderservice.adapter.redis.RedisMatchEngineAdapter;
import com.vibranium.orderservice.adapter.redis.RedisMatchEngineAdapter.MatchResult;
import com.vibranium.orderservice.config.RabbitMQConfig;
import com.vibranium.orderservice.domain.model.Order;
import com.vibranium.orderservice.domain.model.ProcessedEvent;
import com.vibranium.orderservice.domain.repository.OrderRepository;
import com.vibranium.orderservice.domain.repository.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Consumidor do evento que confirma o bloqueio de fundos na carteira.
 *
 * <p>Quando o wallet-service bloqueia os fundos com sucesso, publica um
 * {@link FundsReservedEvent}. Este consumidor recebe esse evento e
 * executa o Motor de Match no Redis:</p>
 * <ol>
 *   <li>Guarda de idempotência por {@code eventId} — descarta re-entregas do broker.</li>
 *   <li>Localiza a ordem pelo {@code correlationId}.</li>
 *   <li>Executa o Script Lua atomicamente no Redis Sorted Set.</li>
 *   <li>Se houver match → publica {@link MatchExecutedEvent} e atualiza a ordem.</li>
 *   <li>Se não houver match → publica {@link OrderAddedToBookEvent} (ordem entra no livro).</li>
 *   <li>Se Redis falhar → publica {@link OrderCancelledEvent} (resiliência).</li>
 * </ol>
 *
 * <p>O processamento é idempotente: se a ordem já estiver {@code OPEN} ou além,
 * o evento duplicado é descartado sem efeito colateral.</p>
 */
@Component
public class FundsReservedEventConsumer {

    private static final Logger logger = LoggerFactory.getLogger(FundsReservedEventConsumer.class);

    private final OrderRepository         orderRepository;
    private final RedisMatchEngineAdapter matchEngine;
    private final RabbitTemplate          rabbitTemplate;
    private final ProcessedEventRepository processedEventRepository;

    public FundsReservedEventConsumer(OrderRepository orderRepository,
                                      RedisMatchEngineAdapter matchEngine,
                                      RabbitTemplate rabbitTemplate,
                                      ProcessedEventRepository processedEventRepository) {
        this.orderRepository          = orderRepository;
        this.matchEngine              = matchEngine;
        this.rabbitTemplate           = rabbitTemplate;
        this.processedEventRepository = processedEventRepository;
    }

    /**
     * Recebe o evento de fundos reservados e tenta executar o match.
     *
     * <p>Concurrency {@code 5} no listener permite processar múltiplos eventos
     * em paralelo, aumentando o throughput para o cenário de 500 ordens
     * simultâneas. Cada consumer tem sua própria transação JPA (@Transactional).</p>
     *
     * @param event Evento publicado pelo wallet-service confirmando o bloqueio.
     */
    @RabbitListener(queues = RabbitMQConfig.QUEUE_FUNDS_RESERVED, concurrency = "5")
    @Transactional
    public void onFundsReserved(FundsReservedEvent event) {
        logger.info("Fundos reservados — iniciando match: correlationId={} orderId={}",
                event.correlationId(), event.orderId());

        // 0. Idempotência por eventId: at-least-once delivery do RabbitMQ pode re-entregar.
        //    Tentamos inserir o eventId na tabela de processed_events; se a PK já existir,
        //    o banco lança DataIntegrityViolationException e descartamos a mensagem.
        try {
            processedEventRepository.saveAndFlush(new ProcessedEvent(event.eventId()));
        } catch (DataIntegrityViolationException duplicate) {
            logger.info("FundsReservedEvent já processado — descartando re-entrega: eventId={} correlationId={}",
                    event.eventId(), event.correlationId());
            return;
        }

        // 1. Localiza a ordem pelo correlationId da Saga
        Optional<Order> orderOpt = orderRepository.findByCorrelationId(event.correlationId());
        if (orderOpt.isEmpty()) {
            logger.warn("Ordem não encontrada para correlationId={} — descartando FundsReservedEvent",
                    event.correlationId());
            return;
        }

        Order order = orderOpt.get();

        // 2. Idempotência: se já foi processado, ignora
        if (order.getStatus() != OrderStatus.PENDING) {
            logger.debug("Ordem já processada (idempotente): orderId={} status={}",
                    order.getId(), order.getStatus());
            return;
        }

        // 3. Tenta executar o match no Redis via Lua atômico
        MatchResult result;
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
        } catch (Exception e) {
            // Redis indisponível ou timeout: cancela a ordem como compensação
            logger.error("Falha no Redis match engine: orderId={} error={}",
                    order.getId(), e.getMessage());
            cancelOrder(order, FailureReason.INTERNAL_ERROR, "REDIS_UNAVAILABLE: " + e.getMessage());
            return;
        }

        // 4. Processa o resultado do match
        if (result.matched()) {
            handleMatch(order, result, event);
        } else {
            handleNoMatch(order);
        }
    }

    // =========================================================================
    // Handlers internos
    // =========================================================================

    /**
     * Processa um match executado: atualiza estado da ordem e publica evento.
     *
     * <p>Determina os papéis (comprador/vendedor) baseado no tipo da ordem
     * ingressante e publica o {@link MatchExecutedEvent} para que o
     * wallet-service execute a liquidação.</p>
     */
    private void handleMatch(Order order, MatchResult result, FundsReservedEvent event) {
        // Atualiza quantidade remaining e status da ordem
        order.applyMatch(result.matchedQty());
        orderRepository.save(order);

        // Determina os lados do trade
        boolean isBuyOrder = order.getOrderType().name().equals("BUY");

        // userId é armazenado como String (keycloakId); MatchExecutedEvent requer UUID.
        // O keycloakId do Keycloak é sempre um UUID v4 válido.
        UUID orderUserUUID       = UUID.fromString(order.getUserId());
        UUID counterpartUserUUID = UUID.fromString(result.counterpartUserId());

        MatchExecutedEvent matchEvent = MatchExecutedEvent.of(
                order.getCorrelationId(),
                isBuyOrder ? order.getId()                : result.counterpartId(),   // buyOrderId
                isBuyOrder ? result.counterpartId()       : order.getId(),            // sellOrderId
                isBuyOrder ? orderUserUUID                : counterpartUserUUID,      // buyerUserId
                isBuyOrder ? counterpartUserUUID          : orderUserUUID,            // sellerUserId
                isBuyOrder ? order.getWalletId()          : result.counterpartWalletId(),
                isBuyOrder ? result.counterpartWalletId() : order.getWalletId(),
                order.getPrice(),
                result.matchedQty()
        );

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.EVENTS_EXCHANGE,
                "order.events.match-executed",
                matchEvent
        );

        logger.info("Match executado: correlationId={} orderId={} qty={} fillType={}",
                order.getCorrelationId(), order.getId(), result.matchedQty(), result.fillType());

        // Log informativo para partial fills — o Lua já reinseriu o residual atomicamente.
        // Para disaster recovery via requeueResidual() consulte RedisMatchEngineAdapter.
        if ("PARTIAL_ASK".equals(result.fillType()) || "PARTIAL_BID".equals(result.fillType())) {
            logger.info("Partial fill detectado: fillType={} counterpartId={} remainingCounterpartQty={} "
                    + "— residual gerenciado atomicamente pelo Lua script.",
                    result.fillType(), result.counterpartId(), result.remainingCounterpartQty());
        }
    }

    /**
     * Processa ausência de contraparte: transiciona para OPEN e publica evento.
     *
     * <p>A ordem já foi inserida no Redis Sorted Set pelo Lua script.
     * Aqui apenas atualizamos o estado no PostgreSQL e notificamos.</p>
     */
    private void handleNoMatch(Order order) {
        order.transitionTo(OrderStatus.OPEN);
        orderRepository.save(order);

        OrderAddedToBookEvent addedEvent = OrderAddedToBookEvent.of(
                order.getCorrelationId(),
                order.getId(),
                order.getOrderType(),
                order.getPrice(),
                order.getRemainingAmount()
        );

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.EVENTS_EXCHANGE,
                "order.events.order-added-to-book",
                addedEvent
        );

        logger.info("Ordem adicionada ao livro: orderId={} type={} price={}",
                order.getId(), order.getOrderType(), order.getPrice());
    }

    /**
     * Cancela a ordem e publica o evento de cancelamento para auditoria.
     *
     * @param order   Ordem a ser cancelada.
     * @param reason  Razão padronizada.
     * @param detail  Detalhe técnico.
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

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.EVENTS_EXCHANGE,
                "order.events.order-cancelled",
                cancelledEvent
        );

        logger.warn("Ordem cancelada: orderId={} reason={} detail={}",
                order.getId(), reason, detail);
    }
}
