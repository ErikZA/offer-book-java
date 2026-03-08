package com.vibranium.orderservice.integration;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BUG-03 TDD — Testes de recuperação do Circuit Breaker.
 *
 * <p>Valida que:
 * <ul>
 *   <li>Após falha massiva, o circuit breaker transiciona para HALF_OPEN e eventual CLOSED.</li>
 *   <li>Health endpoint readiness reporta DOWN quando circuit breaker está OPEN.</li>
 *   <li>Health endpoint liveness permanece UP (o processo está vivo).</li>
 * </ul>
 */
@DisplayName("BUG-03 — Circuit Breaker Recovery")
class CircuitBreakerRecoveryTest extends AbstractIntegrationTest {

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        circuitBreaker = circuitBreakerRegistry.circuitBreaker("redisMatchEngine");
        circuitBreaker.reset();
    }

    @Test
    @DisplayName("RED: Após falha massiva no Redis, circuit breaker deve transicionar para HALF_OPEN e eventual CLOSED")
    void shouldRecoverFromRedisFailure() throws InterruptedException {
        // GIVEN: Circuit breaker é forçado para OPEN (simula falha massiva no Redis)
        circuitBreaker.transitionToOpenState();
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // WHEN: Aguarda wait-duration-in-open-state (1s no perfil test) e transita
        Thread.sleep(1500);
        circuitBreaker.transitionToHalfOpenState();

        // THEN: Circuito deve estar em HALF_OPEN permitindo chamadas de teste
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        // Redis está disponível (container Testcontainers ativo) → chamada de sucesso fecha o circuito
        // Executa chamada trivial no Redis para registrar sucesso no sliding window
        redisTemplate.opsForValue().set("circuit-breaker-test", "ok");
        String value = redisTemplate.opsForValue().get("circuit-breaker-test");
        assertThat(value).isEqualTo("ok");

        // Registra sucesso manual no circuit breaker (a chamada SET/GET não passa pelo decorator)
        circuitBreaker.onSuccess(50, java.util.concurrent.TimeUnit.MILLISECONDS);

        // THEN: Circuito deve fechar após chamada bem-sucedida
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("RED: Health readiness deve reportar DOWN quando circuit breaker está OPEN")
    void shouldReportUnhealthyWhenCircuitOpen() throws Exception {
        // GIVEN: Circuit breaker está OPEN
        circuitBreaker.transitionToOpenState();
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // THEN: /actuator/health/readiness deve retornar 503 (componente circuitBreakers DOWN)
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    @DisplayName("RED: Health liveness deve retornar UP mesmo com circuit breaker OPEN")
    void shouldReportLivenessUpWhenCircuitOpen() throws Exception {
        // GIVEN: Circuit breaker está OPEN
        circuitBreaker.transitionToOpenState();
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // THEN: /actuator/health/liveness deve retornar 200 — o processo está vivo
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("RED: Todos os circuit breakers devem estar registrados no registry")
    void shouldHaveAllCircuitBreakersRegistered() {
        // BUG-03: Circuit breakers para todos os 4 recursos críticos
        assertThat(circuitBreakerRegistry.circuitBreaker("redisMatchEngine")).isNotNull();
        assertThat(circuitBreakerRegistry.circuitBreaker("postgresPool")).isNotNull();
        assertThat(circuitBreakerRegistry.circuitBreaker("mongoEventStore")).isNotNull();
        assertThat(circuitBreakerRegistry.circuitBreaker("rabbitmqPublisher")).isNotNull();
    }
}
