package com.vibranium.orderservice.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerOnStateTransitionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

/**
 * Configuração do Circuit Breaker Resilience4j para todos os recursos externos (AT-11, BUG-03).
 *
 * <p>Extrai as instâncias nomeadas do {@link CircuitBreakerRegistry} auto-configurado
 * pelo {@code resilience4j-spring-boot3} (via application.yaml) e as expõe como beans
 * Spring para injeção via construtor.</p>
 *
 * <p>Recursos protegidos:</p>
 * <ul>
 *   <li>{@code redisMatchEngine} — Motor de Match Redis (Sorted Set + Lua)</li>
 *   <li>{@code postgresPool} — PostgreSQL (HikariCP connection pool)</li>
 *   <li>{@code mongoEventStore} — MongoDB (Event Store)</li>
 *   <li>{@code rabbitmqPublisher} — RabbitMQ (Outbox publisher)</li>
 * </ul>
 */
@Configuration
public class CircuitBreakerConfig {

    private static final Logger logger = LoggerFactory.getLogger(CircuitBreakerConfig.class);

    private final CircuitBreakerRegistry registry;

    public CircuitBreakerConfig(CircuitBreakerRegistry registry) {
        this.registry = registry;
    }

    /**
     * Registra listeners para logar transições de estado de todos os circuit breakers.
     * Necessário para observabilidade e debugging de recuperação pós-stress test.
     */
    @PostConstruct
    void registerStateTransitionListeners() {
        registry.getAllCircuitBreakers().forEach(cb ->
            cb.getEventPublisher().onStateTransition(this::logTransition)
        );
    }

    private void logTransition(CircuitBreakerOnStateTransitionEvent event) {
        logger.warn("Circuit breaker {} transitioned from {} to {}",
                event.getCircuitBreakerName(),
                event.getStateTransition().getFromState(),
                event.getStateTransition().getToState());
    }

    /**
     * Bean nomeado do Circuit Breaker para o motor de match Redis.
     */
    @Bean
    CircuitBreaker redisMatchEngineCircuitBreaker() {
        return registry.circuitBreaker("redisMatchEngine");
    }

    /**
     * Bean nomeado do Circuit Breaker para PostgreSQL (HikariCP).
     */
    @Bean
    CircuitBreaker postgresPoolCircuitBreaker() {
        return registry.circuitBreaker("postgresPool");
    }

    /**
     * Bean nomeado do Circuit Breaker para MongoDB (Event Store).
     */
    @Bean
    CircuitBreaker mongoEventStoreCircuitBreaker() {
        return registry.circuitBreaker("mongoEventStore");
    }

    /**
     * Bean nomeado do Circuit Breaker para RabbitMQ (Publisher).
     */
    @Bean
    CircuitBreaker rabbitmqPublisherCircuitBreaker() {
        return registry.circuitBreaker("rabbitmqPublisher");
    }
}
