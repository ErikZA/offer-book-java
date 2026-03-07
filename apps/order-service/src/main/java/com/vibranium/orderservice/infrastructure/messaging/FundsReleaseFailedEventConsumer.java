package com.vibranium.orderservice.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.vibranium.contracts.enums.FailureReason;
import com.vibranium.contracts.enums.OrderStatus;
import com.vibranium.contracts.events.order.OrderCancelledEvent;
import com.vibranium.contracts.events.wallet.FundsReleaseFailedEvent;
import com.vibranium.orderservice.config.RabbitMQConfig;
import com.vibranium.orderservice.domain.model.Order;
import com.vibranium.orderservice.domain.model.OrderOutboxMessage;
import com.vibranium.orderservice.domain.model.ProcessedEvent;
import com.vibranium.orderservice.domain.repository.OrderOutboxRepository;
import com.vibranium.orderservice.domain.repository.OrderRepository;
import com.vibranium.orderservice.domain.repository.ProcessedEventRepository;
import com.vibranium.orderservice.application.service.EventStoreService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Consumidor do evento de falha de liberação de fundos publicado pelo wallet-service.
 *
 * <p>Quando o wallet-service não consegue executar o {@code ReleaseFundsCommand} — por
 * erro ACID, registro de bloqueio não encontrado, ou violação de idempotência — publica
 * um {@link FundsReleaseFailedEvent}. Este consumidor cancela a ordem e emite alerta
 * operacional, pois fundos permanecem bloqueados indefinidamente.</p>
 *
 * <h3>Ações executadas</h3>
 * <ol>
 *   <li>Verifica idempotência por {@code eventId} na tabela {@code tb_processed_events}.</li>
 *   <li>Localiza a ordem pelo {@code correlationId} da Saga.</li>
 *   <li>Se a ordem já estiver {@code CANCELLED} ou {@code FILLED}, ignora silenciosamente
 *       (log WARN + ACK).</li>
 *   <li>Cancela a ordem com reason {@code RELEASE_FAILED}.</li>
 *   <li>Grava {@link OrderCancelledEvent} no outbox (mesma transação JPA).</li>
 *   <li>Incrementa métrica {@code vibranium.funds.release.failed} via Micrometer Counter.</li>
 *   <li>Envia {@code basicAck} após commit bem-sucedido.</li>
 * </ol>
 *
 * <h3>Por que NÃO tentar re-release?</h3>
 * <p>O {@code ReleaseFundsCommand} já falhou no wallet-service. Reenviar o mesmo comando
 * resultaria no mesmo erro. A compensação correta é cancelar a ordem, alertar a equipe
 * de operações e iniciar reconciliação manual.</p>
 *
 * <p><strong>Sequência garantida:</strong>
 * {@code (1) INSERT idempotency_key → (2) FIND order → (3) UPDATE Order →
 * (4) INSERT OrderCancelledEvent outbox → (5) Commit → (6) basicAck}</p>
 */
@Component
public class FundsReleaseFailedEventConsumer {

    private static final Logger logger = LoggerFactory.getLogger(FundsReleaseFailedEventConsumer.class);

    private final OrderRepository            orderRepository;
    private final OrderOutboxRepository      outboxRepository;
    private final ProcessedEventRepository   processedEventRepository;
    private final ObjectMapper               objectMapper;
    private final MeterRegistry              meterRegistry;
    private final EventStoreService          eventStoreService;

    /**
     * Cria o consumidor com todas as dependências obrigatórias via injeção por construtor.
     *
     * @param orderRepository          repositório JPA das ordens.
     * @param outboxRepository         repositório JPA do outbox — usado para gravar
     *                                 {@code OrderCancelledEvent} na mesma transação.
     * @param processedEventRepository repositório de idempotência por {@code eventId}.
     * @param objectMapper             serializador Jackson para serializar o
     *                                 {@code OrderCancelledEvent} no payload do outbox.
     * @param meterRegistry            registry Micrometer para emissão da métrica
     *                                 {@code vibranium.funds.release.failed}.
     */
    public FundsReleaseFailedEventConsumer(OrderRepository orderRepository,
                                           OrderOutboxRepository outboxRepository,
                                           ProcessedEventRepository processedEventRepository,
                                           ObjectMapper objectMapper,
                                           MeterRegistry meterRegistry,
                                           EventStoreService eventStoreService) {
        this.orderRepository          = orderRepository;
        this.outboxRepository         = outboxRepository;
        this.processedEventRepository = processedEventRepository;
        this.objectMapper             = objectMapper;
        this.meterRegistry            = meterRegistry;
        this.eventStoreService        = eventStoreService;
    }

