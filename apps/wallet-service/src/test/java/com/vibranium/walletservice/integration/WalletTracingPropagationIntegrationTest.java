package com.vibranium.walletservice.integration;

import com.vibranium.walletservice.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AT-14.1 — Fase RED: Verifica propagação W3C TraceContext em mensagens AMQP do wallet-service.
 *
 * <h2>Por que este teste falha ANTES da implementação</h2>
 * <p>Sem {@code micrometer-tracing-bridge-otel} no classpath do wallet-service,
 * o Spring AMQP não injeta o header W3C {@code traceparent} nas mensagens.
 * A asserção {@code assertThat(traceparent).isNotNull()} falha —
 * <strong>fase RED confirmada</strong>.</p>
 *
 * <h2>Por que este teste passa APÓS a implementação</h2>
 * <p>Com {@code micrometer-tracing-bridge-otel} configurado:</p>
 * <ol>
 *   <li>Spring Boot auto-configura {@code OtelTracer} + {@code W3CPropagator}.</li>
 *   <li>Toda publicação via {@code RabbitTemplate} cria um span produtor e
 *       injeta {@code traceparent} e (opcionalmente) {@code tracestate} nos
 *       headers da mensagem AMQP.</li>
 *   <li>O wallet-service consumer (listener) extrai o contexto via
 *       {@code W3CPropagator.extract()} e continua o trace do order-service.</li>
 * </ol>
 *
 * <h2>Diferença entre correlationId de domínio e Distributed Tracing</h2>
 * <table>
 *   <caption>Comparação</caption>
 *   <tr><th>Aspecto</th><th>correlationId (domínio)</th><th>traceparent (W3C)</th></tr>
 *   <tr><td>Propósito</td><td>Ligar comandos/eventos de uma Saga</td><td>Contexto de trace distribuído</td></tr>
 *   <tr><td>Gerado por</td><td>Domínio (OrderCommandService)</td><td>Micrometer/OTel (automático)</td></tr>
 *   <tr><td>Visível no</td><td>Logs, banco de dados</td><td>Jaeger, Zipkin, OTEL Collector</td></tr>
 *   <tr><td>Mede latência</td><td>Não</td><td>Sim (por etapa, end-to-end)</td></tr>
 * </table>
 */
@DisplayName("AT-14.1 — Wallet: Propagação W3C TraceContext em mensagens AMQP")
class WalletTracingPropagationIntegrationTest extends AbstractIntegrationTest {

    /** Nome único da fila de teste isolada para esta execução. */
    private String testQueueName;

    @BeforeEach
    void setUpTestQueue() {
        // Cria fila efêmera auto-delete: não interfere na topologia de produção
        testQueueName = "test.at14.wallet.tracing." + UUID.randomUUID();
        rabbitAdmin.declareQueue(
                new Queue(testQueueName,
                        false /* durable */,
                        false /* exclusive */,
                        true  /* auto-delete */)
        );
    }

    @AfterEach
    void cleanUpTestQueue() {
        try {
            rabbitAdmin.deleteQueue(testQueueName);
        } catch (Exception ignored) { /* fila já foi auto-deletada */ }
    }

    // =========================================================================
    // RED tests
    // =========================================================================

