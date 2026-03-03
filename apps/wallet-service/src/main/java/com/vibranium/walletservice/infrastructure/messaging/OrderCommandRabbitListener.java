package com.vibranium.walletservice.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.vibranium.contracts.commands.wallet.ReleaseFundsCommand;
import com.vibranium.contracts.commands.wallet.ReserveFundsCommand;
import com.vibranium.contracts.commands.wallet.SettleFundsCommand;
import com.vibranium.walletservice.application.service.WalletService;
import com.vibranium.walletservice.domain.repository.IdempotencyKeyRepository;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Listener de comandos enviados pelo {@code order-service} via RabbitMQ.
 *
 * <p>Escuta três filas de comandos:</p>
 * <ul>
 *   <li>{@code wallet.commands.reserve-funds} — fila dedicada com DLQ para {@link ReserveFundsCommand}.</li>
 *   <li>{@code wallet.commands.release-funds} — fila dedicada com DLQ para {@link ReleaseFundsCommand} (compensação Saga).</li>
 *   <li>{@code wallet.commands} — fila genérica para {@link SettleFundsCommand}.</li>
 * </ul>
 *
 * <p>O roteamento entre os tipos é feito pelo header AMQP {@code type},
 * definido pelos produtores como {@code <FQN da classe do comando>}.</p>
 *
 * <p>Política de ACK manual:</p>
 * <ul>
 *   <li>Sem {@code messageId} — NACK sem requeue (não há garantia de idempotência).</li>
 *   <li>Já processado — ACK sem reprocessar (idempotência).</li>
 *   <li>Tipo desconhecido — ACK para evitar dead-letter acidental.</li>
 *   <li>Sucesso — ACK após commit.</li>
 *   <li>Falha inesperada — NACK sem requeue (poison pill prevention).</li>
 * </ul>
 *
 * <p><strong>Rastreabilidade (AT-14.2):</strong> cada branch de comando envolve seu corpo em
 * {@code try (MDC.putCloseable("correlationId", ...))} para que {@code correlationId}
 * e {@code orderId} (ou {@code matchId} para {@link SettleFundsCommand}) apareçam em
 * todas as linhas de log do processamento do comando. O MDC é limpo automaticamente
 * ao final do try-with-resources, prevenindo vazamento em threads do pool AMQP.</p>
 */
@Component
public class OrderCommandRabbitListener {

    private static final Logger logger = LoggerFactory.getLogger(OrderCommandRabbitListener.class);

    private final WalletService walletService;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final ObjectMapper objectMapper;
    // AT-14.1: Micrometer Tracing — enriquece o span do listener com atributos de domínio.
    // O Spring AMQP gera automaticamente um span para cada mensagem recebida pelo @RabbitListener.
    // Adicionamos saga.correlation_id e order.id para que o trace no Jaeger mostre:
    //   order-service:placeOrder → wallet-service:ReserveFundsCommand (saga.correlation_id=...)
    private final Tracer tracer;

    public OrderCommandRabbitListener(WalletService walletService,
                                       IdempotencyKeyRepository idempotencyKeyRepository,
                                       ObjectMapper objectMapper,
                                       Tracer tracer) {
        this.walletService = walletService;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.objectMapper = objectMapper;
        this.tracer = tracer;
    }