    /**
     * Processa a falha de liberação de fundos com ACK manual e idempotência por tabela.
     *
     * <p>O {@code containerFactory = "manualAckContainerFactory"} habilita ACK manual.
     * O ACK só é enviado após o commit JPA, eliminando a janela de duplicação.</p>
     *
     * @param event       evento de falha publicado pelo wallet-service.
     * @param channel     canal AMQP para envio do ACK/NACK manual.
     * @param deliveryTag tag de entrega fornecida pelo broker.
     * @throws Exception  se o ACK/NACK manual falhar ou ocorrer erro de I/O no canal AMQP;
     *                    o container RabbitMQ trata a exceção e reenvia a mensagem.
     */
    @RabbitListener(
            queues = RabbitMQConfig.QUEUE_FUNDS_RELEASE_FAILED,
            containerFactory = "manualAckContainerFactory"
    )
    @Transactional
    public void onFundsReleaseFailed(FundsReleaseFailedEvent event,
                                     Channel channel,
                                     @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws Exception {
        String eventId = event.eventId().toString();

        // MDC garante rastreabilidade em todas as linhas de log desta execução (AT-14.2).
        try (var ignoredCorr = MDC.putCloseable("correlationId", event.correlationId().toString())) {
            MDC.put("orderId", event.orderId().toString());
            try {
                logger.error("[CRITICAL] FundsReleaseFailedEvent recebido — fundos permanecem bloqueados: " +
                        "eventId={} correlationId={} orderId={} walletId={} amount=N/A reason={} detail={}",
                        eventId, event.correlationId(), event.orderId(),
                        event.aggregateId(), event.reason(), event.detail());

                // 1. Idempotência por tabela: INSERT com eventId como PK única.
                //    DataIntegrityViolationException indica duplicata → descarta com ACK.
                try {
                    processedEventRepository.saveAndFlush(new ProcessedEvent(event.eventId()));
                } catch (DataIntegrityViolationException ex) {
                    logger.info("FundsReleaseFailedEvent duplicado (idempotente): eventId={}", eventId);
                    channel.basicAck(deliveryTag, false);
                    return;
                }

                // 2. Localiza a ordem pelo correlationId da Saga.
                Optional<Order> orderOpt = orderRepository.findByCorrelationId(event.correlationId());
                if (orderOpt.isEmpty()) {
                    logger.warn("Ordem não encontrada para correlationId={} — descartando FundsReleaseFailedEvent",
                            event.correlationId());
                    channel.basicAck(deliveryTag, false);
                    return;
                }

                Order order = orderOpt.get();

                // 3. Se a ordem já estiver CANCELLED ou FILLED, ignorar silenciosamente.
                //    Ordem FILLED: liquidação já ocorreu — não pode ser revertida.
                //    Ordem CANCELLED: já processada por outro consumer de compensação.
                if (order.getStatus() == OrderStatus.CANCELLED || order.getStatus() == OrderStatus.FILLED) {
                    logger.warn("Ordem já em estado terminal ({}): orderId={} — ignorando FundsReleaseFailedEvent",
                            order.getStatus(), order.getId());
                    channel.basicAck(deliveryTag, false);
                    return;
                }

                // 4. Cancela a ordem com reason RELEASE_FAILED
                order.cancel("RELEASE_FAILED");
                orderRepository.save(order);

                // 5. Grava OrderCancelledEvent no outbox (mesma transação JPA)
                emitOrderCancelledEvent(order, event);

                // 6. Incrementa métrica Micrometer para alertas (Grafana/OpsGenie)
                String reason = event.reason() != null ? event.reason().name() : "UNKNOWN";
                Counter.builder("vibranium.funds.release.failed")
                        .tag("reason", reason)
                        .tag("asset", "VIBRANIUM")
                        .register(meterRegistry)
                        .increment();

                logger.error("[CRITICAL] Ordem cancelada por falha de liberação de fundos: " +
                        "orderId={} correlationId={} reason=RELEASE_FAILED detail={}",
                        order.getId(), event.correlationId(), event.detail());

                // 7. ACK manual após commit JPA bem-sucedido
                channel.basicAck(deliveryTag, false);

            } finally {
                // Remove orderId explicitamente; correlationId é removido pelo try-with-resources.
                MDC.remove("orderId");
            }
        }
    }

    /**
     * Serializa um {@link OrderCancelledEvent} e persiste no outbox dentro da
     * transação JPA corrente.
     *
     * @param order Ordem cancelada.
     * @param event Evento original de falha de release.
     * @throws IllegalStateException se a serialização Jackson falhar (não-recuperável).
     */
    private void emitOrderCancelledEvent(Order order, FundsReleaseFailedEvent event) {
        FailureReason reason = event.reason() != null ? event.reason() : FailureReason.INTERNAL_ERROR;
        String detail = event.detail() != null
                ? "RELEASE_FAILED: " + event.detail()
                : "RELEASE_FAILED";

        OrderCancelledEvent cancelledEvent = OrderCancelledEvent.of(
                order.getCorrelationId(),
                order.getId(),
                reason,
                detail
        );

        String json;
        try {
            json = objectMapper.writeValueAsString(cancelledEvent);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Falha ao serializar OrderCancelledEvent para outbox (orderId=%s): %s"
                            .formatted(order.getId(), e.getMessage()), e);
        }

        outboxRepository.save(new OrderOutboxMessage(
                order.getId(),
                "Order",
                "OrderCancelledEvent",
                RabbitMQConfig.EVENTS_EXCHANGE,
                RabbitMQConfig.RK_ORDER_CANCELLED,
                json
        ));

        // AT-14: grava o evento também no Event Store imutável (mesma TX)
        eventStoreService.append(
                cancelledEvent.eventId(),
                order.getId().toString(),
                "Order",
                "OrderCancelledEvent",
                json,
                cancelledEvent.occurredOn(),
                order.getCorrelationId(),
                cancelledEvent.schemaVersion()
        );
    }
}
