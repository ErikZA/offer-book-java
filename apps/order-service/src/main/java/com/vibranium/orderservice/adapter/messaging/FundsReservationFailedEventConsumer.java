package com.vibranium.orderservice.adapter.messaging;

import com.vibranium.contracts.enums.OrderStatus;
import com.vibranium.contracts.events.wallet.FundsReservationFailedEvent;
import com.vibranium.orderservice.config.RabbitMQConfig;
import com.vibranium.orderservice.domain.model.Order;
import com.vibranium.orderservice.domain.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Consumidor do evento de falha de reserva de fundos publicado pelo wallet-service.
 *
 * <p>Quando o wallet-service não consegue bloquear os fundos necessários
 * (saldo insuficiente, carteira não encontrada, etc.), publica um
 * {@link FundsReservationFailedEvent}. Este consumidor transiciona
 * a ordem para {@code CANCELLED} e registra o motivo da falha.</p>
 *
 * <p>Esta é a etapa de compensação da Saga: a ordem sai do estado
 * {@code PENDING} para {@code CANCELLED} sem nunca ter entrado no livro.</p>
 */
@Component
public class FundsReservationFailedEventConsumer {

    private static final Logger logger = LoggerFactory.getLogger(FundsReservationFailedEventConsumer.class);

    private final OrderRepository orderRepository;

    public FundsReservationFailedEventConsumer(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    /**
     * Processa a falha de reserva de fundos e cancela a ordem correspondente.
     *
     * <p>Idempotente: se a ordem já estiver {@code CANCELLED}, o evento é ignorado.
     * Isso protege contra redelivery do RabbitMQ em caso de falha de ACK.</p>
     *
     * @param event Evento de falha publicado pelo wallet-service.
     */
    @RabbitListener(queues = RabbitMQConfig.QUEUE_FUNDS_FAILED)
    @Transactional
    public void onFundsReservationFailed(FundsReservationFailedEvent event) {
        logger.info("Reserva de fundos falhou: correlationId={} orderId={} reason={}",
                event.correlationId(), event.orderId(), event.reason());

        Optional<Order> orderOpt = orderRepository.findByCorrelationId(event.correlationId());
        if (orderOpt.isEmpty()) {
            logger.warn("Ordem não encontrada para correlationId={} — descartando evento",
                    event.correlationId());
            return;
        }

        Order order = orderOpt.get();

        // Idempotência: não cancela duas vezes
        if (order.getStatus() == OrderStatus.CANCELLED) {
            logger.debug("Ordem já cancelada (idempotente): orderId={}", order.getId());
            return;
        }

        // Cancela a ordem com o motivo técnico da falha
        String reason = event.reason() != null ? event.reason().name() : "UNKNOWN";
        String detail = event.detail() != null
                ? reason + ": " + event.detail()
                : reason;

        order.cancel(detail);
        orderRepository.save(order);

        logger.info("Ordem cancelada: orderId={} correlationId={} reason={}",
                order.getId(), event.correlationId(), reason);
    }
}
