package com.vibranium.orderservice.integration;

import com.vibranium.orderservice.config.RabbitMQConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * AT-2.2.1 — Testes de integração que verificam o roteamento para DLQ das filas de
 * projeção do Query Side quando mensagens tóxicas (inválidas/não deserializáveis) são
 * publicadas.
 *
 * <h3>Problema (FASE RED)</h3>
 * <p>As 4 filas de projeção ({@code order.projection.*}) não possuem {@code x-dead-letter-exchange}
 * configurado. Mensagens que o listener não consegue desserializar
 * ({@link com.fasterxml.jackson.databind.exc.InvalidDefinitionException} /
 * {@link org.springframework.amqp.support.converter.MessageConversionException})
 * são rejeitadas sem destino — o RabbitMQ descarta a mensagem silenciosamente.
 * Sem DLX, mensagens tóxicas desaparecem sem rastreabilidade; com
 * {@code defaultRequeueRejected=true}, causariam loop infinito.</p>
 *
 * <h3>Solução (FASE GREEN)</h3>
 * <ul>
 *   <li>Cada {@code QueueBuilder} de projeção recebe
 *       {@code x-dead-letter-exchange=vibranium.dlq} e
 *       {@code x-dead-letter-routing-key=<queue>.dlq}.</li>
 *   <li>4 filas DLQ durable declaradas:
 *       {@code order.projection.received.dlq}, {@code order.projection.funds-reserved.dlq},
 *       {@code order.projection.match-executed.dlq}, {@code order.projection.cancelled.dlq}.</li>
 *   <li>4 bindings no {@code vibranium.dlq} exchange — um por DLQ.</li>
 *   <li>{@code autoAckContainerFactory} define {@code defaultRequeueRejected=false}
 *       para prevenir loop infinito em qualquer exceção (não apenas fatais).</li>
 * </ul>
 *
 * <h3>Mecanismo de roteamento</h3>
 * <p>A {@link org.springframework.amqp.support.converter.Jackson2JsonMessageConverter}
 * lança {@link org.springframework.amqp.support.converter.MessageConversionException}
 * ao tentar desserializar um payload inválido como {@code OrderReceivedEvent}.
 * O Spring AMQP trata {@code MessageConversionException} como exceção <em>fatal</em>:
 * emite {@code basicReject(deliveryTag, requeue=false)} independentemente do
 * {@code defaultRequeueRejected}, evitando o requeue automático. O RabbitMQ então
 * encaminha a mensagem para a DLX configurada na fila de origem.</p>
 *
 * <h3>Estratégia de asserção</h3>
 * <p>A verificação consulta a <strong>RabbitMQ Management HTTP API</strong>
 * ({@code /api/queues/%2F/{queue}}) para ler {@code messages} da DLQ de destino.
 * RED → DLQ não existe (404) ou mensagem descartada ({@code messages=0}) → falha.
 * GREEN → mensagem roteada → {@code messages=1} → passa.</p>
 *
 * <h3>Isolamento</h3>
 * <p>O {@code @BeforeEach} purga a DLQ via Management API para evitar interferência
 * entre execuções. Filas existentes precisam ser deletadas e recriadas no broker
 * se os argumentos mudarem (RabbitMQ não permite alterar args de fila existente).</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // Simula produção: acknowledge-mode=manual globalmente, como no application.yaml.
        // Os listeners de projeção ainda usam autoAckContainerFactory (AcknowledgeMode.AUTO)
        // explicitamente, portanto a sobreposição não interfere no roteamento para DLQ.
        properties = "spring.rabbitmq.listener.simple.acknowledge-mode=manual"
)
@DisplayName("AT-2.2.1 — DLX nas filas de projeção: mensagens tóxicas roteadas para DLQ")
class ProjectionDlqIntegrationTest extends AbstractMongoIntegrationTest {

    // =========================================================================
    // Setup / Teardown
    // =========================================================================

    @BeforeEach
    void purgeDlqs() {
        // Purga as DLQs antes de cada teste para garantir isolamento.
        // Em FASE RED as filas não existem — a purge simplesmente não encontra nada;
        // nenhuma exceção é lançada pois ignoramos 404.
        purgeQueueSilently(RabbitMQConfig.QUEUE_ORDER_PROJECTION_RECEIVED_DLQ);
        purgeQueueSilently(RabbitMQConfig.QUEUE_ORDER_PROJECTION_FUNDS_DLQ);
        purgeQueueSilently(RabbitMQConfig.QUEUE_ORDER_PROJECTION_MATCH_DLQ);
        purgeQueueSilently(RabbitMQConfig.QUEUE_ORDER_PROJECTION_CANCELLED_DLQ);
    }

    // =========================================================================
    // TC-DLQ-1: mensagem inválida roteada para DLQ da fila order.projection.received
    // =========================================================================

