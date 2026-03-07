package com.vibranium.orderservice.integration;

import com.vibranium.contracts.enums.AssetType;
import com.vibranium.contracts.enums.OrderStatus;
import com.vibranium.contracts.enums.OrderType;
import com.vibranium.contracts.events.wallet.FundsReservedEvent;
import com.vibranium.orderservice.domain.model.Order;
import com.vibranium.orderservice.domain.repository.OrderRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Testes de integração de degradação graciosa com Circuit Breaker.
 *
 * <p>Valida que quando o circuit breaker está aberto (Redis indisponível),
 * ordens são canceladas com reason contendo "REDIS_UNAVAILABLE" e que quando
 * o circuito fecha (Redis disponível), ordens voltam a ser processadas.</p>
 */
@DisplayName("AT-11 — Graceful Degradation (Testes de Integração)")
class GracefulDegradationIntegrationTest extends AbstractIntegrationTest {

    private static final String FUNDS_RESERVED_RK = "wallet.events.funds-reserved";

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        circuitBreaker = circuitBreakerRegistry.circuitBreaker("redisMatchEngine");
        // Reseta o circuit breaker para estado CLOSED antes de cada teste
        circuitBreaker.reset();
    }

    @Test
    @DisplayName("Circuit breaker OPEN → ordens canceladas com 'REDIS_UNAVAILABLE'")
    void shouldCancelOrderWhenCircuitBreakerIsOpen() {
        // Força o circuit breaker para OPEN
        circuitBreaker.transitionToOpenState();

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Cria ordem PENDING e publica FundsReservedEvent
        UUID orderId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();
        String userId = UUID.randomUUID().toString();

        Order order = Order.create(
                orderId, correlationId, userId, walletId,
                OrderType.BUY, BigDecimal.valueOf(100), BigDecimal.ONE
        );
        orderRepository.saveAndFlush(order);

        FundsReservedEvent event = FundsReservedEvent.of(
                correlationId, orderId, walletId, AssetType.BRL,
                BigDecimal.valueOf(100)
        );

        rabbitTemplate.convertAndSend("vibranium.events", FUNDS_RESERVED_RK, event);

        // Aguarda a ordem ser cancelada
        await().atMost(10, SECONDS).untilAsserted(() -> {
            Order updated = orderRepository.findById(orderId).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(updated.getCancellationReason()).contains("REDIS_UNAVAILABLE");
        });
    }

    @Test
    @DisplayName("Circuit breaker CLOSED → ordens processadas normalmente no Redis")
    void shouldProcessOrderWhenCircuitBreakerIsClosed() {
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        // Cria ordem PENDING e publica FundsReservedEvent
        UUID orderId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();
        String userId = UUID.randomUUID().toString();

        Order order = Order.create(
                orderId, correlationId, userId, walletId,
                OrderType.BUY, BigDecimal.valueOf(50000), BigDecimal.ONE
        );
        orderRepository.saveAndFlush(order);

        FundsReservedEvent event = FundsReservedEvent.of(
                correlationId, orderId, walletId, AssetType.BRL,
                BigDecimal.valueOf(50000)
        );

        rabbitTemplate.convertAndSend("vibranium.events", FUNDS_RESERVED_RK, event);

        // Aguarda a ordem ser processada (OPEN = adicionada ao livro sem contraparte)
        await().atMost(10, SECONDS).untilAsserted(() -> {
            Order updated = orderRepository.findById(orderId).orElseThrow();
            assertThat(updated.getStatus()).isIn(OrderStatus.OPEN, OrderStatus.FILLED, OrderStatus.PARTIAL);
        });

        // Circuit breaker ainda deve estar fechado
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
