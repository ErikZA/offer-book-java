package com.vibranium.orderservice.integration;

import com.vibranium.orderservice.application.service.OrderOutboxPublisherService;
import com.vibranium.orderservice.domain.repository.OrderOutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testa que o {@code OrderOutboxPublisherService} processa mensagens
 * em lotes de tamanho configurável ({@code app.outbox.batch-size}).
 *
 * <p>O scheduler automático é desabilitado via {@code delay-ms=3600000}
 * para que a invocação manual de {@code publishPendingMessages()} controle
 * exatamente quantas mensagens são processadas por ciclo.</p>
 */
@TestPropertySource(properties = {
        "app.outbox.delay-ms=3600000",
        "app.outbox.batch-size=100"
})
@DisplayName("[Integration] OrderOutbox — Batch Size configurável")
class OrderOutboxBatchSizeTest extends AbstractIntegrationTest {

    @Autowired private OrderOutboxRepository outboxRepository;
    @Autowired private OrderOutboxPublisherService publisherService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setup() {
        jdbcTemplate.update("DELETE FROM tb_order_outbox WHERE event_type = 'BatchTestEvent'");
    }

    @Test
    @DisplayName("batch-size=100: 250 mensagens devem ser processadas em lotes de 100, 100, 50")
    void shouldProcessInBatchesOfConfiguredSize() {
        int totalMessages = 250;

        for (int i = 0; i < totalMessages; i++) {
            jdbcTemplate.update("""
                INSERT INTO tb_order_outbox
                    (id, aggregate_type, aggregate_id, event_type, exchange, routing_key, payload, created_at)
                VALUES (?, 'Order', ?, 'BatchTestEvent', 'vibranium.commands',
                        'wallet.commands.reserve-funds', ?::jsonb, now())
                """,
                    UUID.randomUUID(), UUID.randomUUID(),
                    "{\"batch\":" + i + "}");
        }

        assertThat(countPending()).isEqualTo(250);

        // Primeira execução: 100 processadas
        publisherService.publishPendingMessages();
        assertThat(countPending())
                .as("Após 1ª execução, 150 mensagens devem permanecer pendentes")
                .isEqualTo(150);

        // Segunda execução: 100 processadas
        publisherService.publishPendingMessages();
        assertThat(countPending())
                .as("Após 2ª execução, 50 mensagens devem permanecer pendentes")
                .isEqualTo(50);

        // Terceira execução: 50 processadas
        publisherService.publishPendingMessages();
        assertThat(countPending())
                .as("Após 3ª execução, zero mensagens devem permanecer pendentes")
                .isZero();
    }

    private long countPending() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_order_outbox WHERE event_type = 'BatchTestEvent' AND published_at IS NULL",
                Long.class);
    }
}
