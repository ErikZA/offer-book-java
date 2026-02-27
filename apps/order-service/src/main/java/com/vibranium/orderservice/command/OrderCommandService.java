package com.vibranium.orderservice.command;

import com.vibranium.contracts.events.OrderCreatedEvent;
import com.vibranium.orderservice.domain.Order;
import com.vibranium.orderservice.matching.OrderMatchingEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Service de Commands (Write side do CQRS)
 */
@Slf4j
@Service
public class OrderCommandService {

    private final OrderCommandRepository commandRepository;
    private final OrderMatchingEngine matchingEngine;
    private final RabbitTemplate rabbitTemplate;

    @Autowired
    public OrderCommandService(
            OrderCommandRepository commandRepository,
            OrderMatchingEngine matchingEngine,
            RabbitTemplate rabbitTemplate) {
        this.commandRepository = commandRepository;
        this.matchingEngine = matchingEngine;
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * Cria uma nova ordem e a adiciona ao matching engine
     */
    @Transactional
    public String createOrder(CreateOrderCommand command) {
        // Validação básica
        if (!isValidSide(command.getSide())) {
            throw new IllegalArgumentException("Side deve ser BUY ou SELL");
        }

        // Criar entidade de ordem
        Order order = Order.builder()
                .id(UUID.randomUUID().toString())
                .userId(command.getUserId())
                .symbol(command.getSymbol())
                .side(command.getSide())
                .quantity(command.getQuantity())
                .price(command.getPrice())
                .orderType(command.getOrderType())
                .status("PENDING")
                .filledQuantity(command.getQuantity().ZERO)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .correlationId(command.getCorrelationId() != null ? command.getCorrelationId() : UUID.randomUUID().toString())
                .build();

        // Persistir em MongoDB
        Order savedOrder = commandRepository.save(order);
        log.info("Ordem {} criada para usuário {}", savedOrder.getId(), command.getUserId());

        // Adicionar ao matching engine (Redis)
        matchingEngine.matchOrder(
                savedOrder.getId(),
                command.getSymbol(),
                command.getSide(),
                command.getPrice(),
                command.getQuantity()
        );

        // Publicar evento (event-driven)
        publishOrderCreatedEvent(savedOrder);

        return savedOrder.getId();
    }

    /**
     * Publica evento de ordem criada via RabbitMQ
     */
    private void publishOrderCreatedEvent(Order order) {
        OrderCreatedEvent event = OrderCreatedEvent.builder()
                .orderId(order.getId())
                .userId(order.getUserId())
                .symbol(order.getSymbol())
                .side(order.getSide())
                .quantity(order.getQuantity())
                .price(order.getPrice())
                .orderType(order.getOrderType())
                .totalAmount(order.getQuantity().multiply(order.getPrice()))
                .correlationId(order.getCorrelationId())
                .build();

        rabbitTemplate.convertAndSend("vibranium.orders", "orders.created", event);
        log.info("Evento OrderCreatedEvent publicado para ordem {}", order.getId());
    }

    private boolean isValidSide(String side) {
        return "BUY".equals(side) || "SELL".equals(side);
    }

}
