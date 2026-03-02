package com.vibranium.orderservice.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AT-14.1 — Fase RED: Verifica propagação W3C TraceContext em mensagens AMQP.
 *
 * <h2>Por que este teste falha ANTES da implementação</h2>
 * <p>Sem {@code micrometer-tracing-bridge-otel} no classpath, o Spring AMQP
 * não configura nenhum {@code ObservationHandler} de tracing. O
 * {@code RabbitTemplate} NÃO injeta o header W3C {@code traceparent}
 * nas mensagens AMQP. Logo, a asserção {@code assertThat(traceparent).isNotNull()}
 * falha — <strong>fase RED confirmada</strong>.</p>
 *
 * <h2>Por que este teste passa APÓS a implementação</h2>
 * <p>Com {@code micrometer-tracing-bridge-otel}:</p>
 * <ol>
 *   <li>Spring Boot auto-configura {@code OtelTracer} + {@code OtelPropagator}.</li>
 *   <li>{@code RabbitTemplate} + {@code ObservationRegistry} criam um span produtor
 *       para cada {@code convertAndSend()}.</li>
 *   <li>O {@code W3CPropagator} injeta {@code traceparent} no header da mensagem
 *       com formato {@code 00-<traceId-32hex>-<spanId-16hex>-<flags>}.</li>
 * </ol>
 *
 * <h2>Como funciona W3C TraceContext</h2>
 * <p>O padrão W3C TraceContext (RFC-7230 / W3C Recommendation) define dois headers:</p>
 * <ul>
 *   <li>{@code traceparent}: {@code 00-<traceId>-<parentId>-<flags>} — propagação obrigatória</li>
 *   <li>{@code tracestate}: dados opcionais do vendor</li>
 * </ul>
 * <p>O header {@code traceparent} permite que um consumer (wallet-service, motor de match)
 * continue o mesmo trace distribuído criado pelo produtor (order-service), formando a
 * árvore de spans que o Jaeger usa para exibir a latência end-to-end.</p>
 */
@DisplayName("AT-14.1 — Propagação W3C TraceContext em mensagens AMQP")
class TracingW3CPropagationIntegrationTest extends AbstractIntegrationTest {

    /**
     * RabbitAdmin: declara filas e bindings efêmeros para o teste sem alterar topologia de produção.
     * Auto-configurado pelo Spring AMQP via {@code RabbitAutoConfiguration}.
     */
    @Autowired
    private RabbitAdmin rabbitAdmin;

    /** Nome único da fila de teste para isolar esta execução de outras paralelas. */
    private String testQueueName;

    @BeforeEach
    void setUp() {
        // Fila efêmera: auto-delete = true garante limpeza mesmo se o teste falhar
        testQueueName = "test.at14.tracing." + UUID.randomUUID();
        rabbitAdmin.declareQueue(new Queue(testQueueName, false /* durable */, false /* exclusive */, true /* auto-delete */));
    }

    @AfterEach
    void tearDown() {
        // Remoção defensiva para não deixar filas orfãs no container compartilhado
        try {
            rabbitAdmin.deleteQueue(testQueueName);
        } catch (Exception ignored) { /* fila já foi auto-deletada */ }
    }

    // =========================================================================
    // Testes de propagação W3C
    // =========================================================================

