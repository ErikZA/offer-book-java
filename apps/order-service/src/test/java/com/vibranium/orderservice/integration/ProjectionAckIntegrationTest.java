package com.vibranium.orderservice.integration;

import com.vibranium.contracts.enums.OrderType;
import com.vibranium.contracts.events.order.OrderReceivedEvent;
import com.vibranium.orderservice.config.RabbitMQConfig;
import com.vibranium.orderservice.query.model.OrderDocument;
import com.vibranium.orderservice.query.repository.OrderHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * AT-1.2.1 — Testes de integração para verificar que os listeners de projeção MongoDB
 * confirmam (ACK) as mensagens corretamente após o processamento.
 *
 * <h3>Problema (FASE RED)</h3>
 * <p>Os 4 listeners de {@link com.vibranium.orderservice.query.consumer.OrderEventProjectionConsumer}
 * herdam o {@code acknowledge-mode: manual} configurado globalmente em {@code application.yaml},
 * mas <strong>não</strong> chamam {@code channel.basicAck()} explicitamente. Como resultado,
 * todas as mensagens consumidas ficam em estado {@code unacknowledged} no broker
 * indefinidamente — a fila nunca esvazia do ponto de vista do RabbitMQ.</p>
 *
 * <h3>Solução (FASE GREEN)</h3>
 * <p>Bean {@code autoAckContainerFactory} com {@code AcknowledgeMode.AUTO} declarado em
 * {@link RabbitMQConfig}. Spring AMQP chama {@code basicAck()} automaticamente após
 * o método listener retornar com sucesso, sem necessidade de código adicional no consumer.</p>
 *
 * <h3>Estratégia de asserção RED vs GREEN</h3>
 * <p>A verificação usa a <strong>RabbitMQ Management HTTP API</strong> para consultar
 * {@code messages} (= {@code messages_ready + messages_unacknowledged}) da fila.
 * Com MANUAL sem ACK: {@code messages_unacknowledged=1} → {@code messages=1} → asserção falha (RED).
 * Com AUTO ACK: Spring confirma após o listener → {@code messages=0} → asserção passa (GREEN).</p>
 *
 * <h3>Simulação do comportamento de produção</h3>
 * <p>{@code application-test.yml} define {@code acknowledge-mode: auto} globalmente para
 * simplificar o setup dos testes do Command Side. Este teste usa
 * {@code @SpringBootTest(properties = "...=manual")} para sobrescrever essa configuração
 * e simular o ambiente de produção (onde {@code application.yaml} define {@code manual}).
 * Isso garante que o RED seja observável e que a correção via {@code autoAckContainerFactory}
 * seja validada de forma realista.</p>
 *
 * <p><strong>Idempotência preservada:</strong> mesmo com AUTO ACK, as operações MongoDB em
 * {@link com.vibranium.orderservice.query.service.OrderAtomicHistoryWriter} são idempotentes
 * via filtro {@code $ne} no {@code eventId}. Re-entrega acidental não corrompe o estado.</p>
 */
// Sobrescreve application-test.yml (que define auto) com manual para simular produção.
// Os listeners de projeção SEM containerFactory explícito herdarão MANUAL do contexto →
// mensagens ficam unacknowledged → testes FALHAM (FASE RED).
// Após adicionar autoAckContainerFactory + aplicar nos 4 listeners → VERDE.
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.rabbitmq.listener.simple.acknowledge-mode=manual"
)
@DisplayName("AT-1.2.1 — Auto ACK em listeners de projeção MongoDB")
class ProjectionAckIntegrationTest extends AbstractMongoIntegrationTest {

    @Autowired
    private OrderHistoryRepository orderHistoryRepository;

    private UUID userId;
    private UUID walletId;
    private UUID orderId;
    private UUID correlationId;

    @BeforeEach
    void setup() {
        userId        = UUID.randomUUID();
        walletId      = UUID.randomUUID();
        orderId       = UUID.randomUUID();
        correlationId = UUID.randomUUID();

        // Estado limpo entre testes — evita vazamento de documentos entre cenários
        orderHistoryRepository.deleteAll();
    }

    // =========================================================================
    // TC-ACK-1: fila esvazia após processamento (validação via Management API)
    // =========================================================================

    /**
     * [AT-1.2.1 / TC-ACK-1] — FASE RED → GREEN.
     *
     * <p><strong>Cenário:</strong> publica um {@code OrderReceivedEvent} para a fila de
     * projeção e verifica que a fila fica com zero mensagens (incluindo {@code unacknowledged})
     * após o listener processar.</p>
     *
     * <p><strong>Falha antes da correção (RED):</strong> listener herda ACK manual global
     * e não chama {@code basicAck()} → {@code messages_unacknowledged=1} permanece →
     * total {@code messages=1} → {@code await()} expira com {@code ConditionTimeoutException}.</p>
     *
     * <p><strong>Passa após a correção (GREEN):</strong> {@code autoAckContainerFactory} com
     * {@code AcknowledgeMode.AUTO} → Spring AMQP confirma automaticamente após o return
     * do listener → {@code messages=0}.</p>
     */
    @Test
    @DisplayName("TC-ACK-1: fila de projeção deve esvaziar completamente após processamento (AUTO ACK)")
    void testProjectionReceived_messageIsAcked_queueBecomesEmpty() {
        // GIVEN: publica OrderReceivedEvent via vibranium.events exchange
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.EVENTS_EXCHANGE,
                RabbitMQConfig.RK_ORDER_RECEIVED,
                buildOrderReceivedEvent()
        );

        // WHEN: aguarda o MongoDB confirmar que o listener processou a mensagem.
        // Esta etapa passa em AMBOS os estados (RED e GREEN) — o listener PROCESSA,
        // apenas não ACKa em RED.
        await().atMost(15, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(
                       orderHistoryRepository.findById(orderId.toString()))
                       .as("Documento deve existir no MongoDB após processamento do listener")
                       .isPresent());

