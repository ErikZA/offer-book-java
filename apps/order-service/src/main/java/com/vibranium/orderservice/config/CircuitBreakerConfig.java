package com.vibranium.orderservice.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuração do Circuit Breaker Resilience4j para o Motor de Match Redis (AT-11).
 *
 * <p>Extrai a instância nomeada {@code redisMatchEngine} do {@link CircuitBreakerRegistry}
 * auto-configurado pelo {@code resilience4j-spring-boot3} (via application.yaml) e a
 * expõe como bean Spring para injeção via construtor no {@link
 * com.vibranium.orderservice.infrastructure.redis.RedisMatchEngineAdapter}.</p>
 *
 * <p>O registry é populado automaticamente pela auto-configuração do Resilience4j
 * com base na seção {@code resilience4j.circuitbreaker.instances} do YAML.
 * As métricas Micrometer são integradas automaticamente via {@code resilience4j-micrometer}.</p>
 */
@Configuration
public class CircuitBreakerConfig {

    /**
     * Bean nomeado do Circuit Breaker para o motor de match Redis.
     *
     * @param registry Registry auto-configurado pelo Resilience4j Spring Boot starter.
     * @return Instância configurada do circuit breaker {@code redisMatchEngine}.
     */
    @Bean
    CircuitBreaker redisMatchEngineCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("redisMatchEngine");
    }
}
