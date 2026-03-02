package com.vibranium.walletservice.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.vibranium.contracts.commands.wallet.ReserveFundsCommand;
import com.vibranium.contracts.enums.AssetType;
import com.vibranium.walletservice.AbstractIntegrationTest;
import com.vibranium.walletservice.application.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

/**
 * [RED] AT-07.1 — Valida que mensagens NACKed com requeue=false são roteadas
 * automaticamente para {@code wallet.commands.reserve-funds.dlq} via DLX.
 *
 * <p><b>FASE RED:</b> Ambos os testes falham antes da implementação porque:</p>
 * <ol>
 *   <li>A fila {@code wallet.commands.reserve-funds} ainda não existe.</li>
 *   <li>O exchange {@code vibranium.dlq} ainda não está declarado.</li>
 *   <li>A fila DLQ {@code wallet.commands.reserve-funds.dlq} ainda não existe.</li>
 * </ol>
 *
 * <p>Após a implementação (Green), todos os testes deverão passar sem alteração.</p>
 *
 * <p><b>Estratégia de falha forçada:</b> {@code WalletService} é mockado via
 * {@code @MockBean} para lançar {@link RuntimeException} em todo {@code reserveFunds},
 * simulando uma falha permanente (poison pill). Isso faz o listener executar
 * {@code channel.basicNack(deliveryTag, false, false)}, acionando o DLX configurado
 * na fila principal.</p>
 *
 * <p><b>Isolamento:</b> {@code @MockBean} força criação de um ApplicationContext
 * dedicado a esta classe, sem interferir nos demais testes de integração.</p>
 */
@DisplayName("[RED] AT-07.1 — DLQ routing para wallet.commands.reserve-funds")
class ReserveFundsDlqIntegrationTest extends AbstractIntegrationTest {

    /**
     * Nome da fila principal que deve ter x-dead-letter-exchange configurado.
     * Declarada em {@link com.vibranium.walletservice.config.RabbitMQConfig}.
     */
    private static final String QUEUE_RESERVE_FUNDS     = "wallet.commands.reserve-funds";

    /**
     * Nome da Dead Letter Queue onde mensagens rejeitadas devem aparecer.
     */
    private static final String QUEUE_RESERVE_FUNDS_DLQ = "wallet.commands.reserve-funds.dlq";

    /**
     * Exchange DLX usado como dead-letter-exchange na fila principal.
     */
    private static final String DLQ_EXCHANGE            = "vibranium.dlq";

    /**
     * Routing key usada pelo order-service para publicar comandos de reserva.
     */
    private static final String ROUTING_KEY_RESERVE     = "wallet.command.reserve-funds";

    /**
     * Exchange de comandos do wallet-service.
     */
    private static final String WALLET_COMMANDS_EXCHANGE = "wallet.commands";

    /**
     * Mock do WalletService para simular falha permanente no processamento.
     * A cada chamada de reserveFunds, lança RuntimeException intencionalmente.
     */
    @MockBean
    private WalletService walletService;

    @BeforeEach
    void purgeDlqBeforeEach() {
        // Garante isolamento total entre execuções: purga ambas as filas
        try {
            rabbitAdmin.purgeQueue(QUEUE_RESERVE_FUNDS, false);
        } catch (Exception ignored) {
            // Fila ainda não existe — falha esperada na FASE RED
        }
        try {
            rabbitAdmin.purgeQueue(QUEUE_RESERVE_FUNDS_DLQ, false);
        } catch (Exception ignored) {
            // Fila DLQ ainda não existe — falha esperada na FASE RED
        }
    }

    // =========================================================================
    // Teste 1 — Validação ESTRUTURAL: x-dead-letter-exchange na fila principal
    // =========================================================================

