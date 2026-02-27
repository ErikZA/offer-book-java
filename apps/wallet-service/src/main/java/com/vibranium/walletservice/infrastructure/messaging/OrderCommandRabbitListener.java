package com.vibranium.walletservice.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.vibranium.contracts.commands.wallet.ReserveFundsCommand;
import com.vibranium.contracts.commands.wallet.SettleFundsCommand;
import com.vibranium.walletservice.application.service.WalletService;
import com.vibranium.walletservice.domain.repository.IdempotencyKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Listener de comandos enviados pelo {@code order-service} via RabbitMQ.
 *
 * <p>Escuta a fila {@code wallet.commands}, que recebe dois tipos de comandos:</p>
 * <ul>
 *   <li>{@link ReserveFundsCommand} — bloqueia saldo antes de adicionar ordem ao livro.</li>
 *   <li>{@link SettleFundsCommand} — liquida fundos após match confirmado pelo motor.</li>
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
 */
@Component
public class OrderCommandRabbitListener {

    private static final Logger logger = LoggerFactory.getLogger(OrderCommandRabbitListener.class);

    private final WalletService walletService;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final ObjectMapper objectMapper;

    public OrderCommandRabbitListener(WalletService walletService,
                                       IdempotencyKeyRepository idempotencyKeyRepository,
                                       ObjectMapper objectMapper) {
        this.walletService = walletService;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Processa comandos de carteira recebidos na fila {@code wallet.commands}.
     *
     * @param message  Mensagem AMQP raw.
     * @param channel  Canal AMQP para ACK/NACK manual.
     * @throws Exception Propagado para erros de I/O no canal AMQP.
     */
    @RabbitListener(queues = "wallet.commands")
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
                walletService.reserveFunds(cmd, messageId);
                logger.info("ReserveFundsCommand processed: walletId={}, messageId={}",
                        cmd.walletId(), messageId);

            } else if (SettleFundsCommand.class.getName().equals(commandType)) {
                SettleFundsCommand cmd = objectMapper.readValue(body, SettleFundsCommand.class);
                walletService.settleFunds(cmd, messageId);
                logger.info("SettleFundsCommand processed: matchId={}, messageId={}",
                        cmd.matchId(), messageId);

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
}
