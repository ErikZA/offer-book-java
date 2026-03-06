package com.vibranium.walletservice.integration;

import com.vibranium.walletservice.AbstractIntegrationTest;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AT-15.2 — Valida que o endpoint {@code /actuator/prometheus} está exposto e
 * retorna métricas Micrometer no formato Prometheus.
 *
 * <p>{@link AutoConfigureObservability} é necessário porque o Spring Boot 3
 * desabilita as auto-configurações de exportação de métricas em testes por padrão.</p>
 */
@DisplayName("PrometheusEndpoint — /actuator/prometheus (AT-15.2)")
@AutoConfigureMockMvc
@AutoConfigureObservability
class PrometheusEndpointTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PrometheusMeterRegistry prometheusMeterRegistry;

    @Test
    @DisplayName("PrometheusMeterRegistry deve estar disponível no contexto")
    void prometheusMeterRegistry_shouldBeLoaded() {
        assertThat(prometheusMeterRegistry).isNotNull();
    }

    @Test
    @DisplayName("Scrape do Prometheus deve conter métrica vibranium_outbox_queue_depth")
    void prometheusScrape_shouldContainOutboxDepthMetric() {
        String scrape = prometheusMeterRegistry.scrape();
        assertThat(scrape).contains("vibranium_outbox_queue_depth");
    }

    @Test
    @DisplayName("GET /actuator/prometheus deve retornar 200 com formato Prometheus")
    void prometheusEndpoint_shouldReturn200WithMetrics() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/plain"));
    }
}