    /**
     * [RED] Verifica que a fila {@code wallet.commands.reserve-funds} foi declarada
     * com os argumentos de DLX obrigatórios.
     *
     * <p><b>Estratégia:</b> Usa a Management HTTP API do RabbitMQ
     * ({@code GET /api/queues/%2F/{name}}) para inspecionar os argumentos da fila,
     * pois {@link org.springframework.amqp.core.QueueInformation} não expõe metadados
     * de argumentos — apenas {@code messageCount} e {@code consumerCount}.</p>
     *
     * <p>O container usa {@code rabbitmq:3.13-management-alpine}, que habilita o
     * plugin {@code rabbitmq_management} por padrão, expondo a API em porta 15672.</p>
     *
     * <p>Falha na FASE RED porque a fila não existe (não foi declarada com DLX args).
     * Passa na FASE GREEN após a implementação em {@code RabbitMQConfig}.</p>
     */
    @Test
    @DisplayName("[RED] Fila reserve-funds deve ter x-dead-letter-exchange configurado")
    void reserveFundsQueue_shouldHaveDeadLetterExchangeArguments() throws Exception {
        // Arrange — monta URI da Management HTTP API
        // URLEncoder garante que caracteres especiais no nome da fila sejam escapados
        String queueEncoded = URLEncoder.encode(QUEUE_RESERVE_FUNDS, StandardCharsets.UTF_8);
        String url = String.format("http://%s:%d/api/queues/%%2F/%s",
                RABBIT.getHost(),
                RABBIT.getMappedPort(15672),
                queueEncoded);

        // Credenciais básicas do container RabbitMQ
        String credentials = RABBIT.getAdminUsername() + ":" + RABBIT.getAdminPassword();
        String basicAuth = Base64.getEncoder().encodeToString(
                credentials.getBytes(StandardCharsets.UTF_8));

        // Act — chama a Management HTTP API via java.net.http.HttpClient (Java 11+)
        // Não requer dependência externa; disponível em todos os ambientes JDK 11+
        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Basic " + basicAuth)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofString());

        // Assert — o endpoint deve retornar 200 (fila existe no broker)
        assertThat(response.statusCode())
                .as("Management API deve retornar 200 para fila '%s'. " +
                    "Status %d indica que a fila ainda não está declarada.",
                    QUEUE_RESERVE_FUNDS, response.statusCode())
                .isEqualTo(200);

        // Parse do JSON para inspecionar os argumentos da fila
        JsonNode queueJson = objectMapper.readTree(response.body());
        JsonNode arguments  = queueJson.path("arguments");

        assertThat(arguments.isMissingNode() || arguments.isNull())
                .as("Fila '%s' deve ter argumentos configurados no broker", QUEUE_RESERVE_FUNDS)
                .isFalse();

        assertThat(arguments.path("x-dead-letter-exchange").asText(""))
                .as("x-dead-letter-exchange deve ser '%s'", DLQ_EXCHANGE)
                .isEqualTo(DLQ_EXCHANGE);

        assertThat(arguments.path("x-dead-letter-routing-key").asText(""))
                .as("x-dead-letter-routing-key deve ser '%s'", QUEUE_RESERVE_FUNDS_DLQ)
                .isEqualTo(QUEUE_RESERVE_FUNDS_DLQ);
    }

    // =========================================================================
    // Teste 2 — Validação COMPORTAMENTAL: mensagem NACKed vai para DLQ
    // =========================================================================

