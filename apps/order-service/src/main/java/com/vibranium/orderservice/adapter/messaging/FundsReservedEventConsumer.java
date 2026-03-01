package com.vibranium.orderservice.adapter.messaging;

import com.rabbitmq.client.Channel;
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
import com.vibranium.orderservice.domain.repository.ProcessedEventRepository;
import com.vibranium.orderservice.domain.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.messaging.handler.annotation.Header;
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
 *   <li>Insere o {@code eventId} em {@code tb_order_idempotency_keys} (idempotencia por tabela).</li>
 *   <li>Localiza a ordem pelo {@code correlationId}.</li>
 *   <li>Executa o Script Lua atomicamente no Redis Sorted Set.</li>
 *   <li>Se houver match - publica {@link MatchExecutedEvent} e atualiza a ordem.</li>
 *   <li>Se nao houver match - publica {@link OrderAddedToBookEvent} (ordem entra no livro).</li>
 *   <li>Se Redis falhar - publica {@link OrderCancelledEvent} (resiliencia).</li>
 *   <li>Apos commit JPA - envia {@code basicAck} manual ao RabbitMQ.</li>
 * </ol>
 *
 * <p><strong>Estrategia de idempotencia:</strong> INSERT na tabela {@code tb_order_idempotency_keys}
 * com {@code eventId} como PK. Se ja existir ({@link DataIntegrityViolationException}),
 * a mensagem e duplicata e descartada com ACK sem reprocessar. Isso resolve a janela
 * de inconsistencia do check por status: a chave e gravada atomicamente com a mudanca
 * de estado, portanto mesmo uma retentativa apos rollback nao passa pelo check.</p>
 *
 * <p><strong>Sequencia garantida:</strong>
 * {@code (1) INSERT idempotency_key -> (2) UPDATE Order -> (3) Commit -> (4) basicAck}</p>
 */
@Component
public class FundsReservedEventConsumer {

    private static final Logger logger = LoggerFactory.getLogger(FundsReservedEventConsumer.class);

    private final OrderRepository            orderRepository;
    private final ProcessedEventRepository   processedEventRepository;
    private final RedisMatchEngineAdapter    matchEngine;
    private final RabbitTemplate             rabbitTemplate;

    public FundsReservedEventConsumer(OrderRepository orderRepository,
                                      ProcessedEventRepository processedEventRepository,
                                      RedisMatchEngineAdapter matchEngine,
                                      RabbitTemplate rabbitTemplate) {
        this.orderRepository         = orderRepository;
        this.processedEventRepository = processedEventRepository;
        this.matchEngine             = matchEngine;
        this.rabbitTemplate          = rabbitTemplate;
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
     * @param event       Evento publicado pelo wallet-service confirmando o bloqueio.
     * @param channel     Canal AMQP para envio do ACK/NACK manual.
     * @param deliveryTag Tag de entrega fornecida pelo broker.
     */
    @RabbitListener(
            queues = RabbitMQConfig.QUEUE_FUNDS_RESERVED,
            concurrency = "5",
            containerFactory = "manualAckContainerFactory"
    )
    @Transactional
    public void onFundsReserved(FundsReservedEvent event,
                                Channel channel,
                                @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws Exception {
        String eventId = event.eventId().toString();
        logger.info("FundsReservedEvent recebido: eventId={} correlationId={} orderId={}",
                eventId, event.correlationId(), event.orderId());

        // 1. Idempotencia por tabela: INSERT com eventId como PK unica
        //    DataIntegrityViolationException indica duplicata -> descarta com ACK
        try {
            processedEventRepository.saveAndFlush(new ProcessedEvent(event.eventId()));
        } catch (DataIntegrityViolationException ex) {
            logger.info("FundsReservedEvent duplicado (idempotente): eventId={}", eventId);
            channel.basicAck(deliveryTag, false);
            return;
        }

        // 2. Localiza a ordem pelo correlationId da Saga
        Optional<Order> orderOpt = orderRepository.findByCorrelationId(event.correlationId());
        if (orderOpt.isEmpty()) {
            logger.warn("Ordem nao encontrada para correlationId={} -- descartando FundsReservedEvent",
                    event.correlationId());
            channel.basicAck(deliveryTag, false);
            return;
        }

        Order order = orderOpt.get();

        // 3. Tenta executar o match no Redis via Lua atomico
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
            // Redis indisponivel ou timeout: cancela a ordem como compensacao
            logger.error("Falha no Redis match engine: orderId={} error={}",
                    order.getId(), e.getMessage());
            cancelOrder(order, FailureReason.INTERNAL_ERROR, "REDIS_UNAVAILABLE: " + e.getMessage());
            // 4. ACK manual apos commit (mesmo em casos de erro tratado)
            channel.basicAck(deliveryTag, false);
            return;
        }

        // 4. Processa o resultado do match
        if (result.matched()) {
            handleMatch(order, result, event);
        } else {
            handleNoMatch(order);
        }

        // 5. ACK manual apos commit bem-sucedido do JPA
        channel.basicAck(deliveryTag, false);
    }

    // =========================================================================
    // Handlers internos
    // =========================================================================

    /**
     * Processa um match executado: atualiza estado da ordem e publica evento.
     *
     * <p>Determina os papeis (comprador/vendedor) baseado no tipo da ordem
     * ingressante e publica o {@link MatchExecutedEvent} para que o
     * wallet-service execute a liquidacao.</p>
     */
    private void handleMatch(Order order, MatchResult result, FundsReservedEvent event) {
        order.applyMatch(result.matchedQty());
        orderRepository.save(order);

        boolean isBuyOrder = order.getOrderType().name().equals("BUY");

        UUID orderUserUUID       = UUID.fromString(order.getUserId());
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

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.EVENTS_EXCHANGE,
                "order.events.match-executed",
                matchEvent
        );

        logger.info("Match executado: correlationId={} orderId={} qty={} fillType={}",
                order.getCorrelationId(), order.getId(), result.matchedQty(), result.fillType());
    }

    /**
     * Processa ausencia de contraparte: transiciona para OPEN e publica evento.
     *
     * <p>A ordem ja foi inserida no Redis Sorted Set pelo Lua script.
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

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.EVENTS_EXCHANGE,
                "order.events.order-cancelled",
                cancelledEvent
        );

        logger.warn("Ordem cancelada: orderId={} reason={} detail={}",
                order.getId(), reason, detail);
    }
}
