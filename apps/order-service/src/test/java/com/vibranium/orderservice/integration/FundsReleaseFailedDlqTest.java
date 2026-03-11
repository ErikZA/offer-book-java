package com.vibranium.orderservice.integration;

import com.vibranium.orderservice.config.RabbitMQConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * [FASE RED → GREEN] — Teste de integração que verifica o roteamento para DLQ da fila
 * {@code order.events.funds-release-failed} quando mensagens tóxicas são publicadas.
 *
 * <h3>Problema (FASE RED)</h3>
 * <p>A fila {@code order.events.funds-release-failed} não existe e não possui
 * {@code x-dead-letter-exchange} configurado. Mensagens malformadas não são capturadas.</p>
 *
 * <h3>Solução (FASE GREEN)</h3>
 * <ul>
 *   <li>Fila {@code order.events.funds-release-failed} com DLX {@code vibranium.dlq}
 *       e routing key {@code order.events.funds-release-failed.dlq}.</li>
 *   <li>DLQ durable {@code order.events.funds-release-failed.dlq} com binding
 *       no {@code vibranium.dlq} exchange.</li>
 * </ul>
 *
 * <h3>Mecanismo de roteamento</h3>
 * <p>As mensagens tóxicas (payload inválido para {@code FundsReleaseFailedEvent})
 * causam {@code MessageConversionException} no listener. Como o factory é
 * {@code manualAckContainerFactory}, a exceção propaga e o Spring AMQP emite
 * {@code basicReject(requeue=false)} → RabbitMQ encaminha para DLX.</p>
 *
 * <h3>Estratégia de asserção</h3>
 * <p>Consulta a RabbitMQ Management HTTP API para verificar que a DLQ recebeu
 * a mensagem tóxica. Utiliza o mesmo padrão de {@link ProjectionDlqIntegrationTest}.</p>
 */
@TestPropertySource(properties = "spring.rabbitmq.listener.simple.acknowledge-mode=manual")
@DisplayName("Atividade 5 (DLQ) — Mensagens tóxicas roteadas para order.events.funds-release-failed.dlq")
class FundsReleaseFailedDlqTest extends AbstractIntegrationTest {

    @BeforeEach
    void purgeDlq() {
        purgeQueueSilently(RabbitMQConfig.QUEUE_FUNDS_RELEASE_FAILED_DLQ);
    }

    // =========================================================================
    // TC-DLQ-1: mensagem malformada roteada para DLQ
    // =========================================================================

    /**
     * [RED → GREEN] Mensagem com payload JSON inválido publicada na fila
     * {@code order.events.funds-release-failed} é roteada para
     * {@code order.events.funds-release-failed.dlq}.
     *
     * <p><strong>Falha antes da correção (RED):</strong> fila ou DLQ não existe — a mensagem
     * é descartada pelo broker; {@code await()} expira com {@code ConditionTimeoutException}.</p>
     *
     * <p><strong>Passa após a correção (GREEN):</strong>
     * {@code x-dead-letter-exchange=vibranium.dlq} + binding para DLQ → {@code messages=1}.</p>
     */
    @Test
    @DisplayName("TC-DLQ-1: payload inválido em order.events.funds-release-failed é roteado para DLQ")
    void testInvalidMessage_isRoutedToDlq() {
        // GIVEN: mensagem com Content-Type JSON mas body não desserializável como FundsReleaseFailedEvent
        Message toxicMessage = MessageBuilder
                .withBody("{\"toxic\":true,\"notAFundsReleaseFailedEvent\":\"INVALID\"}".getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .build();

        rabbitTemplate.send(
                RabbitMQConfig.EVENTS_EXCHANGE,
                RabbitMQConfig.RK_FUNDS_RELEASE_FAILED,
                toxicMessage
        );

        // THEN: a DLQ deve receber 1 mensagem após o listener rejeitar o payload
        await().atMost(20, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() ->
                        assertThat(getQueueTotalMessages(RabbitMQConfig.QUEUE_FUNDS_RELEASE_FAILED_DLQ))
                                .as("DLQ '%s' deve conter 1 mensagem tóxica após rejeição pelo listener"
                                        .formatted(RabbitMQConfig.QUEUE_FUNDS_RELEASE_FAILED_DLQ))
                                .isEqualTo(1)
                );
    }

    // =========================================================================
    // TC-DLQ-2: DLQ está declarada e acessível
    // =========================================================================

    @Test
    @DisplayName("TC-DLQ-2: DLQ order.events.funds-release-failed.dlq deve estar declarada no broker")
    void testDlq_isProperlyDeclared() {
        assertThat(getQueueTotalMessages(RabbitMQConfig.QUEUE_FUNDS_RELEASE_FAILED_DLQ))
                .as("DLQ order.events.funds-release-failed.dlq deve estar declarada")
                .isGreaterThanOrEqualTo(0);
    }

    // =========================================================================
    // Helpers (mesmo padrão de ProjectionDlqIntegrationTest)
    // =========================================================================

    @SuppressWarnings("unchecked")
    private int getQueueTotalMessages(String queueName) {
        URI uri = URI.create(String.format("http://%s:%d/api/queues/%%2F/%s",
                RABBITMQ.getHost(),
                RABBITMQ.getMappedPort(15672),
                queueName));

        RestTemplate restTemplate = new RestTemplateBuilder()
                .basicAuthentication(RABBITMQ.getAdminUsername(), RABBITMQ.getAdminPassword())
                .build();

        Map<String, Object> queueInfo = restTemplate.getForObject(uri, Map.class);
        assertThat(queueInfo)
                .as("RabbitMQ Management API deve retornar dados da fila '%s'".formatted(queueName))
                .isNotNull();

        Number messages = (Number) queueInfo.get("messages");
        return messages != null ? messages.intValue() : 0;
    }

    private void purgeQueueSilently(String queueName) {
        try {
            URI uri = URI.create(String.format("http://%s:%d/api/queues/%%2F/%s/contents",
                    RABBITMQ.getHost(),
                    RABBITMQ.getMappedPort(15672),
                    queueName));

            RestTemplate restTemplate = new RestTemplateBuilder()
                    .basicAuthentication(RABBITMQ.getAdminUsername(), RABBITMQ.getAdminPassword())
                    .build();

            restTemplate.delete(uri);
        } catch (Exception ignored) {
            // Fila pode não existir em FASE RED — ignorar silenciosamente.
        }
    }
}
