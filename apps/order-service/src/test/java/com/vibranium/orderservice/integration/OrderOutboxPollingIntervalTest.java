package com.vibranium.orderservice.integration;

import com.vibranium.orderservice.domain.repository.OrderOutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Valida que o polling interval de 500ms permite publicação de mensagens
 * em menos de 1 segundo — contraste com o cenário anterior de 5000ms.
 *
 * <p>O scheduler {@code @Scheduled(fixedDelayString = "${app.outbox.delay-ms:500}")}
 * executa automaticamente. Este teste insere uma mensagem via JDBC e verifica
 * que o {@code published_at} é preenchido em menos de 1s.</p>
 */
@DisplayName("[Integration] OrderOutbox — Polling Interval < 1s")
class OrderOutboxPollingIntervalTest extends AbstractIntegrationTest {

    @Autowired private OrderOutboxRepository outboxRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setup() {
        jdbcTemplate.update("DELETE FROM tb_order_outbox WHERE event_type = 'PollingTestEvent'");
    }

    @Test
    @DisplayName("Mensagem inserida deve ser publicada em < 1s com delay-ms=500")
    void shouldPublishWithinOneSecond() {
        UUID msgId = UUID.randomUUID();

        // Insere mensagem pendente diretamente via JDBC para controle preciso de timing
        jdbcTemplate.update("""
            INSERT INTO tb_order_outbox
                (id, aggregate_type, aggregate_id, event_type, exchange, routing_key, payload, created_at)
            VALUES (?, 'Order', ?, 'PollingTestEvent', 'vibranium.commands',
                    'wallet.commands.reserve-funds', ?::jsonb, now())
            """,
                msgId, UUID.randomUUID(),
                "{\"polling\":\"interval-test\"}");

        // Com delay-ms=500, a mensagem deve ser publicada em no máximo 1s
        // (worst case: insere logo após um ciclo → espera ~500ms + processamento)
        await("published_at deve ser preenchido em < 1s")
                .atMost(1, TimeUnit.SECONDS)
                .pollInterval(50, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    var msg = outboxRepository.findById(msgId);
                    assertThat(msg).isPresent();
                    assertThat(msg.get().getPublishedAt())
                            .as("published_at deve ser preenchido pelo scheduler em < 1s (delay-ms=500)")
                            .isNotNull();
                });
    }
}
