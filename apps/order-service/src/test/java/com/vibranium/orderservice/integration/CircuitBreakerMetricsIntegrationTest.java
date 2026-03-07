package com.vibranium.orderservice.integration;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Testes de integração das métricas do Circuit Breaker.
 *
 * <p>Valida que o circuito {@code redisMatchEngine} está registrado no Resilience4j,
 * que suas métricas são expostas via Actuator e que o estado inicial é CLOSED.</p>
 */
@DisplayName("AT-11 — Circuit Breaker Metrics (Testes de Integração)")
class CircuitBreakerMetricsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Test
    @DisplayName("Circuit breaker 'redisMatchEngine' está registrado e fechado por padrão")
    void circuitBreakerShouldBeRegisteredAndClosed() {
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("redisMatchEngine");

        assertThat(cb).isNotNull();
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("Métricas do circuit breaker expostas via /actuator/circuitbreakers")
    void circuitBreakerEndpointShouldReturnStatus() throws Exception {
        mockMvc.perform(get("/actuator/circuitbreakers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.circuitBreakers.redisMatchEngine").exists());
    }

    @Test
    @DisplayName("Métricas Micrometer do circuit breaker disponíveis via /actuator/metrics")
    void circuitBreakerMicrometerMetricsShouldBeAvailable() throws Exception {
        // Verifica que a métrica de estado do circuit breaker existe no Micrometer
        mockMvc.perform(get("/actuator/metrics/resilience4j.circuitbreaker.state"))
                .andExpect(status().isOk());
    }
}