    /**
     * [AT-2.2.1 / TC-DLQ-1] — FASE RED → GREEN.
     *
     * <p><strong>Cenário:</strong> publica um payload JSON malformado (não desserializável
     * como {@link com.vibranium.contracts.events.order.OrderReceivedEvent}) para a exchange
     * de eventos com a routing key de {@code OrderReceivedEvent}. O payload chega à fila
     * {@code order.projection.received}; o listener lança
     * {@code MessageConversionException}; o Spring AMQP rejeita sem requeue; a mensagem
     * é roteada para {@code order.projection.received.dlq}.</p>
     *
     * <p><strong>Falha antes da correção (RED):</strong> fila sem DLX — a mensagem é
     * descartada pelo broker; a DLQ não existe ou permanece vazia; o {@code await()}
     * expira com {@code ConditionTimeoutException}.</p>
     *
     * <p><strong>Passa após a correção (GREEN):</strong>
     * {@code x-dead-letter-exchange=vibranium.dlq} + binding para
     * {@code order.projection.received.dlq} → {@code messages=1} na DLQ.</p>
     */
    @Test
    @DisplayName("TC-DLQ-1: payload inválido em order.projection.received é roteado para DLQ")
    void testInvalidMessage_isRoutedToDlq() {
        // GIVEN: mensagem com Content-Type JSON mas body não desserializável como OrderReceivedEvent.
        // Jackson lança MessageConversionException → Spring AMQP rejeita sem requeue
        // → RabbitMQ encaminha para vibranium.dlq exchange → order.projection.received.dlq.
        Message toxicMessage = MessageBuilder
                .withBody("{\"toxic\":true,\"notAnOrderEvent\":\"INVALID\"}".getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                // __TypeId__ ausente obriga o converter a inferir o tipo pelo listener → falha.
                .build();

        rabbitTemplate.send(
                RabbitMQConfig.EVENTS_EXCHANGE,
                RabbitMQConfig.RK_ORDER_RECEIVED,
                toxicMessage
        );

        // THEN: a DLQ de projeção deve receber 1 mensagem após o listener rejeitar o payload.
        // RED  → DLQ não existe (404) ou messages=0 → ConditionTimeoutException.
        // GREEN → messages=1 → passa.
        await().atMost(20, TimeUnit.SECONDS)
               .pollInterval(500, TimeUnit.MILLISECONDS)
               .untilAsserted(() ->
                   assertThat(getQueueTotalMessages(RabbitMQConfig.QUEUE_ORDER_PROJECTION_RECEIVED_DLQ))
                           .as("DLQ '%s' deve conter 1 mensagem tóxica após rejeição pelo listener"
                                   .formatted(RabbitMQConfig.QUEUE_ORDER_PROJECTION_RECEIVED_DLQ))
                           .isEqualTo(1)
               );
    }

    // =========================================================================
    // TC-DLQ-2: todas as 4 DLQs de projeção declaradas e acessíveis
    // =========================================================================

    /**
     * [AT-2.2.1 / TC-DLQ-2] — Smoke test de infraestrutura.
     *
     * <p><strong>Cenário:</strong> verifica que as 4 DLQs de projeção existem no broker
     * e estão acessíveis via Management API. Falha em FASE RED (filas não declaradas).</p>
     *
     * <p>Cada DLQ deve retornar código HTTP 200 da Management API, confirmando que
     * o {@link RabbitMQConfig} declarou e vinculou corretamente todas as DLQs.</p>
     */
    @Test
    @DisplayName("TC-DLQ-2: todas as 4 DLQs de projeção devem estar declaradas no broker")
    void testAllProjectionDlqs_areProperlyDeclared() {
        // Verifica existência consultando messages (0 é esperado — filas acabaram de ser purgadas).
        // Se a fila não existir, getQueueTotalMessages lança AssertionError (resposta nula da API).
        // RED  → filas não existem → AssertionError → falha.
        // GREEN → filas declaradas → messages=0 → passa.
        assertThat(getQueueTotalMessages(RabbitMQConfig.QUEUE_ORDER_PROJECTION_RECEIVED_DLQ))
                .as("DLQ order.projection.received.dlq deve estar declarada")
                .isGreaterThanOrEqualTo(0);

        assertThat(getQueueTotalMessages(RabbitMQConfig.QUEUE_ORDER_PROJECTION_FUNDS_DLQ))
                .as("DLQ order.projection.funds-reserved.dlq deve estar declarada")
                .isGreaterThanOrEqualTo(0);

        assertThat(getQueueTotalMessages(RabbitMQConfig.QUEUE_ORDER_PROJECTION_MATCH_DLQ))
                .as("DLQ order.projection.match-executed.dlq deve estar declarada")
                .isGreaterThanOrEqualTo(0);

        assertThat(getQueueTotalMessages(RabbitMQConfig.QUEUE_ORDER_PROJECTION_CANCELLED_DLQ))
                .as("DLQ order.projection.cancelled.dlq deve estar declarada")
                .isGreaterThanOrEqualTo(0);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Retorna o total de mensagens ({@code messages_ready + messages_unacknowledged})
     * em uma fila via RabbitMQ Management HTTP API.
     *
     * @param queueName nome da fila
     * @return total de mensagens, ou {@code 0} se o campo não estiver presente
     */
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

    /**
     * Purga uma fila via Management API, ignorando erros 404 (fila não existe em RED).
     *
     * @param queueName nome da fila a purgar
     */
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
