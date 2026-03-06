package com.vibranium.orderservice.integration;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AT-15.2 — Valida que o Prometheus MeterRegistry está configurado e que o
 * endpoint {@code /actuator/prometheus} está acessível.
 *
 * <p>{@link AutoConfigureObservability} é necessário porque o Spring Boot 3
 * desabilita as auto-configurações de exportação de métricas em testes por padrão
 * (via {@code ObservabilityContextCustomizerFactory}). Sem esta anotação,
 * o {@code PrometheusMeterRegistry} não é registrado no contexto de teste.</p>
 */
@DisplayName("PrometheusEndpoint — /actuator/prometheus (AT-15.2)")
@AutoConfigureObservability
class PrometheusEndpointTest extends AbstractIntegrationTest {

    @Autowired
    private PrometheusMeterRegistry prometheusMeterRegistry;

    @Test
    @DisplayName("PrometheusMeterRegistry deve estar disponível no contexto")
    void prometheusMeterRegistry_shouldBeLoaded() {
        assertThat(prometheusMeterRegistry).isNotNull();
    }

    @Test
    @DisplayName("Scrape do Prometheus deve conter métrica vibranium_outbox_queue_depth")
    void prometheusScrape_shouldContainOutboxQueueDepthMetric() {
        String scrape = prometheusMeterRegistry.scrape();

        // Prometheus converte '.' em '_' no nome da métrica
        assertThat(scrape).contains("vibranium_outbox_queue_depth");
    }

    @Test
    @DisplayName("GET /actuator/prometheus com JWT deve retornar 200")
    void prometheusEndpoint_withJwt_shouldReturn200() throws Exception {
        mockMvc.perform(get("/actuator/prometheus")
                        .with(jwt().jwt(j -> j.subject("test-user"))))
                .andExpect(status().isOk());
    }
}
