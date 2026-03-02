package com.vibranium.orderservice.adapter.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.vibranium.contracts.enums.FailureReason;
import com.vibranium.contracts.enums.OrderStatus;
import com.vibranium.contracts.events.order.MatchExecutedEvent;
import com.vibranium.contracts.events.order.OrderAddedToBookEvent;
import com.vibranium.contracts.events.order.OrderCancelledEvent;
import com.vibranium.contracts.events.order.OrderFilledEvent;
import com.vibranium.contracts.events.order.OrderPartiallyFilledEvent;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
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
 * executa o Motor de Match no Redis e persiste os eventos no {@code tb_order_outbox}:</p>
 * <ol>
 *   <li>Insere o {@code eventId} em {@code tb_order_idempotency_keys} (idempotencia por tabela).</li>
 *   <li>Localiza a ordem pelo {@code correlationId}.</li>
 *   <li>Executa o Script Lua atomicamente no Redis Sorted Set.</li>
 *   <li>Se houver match:
 *     <ul>
 *       <li>Grava {@link MatchExecutedEvent} no Outbox (evento de infraestrutura/projecao MongoDB).</li>
 *       <li>Grava {@link OrderFilledEvent} ou {@link OrderPartiallyFilledEvent} no Outbox
 *           (Domain Event explícito da transicao de status — AT-15.1).</li>
 *       <li>Atualiza o status da ordem para {@code FILLED} ou {@code PARTIAL}.</li>
 *     </ul>
 *   </li>
 *   <li>Se nao houver match - grava {@link OrderAddedToBookEvent} no Outbox (ordem entra no livro).</li>
 *   <li>Se Redis falhar - grava {@link OrderCancelledEvent} no Outbox (resiliencia).</li>
 *   <li>Apos commit JPA - envia {@code basicAck} manual ao RabbitMQ.</li>
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
 * <p><strong>Sequencia garantida (match):</strong>
 * {@code (1) INSERT idempotency_key -> (2) UPDATE Order -> (3) INSERT MatchExecutedEvent outbox
 * -> (4) INSERT FillEvent outbox -> (5) Commit -> (6) basicAck}</p>
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

    public FundsReservedEventConsumer(OrderRepository orderRepository,
                                      ProcessedEventRepository processedEventRepository,
                                      RedisMatchEngineAdapter matchEngine,
                                      OrderOutboxRepository outboxRepository,
                                      ObjectMapper objectMapper) {
        this.orderRepository          = orderRepository;
        this.processedEventRepository = processedEventRepository;
        this.matchEngine              = matchEngine;
        this.outboxRepository         = outboxRepository;
        this.objectMapper             = objectMapper;
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
     * Processa um match executado: atualiza estado da ordem e grava {@link MatchExecutedEvent}
     * no Outbox dentro da mesma transação.
     *
     * <p>Determina os papeis (comprador/vendedor) baseado no tipo da ordem ingressante.
     * A publicação real ao broker é feita de forma assíncrona pelo
     * {@link com.vibranium.orderservice.application.service.OrderOutboxPublisherService},
     * garantindo atomicidade entre a atualização do estado da ordem e o registro do evento.</p>
     *
     * <p>[AT-15.1] Além do {@link MatchExecutedEvent}, publica o Domain Event de preenchimento
     * explícito ({@link OrderFilledEvent} ou {@link OrderPartiallyFilledEvent}) no mesmo outbox,
     * na mesma transação. Isso elimina a divergência contrato ↔ implementação e permite que
     * sistemas downstream reajam à transição de status sem inferir via MatchExecutedEvent.</p>
     */
    private void handleMatch(Order order, MatchResult result, FundsReservedEvent event) {
        // 1. Um match imediato significa que a ordem foi a mercado e encontrou contraparte.
        //    A ordem ingressante ainda está em PENDING (FundsReserved acabou de chegar).
        //    Transitamos para OPEN antes de applyMatch(), que exige OPEN ou PARTIAL.
        //    Semanticamente: a ordem ficou OPEN por um instante e imediatamente executou.
        order.markAsOpen();
        // 2. Aplica o match — transiciona para FILLED ou PARTIAL conforme qty executada
        order.applyMatch(result.matchedQty());
        // 3. Persiste a ordem atualizada — primeira operação da unidade de trabalho
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

        // 4. Grava MatchExecutedEvent no Outbox — infraestrutura/projeção MongoDB.
        saveToOutbox(
                order.getId(),
                "MatchExecutedEvent",
                RabbitMQConfig.EVENTS_EXCHANGE,
                RabbitMQConfig.RK_MATCH_EXECUTED,
                matchEvent
        );

        // 5. [AT-15.1] Publica Domain Event explícito de preenchimento, baseado na transição
        //    de status produzida por applyMatch().
        //    Regra: somente FILLED ou PARTIAL geram evento — nenhum outro status é possível
        //    após applyMatch(), mas o guard explícito previne regressão.
        //
        //    Ambos os saves ocorrem NA MESMA TRANSAÇÃO que atualizou a Order acima,
        //    garantindo atomicidade total via Outbox Pattern.
        if (order.getStatus() == OrderStatus.FILLED) {
            // Ordem totalmente executada: informa quantidade total e preço de execução.
            // Preço médio = order.getPrice() (match único; extensão futura pode calcular VWAP).
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
            // Ordem parcialmente executada: informa quanto foi executado neste match
            // e quanto ainda resta no livro. matchId = counterpartId (contraparte deste match).
            saveToOutbox(
                    order.getId(),
                    "OrderPartiallyFilledEvent",
                    RabbitMQConfig.EVENTS_EXCHANGE,
                    RabbitMQConfig.RK_ORDER_PARTIALLY_FILLED,
                    OrderPartiallyFilledEvent.of(
                            order.getCorrelationId(),
                            order.getId(),
                            result.counterpartId(),       // matchId = ID da ordem contraparte
                            result.matchedQty(),          // filledAmount = executado neste match
                            order.getRemainingAmount()    // remainingAmount = saldo restante
                    )
            );
        }

        logger.info("Match executado (outbox): correlationId={} orderId={} qty={} fillType={}",
                order.getCorrelationId(), order.getId(), result.matchedQty(), result.fillType());
    }

    /**
     * Processa ausencia de contraparte: transiciona para OPEN e grava {@link OrderAddedToBookEvent}
     * no Outbox dentro da mesma transação.
     *
     * <p>A ordem ja foi inserida no Redis Sorted Set pelo Lua script.
     * Utiliza {@link Order#markAsOpen()} que valida internamente que a ordem
     * está em {@code PENDING} antes de transicionar — garantindo que qualquer
     * race condition neste ponto gere uma exceção rastreável.</p>
     */
    private void handleNoMatch(Order order) {
        order.markAsOpen();
        orderRepository.save(order);

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
    }
}