    /**
     * [RED] Verifica o fluxo completo de dead-letter:
     * <ol>
     *   <li>Publica um {@link ReserveFundsCommand} válido na exchange principal.</li>
     *   <li>O {@code WalletService} mockado lança {@link RuntimeException} — simula poison pill.</li>
     *   <li>O listener executa {@code basicNack(tag, false, false)} — sem requeue.</li>
     *   <li>O RabbitMQ roteia a mensagem para {@code vibranium.dlq} com a routing key DLQ.</li>
     *   <li>A mensagem aparece em {@code wallet.commands.reserve-funds.dlq}.</li>
     * </ol>
     *
     * <p>Falha na FASE RED porque nem a fila principal com DLX, nem a DLQ existem.
     * Passa na FASE GREEN após as declarações em {@code RabbitMQConfig}.</p>
     */
    @Test
    @DisplayName("[RED] Mensagem com falha permanente deve ser roteada para wallet.commands.reserve-funds.dlq")
    void reserveFundsCommand_whenListenerThrows_shouldRouteMessageToDlq() throws Exception {
        // Arrange — configura o mock para simular falha permanente (ex: NPE, estado inválido)
        // O WalletService lança RuntimeException em qualquer chamada de reserveFunds.
        // Essa é a condição de "poison pill": mensagem que nunca pode ser processada.
        doThrow(new RuntimeException("Simulated permanent failure — wallet state invalid"))
                .when(walletService)
                .reserveFunds(any(ReserveFundsCommand.class), anyString());

        // Arrange — monta a mensagem AMQP com o contrato correto
        String messageId = UUID.randomUUID().toString();
        ReserveFundsCommand command = new ReserveFundsCommand(
                UUID.randomUUID(),           // commandId
                UUID.randomUUID(),           // orderId
                UUID.randomUUID(),           // walletId
                AssetType.BRL,
                new BigDecimal("100.00")
        );

        String json = objectMapper.writeValueAsString(command);

        MessageProperties props = new MessageProperties();
        props.setMessageId(messageId);
        // Header 'type' usado pelo listener para identificar o tipo de comando
        props.setType(ReserveFundsCommand.class.getName());
        Message amqpMessage = new Message(json.getBytes(StandardCharsets.UTF_8), props);

        // Act — publica na exchange de comandos com a routing key de reserve-funds
        // A mensagem deve ir para 'wallet.commands.reserve-funds' (binding específico)
        rabbitTemplate.send(WALLET_COMMANDS_EXCHANGE, ROUTING_KEY_RESERVE, amqpMessage);

        // Assert Parte 1: mensagem NÃO deve permanecer na fila principal
        // Aguarda até 5 segundos para o listener processar e rejeitar a mensagem
        await()
                .atMost(5, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    QueueInformation mainQueueInfo = rabbitAdmin.getQueueInfo(QUEUE_RESERVE_FUNDS);
                    assertThat(mainQueueInfo)
                            .as("Fila principal '%s' deve existir", QUEUE_RESERVE_FUNDS)
                            .isNotNull();
                    assertThat(mainQueueInfo.getMessageCount())
                            .as("Fila principal deve estar vazia após NACK sem requeue")
                            .isZero();
                });

        // Assert Parte 2: mensagem DEVE aparecer na DLQ
        // Usa rabbitTemplate.receive com timeout para polling não-destrutivo
        await()
                .atMost(8, TimeUnit.SECONDS)
                .pollInterval(300, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    QueueInformation dlqInfo = rabbitAdmin.getQueueInfo(QUEUE_RESERVE_FUNDS_DLQ);
                    assertThat(dlqInfo)
                            .as("Fila DLQ '%s' deve estar declarada e visível", QUEUE_RESERVE_FUNDS_DLQ)
                            .isNotNull();
                    assertThat(dlqInfo.getMessageCount())
                            .as("DLQ deve conter exatamente 1 mensagem (a rejeitada)")
                            .isGreaterThanOrEqualTo(1);
                });

        // Assert Parte 3: valida conteúdo da mensagem na DLQ
        // receive() é destrutivo mas aqui serve para verificar o payload
        Message dlqMessage = rabbitTemplate.receive(QUEUE_RESERVE_FUNDS_DLQ, 3_000 /* ms */);
        assertThat(dlqMessage)
                .as("Deve ser possível consumir a mensagem da DLQ")
                .isNotNull();

        // Verifica header x-death adicionado automaticamente pelo RabbitMQ
        // Presente em toda mensagem roteada via dead-letter mechanism
        Map<String, Object> headers = dlqMessage.getMessageProperties().getHeaders();
        assertThat(headers)
                .as("Mensagem na DLQ deve conter o header 'x-death' adicionado pelo RabbitMQ")
                .containsKey("x-death");
    }
}