    /**
     * Verifica que o wallet-service injeta W3C {@code traceparent} em mensagens AMQP.
     *
     * <p><strong>RED antes de AT-14.1:</strong> sem bridge OTel, o header está ausente.
     * {@code assertThat(traceparent).isNotNull()} lança {@code AssertionError}.</p>
     *
     * <p><strong>GREEN após AT-14.1:</strong> bridge + Spring AMQP auto-config
     * injeta {@code traceparent} com formato W3C correto.</p>
     */
    @Test
    @DisplayName("RabbitTemplate do wallet-service deve injetar header W3C 'traceparent'")
    void walletRabbitTemplate_shouldInjectW3CTraceparentHeader() {
        // WHEN: publica uma mensagem AMQP via RabbitTemplate do wallet-service
        // Spring AMQP 3.x cria span produtor + injeta traceparent quando OTel bridge está presente
        rabbitTemplate.convertAndSend("", testQueueName, "at-14.1-wallet-tracing-test");

        // THEN: recebe a mensagem
        Message received = rabbitTemplate.receive(testQueueName, 5_000L);
        assertThat(received)
                .as("Mensagem deve ser recebida na fila de teste (verificar conectividade)")
                .isNotNull();

        // Header W3C traceparent deve estar presente
        Object traceparent = received.getMessageProperties().getHeaders().get("traceparent");

        // =====================================================================
        // RED antes de AT-14.1:
        //   traceparent == null → assertThat FALHA → fase RED confirmada ✅
        //
        // GREEN após AT-14.1 (micrometer-tracing-bridge-otel + management.tracing.sampling=1.0):
        //   traceparent = "00-<32 hex traceId>-<16 hex spanId>-01"
        //   assertThat PASSA → fase GREEN confirmada ✅
        // =====================================================================
        assertThat(traceparent)
                .as("AT-14.1 RED [wallet-service]: Header W3C 'traceparent' ausente.\nRequisito: adicionar micrometer-tracing-bridge-otel + opentelemetry-exporter-otlp\nem apps/wallet-service/pom.xml.\nConfigurar management.tracing.sampling.probability=1.0 em application.yaml.")
                .isNotNull()
                .asString()
                .matches("^00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]$");
    }

    /**
     * Verifica que cada mensagem publicada pelo wallet-service recebe um spanId único,
     * garantindo que spans distintos são gerados por operação AMQP.
     *
     * <p>Dois spans com o mesmo traceId pertencem ao mesmo trace (Saga), mas
     * devem ter spanIds distintos para representar operações independentes.</p>
     *
     * <p><strong>RED antes de AT-14.1:</strong> sem bridge, traceparent é null.
     * <strong>GREEN após AT-14.1:</strong> cada mensagem tem traceparent único com spanId distinto.</p>
     */
    @Test
    @DisplayName("Cada mensagem AMQP deve ter um spanId único (spans distintos por operação)")
    void messageWithTraceparent_shouldPreserveTraceIdAcrossServices() {
        // Fila auxiliar para segunda mensagem — isolamento por UUID evita interferência
        String testQueueName2 = "test.at14.wallet.tracing.b." + UUID.randomUUID();
        rabbitAdmin.declareQueue(new Queue(testQueueName2, false, false, true));

        try {
            // WHEN: dois envios independentes via RabbitTemplate
            rabbitTemplate.convertAndSend("", testQueueName,  "wallet-msg-1");
            rabbitTemplate.convertAndSend("", testQueueName2, "wallet-msg-2");

            Message msg1 = rabbitTemplate.receive(testQueueName,  5_000L);
            Message msg2 = rabbitTemplate.receive(testQueueName2, 5_000L);

            assertThat(msg1).as("msg1 deve ser recebida").isNotNull();
            assertThat(msg2).as("msg2 deve ser recebida").isNotNull();

            String traceparent1 = (String) msg1.getMessageProperties().getHeaders().get("traceparent");
            String traceparent2 = (String) msg2.getMessageProperties().getHeaders().get("traceparent");

            // THEN: ambas as mensagens têm traceparent válido W3C
            assertThat(traceparent1)
                    .as("AT-14.1: traceparent da msg 1 do wallet-service")
                    .isNotNull()
                    .matches("^00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]$");
            assertThat(traceparent2)
                    .as("AT-14.1: traceparent da msg 2 do wallet-service")
                    .isNotNull()
                    .matches("^00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]$");

            // E cada mensagem tem um spanId único (operações AMQP independentes)
            String spanId1 = traceparent1.split("-")[2];
            String spanId2 = traceparent2.split("-")[2];
            assertThat(spanId1)
                    .as("AT-14.1: cada operação AMQP deve gerar um spanId único")
                    .isNotEqualTo(spanId2);

        } finally {
            try { rabbitAdmin.deleteQueue(testQueueName2); } catch (Exception ignored) { /* */ }
        }
    }
}