    /**
     * Processa comandos de carteira recebidos nas filas de comando:
     * <ul>
     *   <li>{@code wallet.commands.reserve-funds} — fila dedicada com DLQ para reserva de saldo.</li>
     *   <li>{@code wallet.commands.release-funds} — fila dedicada com DLQ para liberação compensatória.</li>
     *   <li>{@code wallet.commands} — fila genérica para liquidação de fundos ({@link SettleFundsCommand}).</li>
     * </ul>
     *
     * <p>As três filas compartilham o mesmo handler: o roteamento entre tipos é feito pelo
     * header AMQP {@code type} (FQN da classe do comando). Manter um único método reduz
     * duplicação e garante consistência na política de ACK/NACK.</p>
     *
     * @param message  Mensagem AMQP raw.
     * @param channel  Canal AMQP para ACK/NACK manual.
     * @throws Exception Propagado para erros de I/O no canal AMQP.
     */
    @RabbitListener(queues = {"wallet.commands.reserve-funds", "wallet.commands.release-funds", "wallet.commands"})
    public void handleCommand(Message message, Channel channel) throws Exception {
        String messageId = message.getMessageProperties().getMessageId();
        long deliveryTag = message.getMessageProperties().getDeliveryTag();

        // Rejeita mensagens sem messageId — idempotência não pode ser garantida
        if (messageId == null || messageId.isBlank()) {
            logger.warn("Wallet command received without messageId — NACKing without requeue");
            channel.basicNack(deliveryTag, false, false);
            return;
        }

        // Pré-verificação de idempotência (evita overhead de transação em duplicatas)
        if (idempotencyKeyRepository.existsById(messageId)) {
            logger.info("Command {} already processed — ACKing idempotently", messageId);
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            // Identifica o tipo de comando pelo header AMQP 'type' (FQN da classe)
            String commandType = message.getMessageProperties().getType();
            String body = new String(message.getBody(), StandardCharsets.UTF_8);

            if (ReserveFundsCommand.class.getName().equals(commandType)) {
                ReserveFundsCommand cmd = objectMapper.readValue(body, ReserveFundsCommand.class);
                // AT-14.2: MDC popula correlationId e orderId para rastreabilidade em todos os
                // logs desta execução. try-with-resources remove correlationId automaticamente;
                // finally remove orderId — sem memory leak em threads do pool AMQP reutilizadas.
                try (var ignoredCorr = MDC.putCloseable("correlationId", cmd.correlationId().toString())) {
                    MDC.put("orderId", cmd.orderId().toString());
                    try {
                        // AT-14.1: Enriquece o span do listener com atributos de domínio.
                        // O trace no Jaeger mostrará: order-service:placeOrder → wallet-service:ReserveFunds
                        // com saga.correlation_id permitindo correlacionar ao trace do order-service.
                        enrichSpan(cmd.correlationId(), cmd.orderId());
                        walletService.reserveFunds(cmd, messageId);
                        logger.info("ReserveFundsCommand processed: walletId={}, messageId={}",
                                cmd.walletId(), messageId);
                    } finally {
                        MDC.remove("orderId");
                    }
                }

            } else if (ReleaseFundsCommand.class.getName().equals(commandType)) {
                ReleaseFundsCommand cmd = objectMapper.readValue(body, ReleaseFundsCommand.class);
                // AT-14.2: MDC popula correlationId e orderId para rastreabilidade no caminho compensatório.
                try (var ignoredCorr = MDC.putCloseable("correlationId", cmd.correlationId().toString())) {
                    MDC.put("orderId", cmd.orderId().toString());
                    try {
                        enrichSpan(cmd.correlationId(), cmd.orderId());
                        walletService.releaseFunds(cmd, messageId);
                        logger.info("ReleaseFundsCommand processed: walletId={}, messageId={}",
                                cmd.walletId(), messageId);
                    } finally {
                        MDC.remove("orderId");
                    }
                }

            } else if (SettleFundsCommand.class.getName().equals(commandType)) {
                SettleFundsCommand cmd = objectMapper.readValue(body, SettleFundsCommand.class);
                // AT-14.2: para SettleFundsCommand, matchId identifica o trade em liquidação —
                // usado como orderId no MDC para rastreabilidade do settlement no log.
                try (var ignoredCorr = MDC.putCloseable("correlationId", cmd.correlationId().toString())) {
                    MDC.put("orderId", cmd.matchId().toString());
                    try {
                        // AT-14.1: SettleFundsCommand também carrega correlationId da Saga
                        // (via matchId); mapeamos matchId como order.id para visível no Jaeger.
                        enrichSpanSettle(cmd.correlationId(), cmd.matchId());
                        walletService.settleFunds(cmd, messageId);
                        logger.info("SettleFundsCommand processed: matchId={}, messageId={}",
                                cmd.matchId(), messageId);
                    } finally {
                        MDC.remove("orderId");
                    }
                }

            } else {
                // Tipo não reconhecido: tenta inferir pelo conteúdo do JSON
                logger.warn("Unknown command type header: '{}' — attempting JSON inference for messageId={}",
                        commandType, messageId);

                if (body.contains("\"walletId\"") && body.contains("\"asset\"")) {
                    // Heurística: ReserveFundsCommand contém walletId e asset
                    ReserveFundsCommand cmd = objectMapper.readValue(body, ReserveFundsCommand.class);
                    walletService.reserveFunds(cmd, messageId);
                } else if (body.contains("\"matchId\"") && body.contains("\"buyerWalletId\"")) {
                    // Heurística: SettleFundsCommand contém matchId e buyerWalletId
                    SettleFundsCommand cmd = objectMapper.readValue(body, SettleFundsCommand.class);
                    walletService.settleFunds(cmd, messageId);
                } else {
                    logger.error("Cannot infer command type for messageId={} — ACKing to prevent poison pill", messageId);
                }
            }

            channel.basicAck(deliveryTag, false);

        } catch (Exception e) {
            logger.error("Failed to process wallet command messageId={}: {}", messageId, e.getMessage(), e);
            // NACK sem requeue — em produção configurar dead-letter exchange para análise
            channel.basicNack(deliveryTag, false, false);
        }
    }

    // =========================================================================
    // AT-14.1 — Span enrichment helpers
    // =========================================================================

    /**
     * Enriquece o span ativo com atributos de domínio do {@code ReserveFundsCommand}.
     *
     * <p>{@code tracer.currentSpan()} retorna o span criado pelo
     * {@code RabbitListenerObservation} do Spring AMQP ao receber a mensagem.
     * Retornará {@code null} se nenhum span estiver ativo (ex.: testes sem bridge).</p>
     *
     * @param correlationId ID de correlação da Saga — visível no Jaeger como {@code saga.correlation_id}.
     * @param orderId       UUID da ordem — visível como {@code order.id}.
     */
    private void enrichSpan(java.util.UUID correlationId, java.util.UUID orderId) {
        io.micrometer.tracing.Span currentSpan = tracer.currentSpan();
        if (currentSpan != null && correlationId != null && orderId != null) {
            currentSpan
                    .tag("saga.correlation_id", correlationId.toString())
                    .tag("order.id",            orderId.toString());
        }
    }

    /**
     * Enriquece o span ativo com atributos de domínio do {@code SettleFundsCommand}.
     *
     * @param correlationId ID de correlação da Saga.
     * @param matchId       UUID do match executado — mapeado como {@code order.id} para
     *                      manter consistência semântica no Jaeger.
     */
    private void enrichSpanSettle(java.util.UUID correlationId, java.util.UUID matchId) {
        io.micrometer.tracing.Span currentSpan = tracer.currentSpan();
        if (currentSpan != null && correlationId != null) {
            currentSpan.tag("saga.correlation_id", correlationId.toString());
            if (matchId != null) {
                currentSpan.tag("order.id", matchId.toString());
            }
        }
    }
}