        // THEN: o total de mensagens na fila (ready + unacknowledged) deve ser zero.
        // RED  → messages_unacknowledged=1 → total=1 → falha (await expira).
        // GREEN → AUTO ACK confirma → messages_unacknowledged=0 → total=0 → passa.
        await().atMost(5, TimeUnit.SECONDS)
               .untilAsserted(() ->
                   assertThat(getQueueTotalMessages(RabbitMQConfig.QUEUE_ORDER_PROJECTION_RECEIVED))
                           .as("Fila '%s' deve ter 0 mensagens (ready+unacknowledged) após AUTO ACK"
                                   .formatted(RabbitMQConfig.QUEUE_ORDER_PROJECTION_RECEIVED))
                           .isZero()
               );
    }

    // =========================================================================
    // TC-ACK-2: documento MongoDB criado e fila esvaziada (validação end-to-end)
    // =========================================================================

    /**
     * [AT-1.2.1 / TC-ACK-2] — FASE RED → GREEN.
     *
     * <p><strong>Cenário:</strong> valida o fluxo end-to-end: publicação do evento →
     * persistência correta no MongoDB → fila esvaziada pelo ACK automático.</p>
     *
     * <p>Combina as duas asserções críticas:</p>
     * <ol>
     *   <li>Documento criado com {@code userId}, {@code orderId} e entrada {@code ORDER_RECEIVED}
     *       no histórico — garante que a projeção funcionou corretamente.</li>
     *   <li>Fila vazia após ACK — garante que mensagens não acumulam no broker.</li>
     * </ol>
     *
     * <p><strong>Falha antes da correção (RED):</strong> asserção da fila falha com
     * {@code messages=1} (mesmo após MongoDB confirmado).</p>
     */
    @Test
    @DisplayName("TC-ACK-2: documento MongoDB criado com dados corretos e fila vazia após OrderReceivedEvent")
    void testProjectionMatch_messageIsAcked_afterMongoPersistence() {
        // GIVEN
        OrderReceivedEvent event = buildOrderReceivedEvent();
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.EVENTS_EXCHANGE,
                RabbitMQConfig.RK_ORDER_RECEIVED,
                event
        );

        // THEN (1/2): documento persistido corretamente no MongoDB
        await().atMost(15, TimeUnit.SECONDS)
               .untilAsserted(() -> {
                   Optional<OrderDocument> doc = orderHistoryRepository.findById(orderId.toString());
                   assertThat(doc)
                           .as("Documento OrderDocument deve ser criado no MongoDB ao receber OrderReceivedEvent")
                           .isPresent();

                   assertThat(doc.get().getHistory())
                           .as("Histórico deve conter entrada do tipo ORDER_RECEIVED")
                           .anyMatch(h -> h.eventType().equals("ORDER_RECEIVED"));

                   assertThat(doc.get().getUserId())
                           .as("Campo userId deve ser preenchido com o valor do evento")
                           .isEqualTo(userId.toString());
               });

        // THEN (2/2): fila de projeção esvaziada após ACK automático
        // RED  → messages=1 (unacknowledged) → await expira → FALHA.
        // GREEN → messages=0 (acked) → PASSA.
        await().atMost(5, TimeUnit.SECONDS)
               .untilAsserted(() ->
                   assertThat(getQueueTotalMessages(RabbitMQConfig.QUEUE_ORDER_PROJECTION_RECEIVED))
                           .as("Fila deve estar vazia: mensagem ACKed após persistência bem-sucedida no MongoDB")
                           .isZero()
               );
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private OrderReceivedEvent buildOrderReceivedEvent() {
        return OrderReceivedEvent.of(
                correlationId,
                orderId,
                userId,
                walletId,
                OrderType.BUY,
                new BigDecimal("50000.00"),
                new BigDecimal("0.5")
        );
    }

    /**
     * Consulta o total de mensagens em uma fila via RabbitMQ Management HTTP API.
     *
     * <p>O campo {@code messages} retornado pela API equivale a
     * {@code messages_ready + messages_unacknowledged}. Esta métrica revela se há
     * mensagens entregues mas ainda não confirmadas (ACK pendente), o que o simple
     * {@code messages_ready} (via {@code RabbitAdmin}) não detecta.</p>
     *
     * @param queueName nome da fila (ex: {@code order.projection.received})
     * @return total de mensagens na fila ({@code messages_ready + messages_unacknowledged})
     */
    @SuppressWarnings("unchecked")
    private int getQueueTotalMessages(String queueName) {
        // Monta URL da Management API com vhost "/" encoded como %2F.
        // URI.create() aceita a URL já codificada e evita double-encoding pelo RestTemplate
        // (sem URI.create, RestTemplate re-codificaria % → %25, gerando %252F → 404).
        URI uri = URI.create(String.format("http://%s:%d/api/queues/%%2F/%s",
                RABBITMQ.getHost(),
                RABBITMQ.getMappedPort(15672),
                queueName));

        // RestTemplate com autenticação Basic usando as credenciais do container de teste
        RestTemplate restTemplate = new RestTemplateBuilder()
                .basicAuthentication(RABBITMQ.getAdminUsername(), RABBITMQ.getAdminPassword())
                .build();

        Map<String, Object> queueInfo = restTemplate.getForObject(uri, Map.class);
        assertThat(queueInfo)
                .as("RabbitMQ Management API deve retornar dados da fila '%s'".formatted(queueName))
                .isNotNull();

        // "messages" = messages_ready + messages_unacknowledged
        Number messages = (Number) queueInfo.get("messages");
        return messages != null ? messages.intValue() : 0;
    }
}
