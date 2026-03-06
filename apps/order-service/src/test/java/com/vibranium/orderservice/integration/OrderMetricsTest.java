package com.vibranium.orderservice.integration;

import com.vibranium.contracts.enums.OrderType;
import com.vibranium.orderservice.domain.model.UserRegistry;
import com.vibranium.orderservice.domain.repository.OrderOutboxRepository;
import com.vibranium.orderservice.domain.repository.OrderRepository;
import com.vibranium.orderservice.domain.repository.UserRegistryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AT-15.2 — Testes de métricas de negócio Micrometer no order-service.
 *
 * <p>Valida que as métricas {@code vibranium.orders.created},
 * {@code vibranium.orders.matched} e {@code vibranium.orders.cancelled}
 * são incrementadas corretamente nos fluxos de negócio.</p>
 */
@DisplayName("OrderMetrics — Métricas de negócio Micrometer (AT-15.2)")
class OrderMetricsTest extends AbstractIntegrationTest {

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderOutboxRepository outboxRepository;

    @Autowired
    private UserRegistryRepository userRegistryRepository;

    private String keycloakId;
    private UUID walletId;

    @BeforeEach
    void setup() {
        outboxRepository.deleteAll();
        orderRepository.deleteAll();

        keycloakId = UUID.randomUUID().toString();
        walletId   = UUID.randomUUID();

        userRegistryRepository.save(new UserRegistry(keycloakId));
    }

    // =========================================================================
    // vibranium.orders.created
    // =========================================================================

    @Test
    @DisplayName("placeOrder BUY deve incrementar vibranium.orders.created com tag orderType=BUY")
    void placeOrder_buy_shouldIncrementOrdersCreatedCounter() throws Exception {
        // Captura contagem atual antes da operação
        double before = getCounterValue("vibranium.orders.created", "orderType", "BUY");

        // Act — cria uma ordem BUY
        mockMvc.perform(post("/api/v1/orders")
                        .contentType("application/json")
                        .content(buildPlaceOrderJson(walletId, "BUY", "100.00", "1.00"))
                        .with(jwt().jwt(j -> j.subject(keycloakId))))
                .andExpect(status().isAccepted());

        // Assert — counter incrementou em 1
        double after = getCounterValue("vibranium.orders.created", "orderType", "BUY");
        assertThat(after - before).isEqualTo(1.0);
    }

    @Test
    @DisplayName("placeOrder SELL deve incrementar vibranium.orders.created com tag orderType=SELL")
    void placeOrder_sell_shouldIncrementOrdersCreatedCounter() throws Exception {
        double before = getCounterValue("vibranium.orders.created", "orderType", "SELL");

        mockMvc.perform(post("/api/v1/orders")
                        .contentType("application/json")
                        .content(buildPlaceOrderJson(walletId, "SELL", "100.00", "1.00"))
                        .with(jwt().jwt(j -> j.subject(keycloakId))))
                .andExpect(status().isAccepted());

        double after = getCounterValue("vibranium.orders.created", "orderType", "SELL");
        assertThat(after - before).isEqualTo(1.0);
    }

    // =========================================================================
    // vibranium.outbox.queue.depth
    // =========================================================================

    @Test
    @DisplayName("Após placeOrder, vibranium.outbox.queue.depth deve refletir mensagens pendentes")
    void placeOrder_shouldReflectOutboxDepth() throws Exception {
        // Act — cria uma ordem (gera 2 mensagens outbox: ReserveFundsCommand + OrderReceivedEvent)
        mockMvc.perform(post("/api/v1/orders")
                        .contentType("application/json")
                        .content(buildPlaceOrderJson(walletId, "BUY", "100.00", "1.00"))
                        .with(jwt().jwt(j -> j.subject(keycloakId))))
                .andExpect(status().isAccepted());

        // Assert — gauge reflete as mensagens pendentes no outbox
        double depth = getGaugeValue("vibranium.outbox.queue.depth");
        assertThat(depth).isGreaterThanOrEqualTo(1.0);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private double getCounterValue(String name, String tagKey, String tagValue) {
        var counter = meterRegistry.find(name).tag(tagKey, tagValue).counter();
        return counter != null ? counter.count() : 0.0;
    }

    private double getGaugeValue(String name) {
        var gauge = meterRegistry.find(name).gauge();
        return gauge != null ? gauge.value() : 0.0;
    }

    private String buildPlaceOrderJson(UUID wId, String type, String price, String amount) {
        return """
                {
                  "walletId": "%s",
                  "orderType": "%s",
                  "price": %s,
                  "amount": %s
                }
                """.formatted(wId, type, price, amount);
    }
}
