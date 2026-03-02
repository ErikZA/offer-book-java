package com.vibranium.orderservice.adapter.messaging;

import com.rabbitmq.client.Channel;
import com.vibranium.contracts.enums.OrderStatus;
import com.vibranium.contracts.events.wallet.FundsReservationFailedEvent;
import com.vibranium.orderservice.adapter.redis.RedisMatchEngineAdapter;
import com.vibranium.orderservice.config.RabbitMQConfig;
import com.vibranium.orderservice.domain.model.Order;
import com.vibranium.orderservice.domain.model.ProcessedEvent;
import com.vibranium.orderservice.domain.repository.ProcessedEventRepository;
import com.vibranium.orderservice.domain.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Consumidor do evento de falha de reserva de fundos publicado pelo wallet-service.
 *
 * <p>Quando o wallet-service nao consegue bloquear os fundos necessarios
 * (saldo insuficiente, carteira nao encontrada, etc.), publica um
 * {@link FundsReservationFailedEvent}. Este consumidor transiciona
 * a ordem para {@code CANCELLED} e registra o motivo da falha.</p>
 *
 * <p>Esta e a etapa de compensacao da Saga: a ordem sai do estado
 * {@code PENDING} para {@code CANCELLED} sem nunca ter entrado no livro.</p>
 *
 * <p><strong>Estrategia de idempotencia:</strong> INSERT na tabela {@code tb_order_idempotency_keys}
 * com {@code eventId} como PK. Se ja existir ({@link DataIntegrityViolationException}),
 * a mensagem e duplicata e descartada com ACK sem reprocessar.</p>
 *
 * <p><strong>Sequência garantida (AT-04.1):</strong>
 * {@code (1) INSERT idempotency_key -> (2) ZREM Redis -> (3) UPDATE Order -> (4) Commit -> (5) basicAck}</p>
 *
 * <p><strong>Consistência cross-store (AT-04.1):</strong> A remoção do Redis é executada
 * <em>antes</em> do {@code basicAck} para garantir que, mesmo em caso de reinicialização
 * pós-cancelamento-PostgreSQL e pré-ACK, o Redis esteja limpo no reprocessamento.
 * O {@code ZREM} é idempotente: membros inexistentes são ignorados silenciosamente.</p>
 */
@Component
public class FundsReservationFailedEventConsumer {

    private static final Logger logger = LoggerFactory.getLogger(FundsReservationFailedEventConsumer.class);

    private final OrderRepository            orderRepository;
    private final ProcessedEventRepository   processedEventRepository;

    /**
     * Adaptador do Redis Order Book injetado para executar ZREM antes do basicAck (AT-04.1).
     * Garante consistência entre o estado transacional (PostgreSQL CANCELLED) e o
     * livro de ofertas em memória (Redis Sorted Set).
     */
    private final RedisMatchEngineAdapter     redisAdapter;

    /**
     * Cria o consumidor com todas as dependências obrigatórias.
     *
     * @param orderRepository          repositório JPA das ordens.
     * @param processedEventRepository repositório de idempotência por eventId.
     * @param redisAdapter             adaptador do Redis Order Book; usado para
     *                                 executar {@code ZREM} antes do {@code basicAck}
     *                                 garantindo consistência cross-store (AT-04.1).
     */
    public FundsReservationFailedEventConsumer(OrderRepository orderRepository,
                                               ProcessedEventRepository processedEventRepository,
                                               RedisMatchEngineAdapter redisAdapter) {
        this.orderRepository          = orderRepository;
        this.processedEventRepository = processedEventRepository;
        this.redisAdapter             = redisAdapter;
    }

    /**
     * Processa a falha de reserva de fundos com ACK manual e idempotencia por tabela.
     *
     * <p>O {@code containerFactory = "manualAckContainerFactory"} habilita ACK manual.
     * O ACK so e enviado apos o commit JPA, eliminando a janela de duplicacao.</p>
     *
     * @param event       evento de falha publicado pelo wallet-service.
     * @param channel     canal AMQP para envio do ACK/NACK manual.
     * @param deliveryTag tag de entrega fornecida pelo broker.
     * @throws Exception  se o ACK/NACK manual falhar ou ocorrer erro de I/O no canal AMQP;
     *                    o container RabbitMQ trata a exceção e reenvia a mensagem.
     */
    @RabbitListener(
            queues = RabbitMQConfig.QUEUE_FUNDS_FAILED,
            containerFactory = "manualAckContainerFactory"
    )
    @Transactional
    public void onFundsReservationFailed(FundsReservationFailedEvent event,
                                          Channel channel,
                                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws Exception {
        String eventId = event.eventId().toString();
        logger.info("FundsReservationFailedEvent recebido: eventId={} correlationId={} orderId={} reason={}",
                eventId, event.correlationId(), event.orderId(), event.reason());

        // 1. Idempotencia por tabela: INSERT com eventId como PK unica
        //    DataIntegrityViolationException indica duplicata -> descarta com ACK
        try {
            processedEventRepository.saveAndFlush(new ProcessedEvent(event.eventId()));
        } catch (DataIntegrityViolationException ex) {
            logger.info("FundsReservationFailedEvent duplicado (idempotente): eventId={}", eventId);
            channel.basicAck(deliveryTag, false);
            return;
        }

        // 2. Localiza a ordem pelo correlationId da Saga
        Optional<Order> orderOpt = orderRepository.findByCorrelationId(event.correlationId());
        if (orderOpt.isEmpty()) {
            logger.warn("Ordem nao encontrada para correlationId={} -- descartando evento",
                    event.correlationId());
            channel.basicAck(deliveryTag, false);
            return;
        }

        Order order = orderOpt.get();

        // 3. Cancela a ordem com o motivo tecnico da falha
        //    A idempotencia ja e garantida pela PK da tabela acima;
        //    o check de status abaixo e uma defesa extra contra corrupcao de dados.
        if (order.getStatus() == OrderStatus.CANCELLED) {
            logger.debug("Ordem ja cancelada (defensivo): orderId={}", order.getId());
            // AT-04.1: mesmo no caminho defensivo, garante remocao do Redis
            // (idempotente: ZREM em membro inexistente e silencioso)
            redisAdapter.removeFromBook(order.getId(), order.getOrderType());
            channel.basicAck(deliveryTag, false);
            return;
        }

        String reason = event.reason() != null ? event.reason().name() : "UNKNOWN";
        String detail = event.detail() != null
                ? reason + ": " + event.detail()
                : reason;

        // 4. AT-04.1: Remove do Redis Order Book ANTES do basicAck.
        //    Garante que a ordem cancelada nao seja retornada como contraparte pelo
        //    motor de match, evitando MatchExecutedEvent invalido e tentativa de
        //    liquidacao com fundos inexistentes no wallet-service.
        //    Ordem de execucao obrigatoria: ZREM -> UPDATE Postgres -> basicAck
        redisAdapter.removeFromBook(order.getId(), order.getOrderType());

        order.cancel(detail);
        orderRepository.save(order);

        logger.info("Ordem cancelada e removida do Redis: orderId={} correlationId={} reason={}",
                order.getId(), event.correlationId(), reason);

        // 5. ACK manual apos ZREM + commit bem-sucedido do JPA
        channel.basicAck(deliveryTag, false);
    }
}
