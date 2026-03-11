package com.vibranium.orderservice.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * BUG-04 TDD — Testes de degradação graciosa para pool exhaustion.
 *
 * <p>Valida que:
 * <ul>
 *   <li>Respostas de erro NUNCA contêm stack trace ou informação interna (OWASP A01).</li>
 *   <li>Métricas HikariCP estão expostas no endpoint Prometheus.</li>
 *   <li>Respostas contêm campos padronizados (error, correlationId).</li>
 * </ul>
 */
@AutoConfigureObservability
@DisplayName("BUG-04 — Graceful Degradation (Pool Exhaustion)")
class GracefulDegradationPoolTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("RED: Resposta de erro NUNCA deve conter stack trace ou info interna")
    void shouldNotLeakInternalInfoOnError() throws Exception {
        // WHEN: Requisição POST com body inválido força erro de validação/desserialização
        String invalidJson = "{ \"invalid\": true }";

        String responseBody = mockMvc.perform(post("/api/v1/orders")
                        .contentType("application/json")
                        .content(invalidJson))
                .andReturn()
                .getResponse()
                .getContentAsString();

        // THEN: Response body NÃO contém informações internas
        org.assertj.core.api.Assertions.assertThat(responseBody)
                .doesNotContain("java.")
                .doesNotContain("org.springframework.")
                .doesNotContain("at com.")
                .doesNotContain(".java:")
                .doesNotContain("NullPointerException")
                .doesNotContain("ClassCastException")
                .doesNotContain("StackTrace");
    }

    @Test
    @DisplayName("RED: Métricas HikariCP pool devem estar expostas no Prometheus endpoint")
    void shouldExposeHikariMetrics() throws Exception {
        // GET /actuator/prometheus
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("hikaricp_connections_active")))
                .andExpect(content().string(containsString("hikaricp_connections_idle")))
                .andExpect(content().string(containsString("hikaricp_connections_pending")))
                .andExpect(content().string(containsString("hikaricp_connections_timeout_total")));
    }

    @Test
    @DisplayName("RED: Métricas HikariCP devem estar disponíveis via /actuator/metrics")
    void shouldExposeHikariMetricsViaActuator() throws Exception {
        mockMvc.perform(get("/actuator/metrics/hikaricp.connections.active"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/actuator/metrics/hikaricp.connections.idle"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("RED: Endpoint health deve incluir detalhes do datasource")
    void shouldIncludeDataSourceInHealth() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.db").exists())
                .andExpect(jsonPath("$.components.db.status").value("UP"));
    }
}