    /**
     * Verifica que toda mensagem publicada via {@code RabbitTemplate} contém
     * o header W3C {@code traceparent}.
     *
     * <p><strong>RED antes de AT-14.1:</strong> sem {@code micrometer-tracing-bridge-otel},
     * o Spring AMQP não injeta {@code traceparent} → {@code assertThat(traceparent).isNotNull()}
     * lança {@code AssertionError}.</p>
     *
     * <p><strong>GREEN após AT-14.1:</strong> com o bridge presente, Spring AMQP
     * auto-configura a propagação W3C → header presente com formato correto.</p>
     */
    @Test
    @DisplayName("RabbitTemplate deve injetar header W3C 'traceparent' em toda mensagem AMQP publicada")
    void rabbitTemplate_shouldInjectW3CTraceparentHeader_inPublishedMessage() {
        // WHEN: publica mensagem via RabbitTemplate no exchange default (routing direto para fila)
        // Spring AMQP 3.x com ObservationRegistry cria span produtor e injeta traceparent
        rabbitTemplate.convertAndSend("", testQueueName, "at-14.1-tracing-test-payload");

        // THEN: recebe a mensagem publicada (timeout de 5s para evitar falso timeout)
        Message received = rabbitTemplate.receive(testQueueName, 5_000L);
        assertThat(received)
                .as("Mensagem deve ser recebida na fila de teste (verificar conectividade RabbitMQ)")
                .isNotNull();

        // ENTÃO: header traceparent deve estar presente e formatado conforme W3C TraceContext spec
        // Formato: version(2h)-traceId(32h)-parentId(16h)-flags(2h)
        // Exemplo:  00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
        Object traceparent = received.getMessageProperties().getHeaders().get("traceparent");

        // =====================================================================
        // RED antes da implementação (AT-14.1):
        //   - traceparent == null (sem bridge OTel configurado)
        //   - Este assertThat FALHA → confirma fase RED ✅
        //
        // GREEN após a implementação (AT-14.1):
        //   - traceparent = "00-<32hex>-<16hex>-01" (span criado pelo OtelTracer)
        //   - Este assertThat PASSA → confirma fase GREEN ✅
        // =====================================================================
        assertThat(traceparent)
                .as("AT-14.1 RED: Header W3C 'traceparent' ausente em mensagem AMQP.\nPara corrigir: adicionar micrometer-tracing-bridge-otel + opentelemetry-exporter-otlp\nem apps/order-service/pom.xml e configurar management.tracing.sampling.probability=1.0\nem application.yaml.")
                .isNotNull()
                .asString()
                .matches("^00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]$");
    }

    /**
     * Verifica que o header {@code traceparent} é único por mensagem publicada,
     * garantindo que spans distintos são gerados para cada operação AMQP.
     *
     * <p>Dois spans com o mesmo traceId são do mesmo trace (Saga), mas
     * devem ter spanIds diferentes. Isso garante relaçao parent-child no Jaeger.</p>
     */
    @Test
    @DisplayName("Cada mensagem AMQP deve ter um spanId único (spans distintos por operação)")
    void rabbitTemplate_shouldGenerateUniqueSpanIdPerMessage() {
        // Fila auxiliar para segunda mensagem
        String testQueueName2 = "test.at14.tracing.unique." + UUID.randomUUID();
        rabbitAdmin.declareQueue(new Queue(testQueueName2, false, false, true));

        try {
            // WHEN: dois envios independentes
            rabbitTemplate.convertAndSend("", testQueueName,  "message-1");
            rabbitTemplate.convertAndSend("", testQueueName2, "message-2");

            Message msg1 = rabbitTemplate.receive(testQueueName,  5_000L);
            Message msg2 = rabbitTemplate.receive(testQueueName2, 5_000L);

            assertThat(msg1).isNotNull();
            assertThat(msg2).isNotNull();

            String traceparent1 = (String) msg1.getMessageProperties().getHeaders().get("traceparent");
            String traceparent2 = (String) msg2.getMessageProperties().getHeaders().get("traceparent");

            // RED: null antes da implementação
            assertThat(traceparent1)
                    .as("AT-14.1 RED: traceparent da mensagem 1 deve estar presente")
                    .isNotNull();
            assertThat(traceparent2)
                    .as("AT-14.1 RED: traceparent da mensagem 2 deve estar presente")
                    .isNotNull();

            // Extrai spanId (posição 2 no split por '-') e compara
            // O spanId deve ser diferente para cada mensagem independente
            String spanId1 = traceparent1.split("-")[2];
            String spanId2 = traceparent2.split("-")[2];
            assertThat(spanId1)
                    .as("Cada operação AMQP deve gerar um spanId único")
                    .isNotEqualTo(spanId2);

        } finally {
            try { rabbitAdmin.deleteQueue(testQueueName2); } catch (Exception ignored) { /* */ }
        }
    }
}
