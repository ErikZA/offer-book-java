package com.vibranium.orderservice.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.vibranium.contracts.commands.wallet.ReleaseFundsCommand;
import com.vibranium.contracts.enums.AssetType;
import com.vibranium.contracts.events.order.MatchExecutedEvent;
import com.vibranium.contracts.events.wallet.FundsSettlementFailedEvent;
import com.vibranium.orderservice.config.RabbitMQConfig;
import com.vibranium.orderservice.domain.model.Order;
import com.vibranium.orderservice.domain.model.OrderOutboxMessage;
import com.vibranium.orderservice.domain.model.ProcessedEvent;
import com.vibranium.orderservice.domain.repository.OrderOutboxRepository;
import com.vibranium.orderservice.domain.repository.OrderRepository;
import com.vibranium.orderservice.domain.repository.ProcessedEventRepository;
import com.vibranium.orderservice.application.service.EventStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Consumidor do evento de falha de liquidação de um trade, publicado pelo wallet-service.
 *
 * <p>Quando o wallet-service não consegue executar o {@code SettleFundsCommand} — por erro ACID,
 * duplicidade ou falha transacional — publica um {@link FundsSettlementFailedEvent}.
 * Este consumidor realiza a compensação da Saga emitindo {@link ReleaseFundsCommand} para
 * <strong>ambas</strong> as carteiras envolvidas no match (buyer + seller), dado que os
 * fundos continuam bloqueados ainda que o trade não tenha sido liquidado.</p>
 *
 * <h3>Fluxo de compensação (AT-1.1.4)</h3>
 * <ol>
 *   <li>Verifica idempotência por {@code eventId} na tabela {@code tb_processed_events}.</li>
 *   <li>Localiza a ordem que disparou o match pelo {@code correlationId} da Saga.</li>
 *   <li>Recupera o {@link MatchExecutedEvent} persistido no outbox durante o processamento
 *       do {@code FundsReservedEvent} — contém IDs de carteira e valores do match.</li>
 *   <li>Grava dois {@link ReleaseFundsCommand} no outbox (mesma transação JPA):<br>
 *       — Comprador: libera {@code matchPrice × matchAmount} BRL.<br>
 *       — Vendedor: libera {@code matchAmount} VIBRANIUM.</li>
 *   <li>Envia {@code basicAck} após commit bem-sucedido.</li>
 * </ol>
 *
 * <h3>Por que 2 ReleaseFundsCommand?</h3>
 * <p>Ao contrário da reserva inicial — onde cada usuário reserva para si — a liquidação
 * usa fundos de <em>ambas</em> as partes. Se ela falha, ambas ficam com saldo bloqueado.
 * A compensação deve cobrir os dois lados para restaurar a consistência financeira.</p>
 *
 * <h3>Lookup do MatchExecutedEvent</h3>
 * <p>O {@link FundsSettlementFailedEvent} não carrega os IDs de carteira diretamente.
 * O consumer usa o {@code correlationId} para encontrar a ordem que gerou o match,
 * e então busca o {@link MatchExecutedEvent} no outbox pelo {@code aggregateId} da ordem.
 * Os registros do outbox são marcados como publicados mas nunca deletados, garantindo
 * disponibilidade para este lookup de compensação.</p>
 *
 * <p><strong>Sequência garantida (AT-1.1.4):</strong>
 * {@code (1) INSERT idempotency_key → (2) FIND order → (3) FIND MatchExecutedEvent outbox
 * → (4) INSERT 2×ReleaseFundsCommand outbox → (5) Commit → (6) basicAck}</p>
 */
@Component
public class FundsSettlementFailedEventConsumer {

    private static final Logger logger = LoggerFactory.getLogger(FundsSettlementFailedEventConsumer.class);

    private final OrderRepository            orderRepository;
    private final OrderOutboxRepository      outboxRepository;
    private final ProcessedEventRepository   processedEventRepository;
    private final ObjectMapper               objectMapper;
    private final EventStoreService          eventStoreService;

    /**
     * Cria o consumidor com todas as dependências obrigatórias via injeção por construtor.
     *
     * @param orderRepository          repositório JPA das ordens.
     * @param outboxRepository         repositório JPA do outbox — usado tanto para lookup
     *                                 do {@code MatchExecutedEvent} quanto para gravar os
     *                                 {@code ReleaseFundsCommand} de compensação.
     * @param processedEventRepository repositório de idempotência por {@code eventId}.
     * @param objectMapper             serializador Jackson para desserializar o payload do
     *                                 {@code MatchExecutedEvent} e serializar os releases.
     */
    public FundsSettlementFailedEventConsumer(OrderRepository orderRepository,
                                              OrderOutboxRepository outboxRepository,
                                              ProcessedEventRepository processedEventRepository,
                                              ObjectMapper objectMapper,
                                              EventStoreService eventStoreService) {
        this.orderRepository          = orderRepository;
        this.outboxRepository         = outboxRepository;
        this.processedEventRepository = processedEventRepository;
        this.objectMapper             = objectMapper;
        this.eventStoreService        = eventStoreService;
    }

    /**
     * Processa o evento de falha de liquidação com ACK manual e idempotência por tabela.
     *
     * <p>O {@code containerFactory = "manualAckContainerFactory"} habilita ACK manual.
     * O ACK só é enviado após o commit JPA, eliminando a janela de duplicação.</p>
     *
     * @param event       evento de falha publicado pelo wallet-service.
     * @param channel     canal AMQP para envio do ACK/NACK manual.
     * @param deliveryTag tag de entrega fornecida pelo broker.
     * @throws Exception  se o ACK/NACK manual falhar ou ocorrer erro de I/O no canal AMQP.
     */
    @RabbitListener(
            queues = RabbitMQConfig.QUEUE_FUNDS_SETTLEMENT_FAILED,
            containerFactory = "manualAckContainerFactory"
    )
    @Transactional
    public void onFundsSettlementFailed(FundsSettlementFailedEvent event,
                                        Channel channel,
                                        @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws Exception {
        String eventId = event.eventId().toString();

        // MDC garante rastreabilidade em todas as linhas de log desta execução (AT-14.2).
        try (var ignoredCorr = MDC.putCloseable("correlationId", event.correlationId().toString())) {
            MDC.put("matchId", event.matchId().toString());
            try {
                logger.warn("FundsSettlementFailedEvent recebido: eventId={} correlationId={} matchId={} reason={}",
                        eventId, event.correlationId(), event.matchId(), event.reason());

                // 1. Idempotência por tabela: INSERT com eventId como PK única.
                //    DataIntegrityViolationException indica duplicata → descarta com ACK.
                try {
                    processedEventRepository.saveAndFlush(new ProcessedEvent(event.eventId()));
                } catch (DataIntegrityViolationException ex) {
                    logger.info("FundsSettlementFailedEvent duplicado (idempotente): eventId={}", eventId);
                    channel.basicAck(deliveryTag, false);
                    return;
                }

                // 2. Localiza a ordem que disparou o match pelo correlationId da Saga.
                //    O correlationId no evento coincide com o correlationId da ordem ingressante
                //    (a que disparou o match via FundsReservedEventConsumer).
                Optional<Order> orderOpt = orderRepository.findByCorrelationId(event.correlationId());
                if (orderOpt.isEmpty()) {
                    logger.warn("Ordem não encontrada para correlationId={} — descartando FundsSettlementFailedEvent",
                            event.correlationId());
                    channel.basicAck(deliveryTag, false);
                    return;
                }

                Order triggeringOrder = orderOpt.get();

                // 3. Recupera o MatchExecutedEvent do outbox para obter os IDs de carteira de
                //    ambos os lados do trade. O outbox preserva registros históricos (apenas
                //    publishedAt é populado após relay, o registro nunca é deletado).
                Optional<OrderOutboxMessage> matchOutboxOpt = outboxRepository
                        .findFirstByAggregateIdAndEventType(triggeringOrder.getId(), "MatchExecutedEvent");

                if (matchOutboxOpt.isEmpty()) {
                    logger.error("MatchExecutedEvent não encontrado no outbox para orderId={} — " +
                            "não é possível emitir ReleaseFundsCommand. Requer intervenção manual.",
                            triggeringOrder.getId());
                    // Envia para DLQ (NACK sem re-enqueue) para rastreamento operacional.
                    channel.basicNack(deliveryTag, false, false);
                    return;
                }

                // 4. Desserializa o MatchExecutedEvent para obter detalhes do trade.
                MatchExecutedEvent matchEvent;
                try {
                    matchEvent = objectMapper.readValue(
                            matchOutboxOpt.get().getPayload(), MatchExecutedEvent.class
                    );
                } catch (JsonProcessingException ex) {
                    logger.error("Falha ao desserializar MatchExecutedEvent do outbox (orderId={}): {}",
                            triggeringOrder.getId(), ex.getMessage());
                    channel.basicNack(deliveryTag, false, false);
                    return;
                }

                // 5. Grava 2 ReleaseFundsCommand no outbox (mesma transação JPA):
                //    — Comprador: libera matchPrice × matchAmount BRL (locked no reserve)
                //    — Vendedor: libera matchAmount VIBRANIUM (locked no reserve)
                emitReleaseFundsCommand(triggeringOrder.getId(), matchEvent);

                logger.warn("Compensação emitida por FundsSettlementFailed: correlationId={} matchId={} " +
                        "buyerWallet={} sellerWallet={} amount={}",
                        event.correlationId(), event.matchId(),
                        matchEvent.buyerWalletId(), matchEvent.sellerWalletId(),
                        matchEvent.matchAmount());

                // 6. ACK após commit JPA bem-sucedido (Outbox Pattern garante entrega eventual)
                channel.basicAck(deliveryTag, false);

            } finally {
                MDC.remove("matchId");
            }
        }
    }

    /**
     * Grava dois {@link ReleaseFundsCommand} no outbox: um para o comprador (BRL) e
     * um para o vendedor (VIBRANIUM), na mesma transação JPA já aberta pelo caller.
     *
     * <p>Valores a liberar:</p>
     * <ul>
     *   <li>Comprador: {@code matchPrice × matchAmount} BRL — exatamente o que foi
     *       bloqueado para cobrir este match (o lock original foi {@code orderPrice × amount};
     *       a parcela deste match foi {@code matchPrice × matchAmount}).</li>
     *   <li>Vendedor: {@code matchAmount} VIBRANIUM — o lock foi proporcional à quantidade.</li>
     * </ul>
     *
     * @param aggregateId ID do agregado raiz (ordem que disparou o match).
     * @param matchEvent  Dados do trade: IDs das carteiras, preço e quantidade.
     */
    private void emitReleaseFundsCommand(java.util.UUID aggregateId, MatchExecutedEvent matchEvent) {
        // Release para o comprador: libera BRL bloqueado = matchPrice × matchAmount
        ReleaseFundsCommand buyerRelease = new ReleaseFundsCommand(
                matchEvent.correlationId(),
                matchEvent.buyOrderId(),
                matchEvent.buyerWalletId(),
                AssetType.BRL,
                matchEvent.matchPrice().multiply(matchEvent.matchAmount()),
                1
        );

        // Release para o vendedor: libera VIBRANIUM bloqueado = matchAmount
        ReleaseFundsCommand sellerRelease = new ReleaseFundsCommand(
                matchEvent.correlationId(),
                matchEvent.sellOrderId(),
                matchEvent.sellerWalletId(),
                AssetType.VIBRANIUM,
                matchEvent.matchAmount(),
                1
        );

        saveToOutbox(aggregateId, buyerRelease);
        saveToOutbox(aggregateId, sellerRelease);
    }

    /**
     * Serializa um {@link ReleaseFundsCommand} e persiste no outbox dentro da
     * transação JPA corrente.
     *
     * @param aggregateId  ID do agregado (orderId que originou o match).
     * @param releaseCmd   Comando a serializar e persistir.
     * @throws IllegalStateException se a serialização Jackson falhar (não-recuperável).
     */
    private void saveToOutbox(java.util.UUID aggregateId, ReleaseFundsCommand releaseCmd) {
        String json;
        try {
            json = objectMapper.writeValueAsString(releaseCmd);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Falha ao serializar ReleaseFundsCommand para outbox (aggregateId=%s): %s"
                            .formatted(aggregateId, e.getMessage()), e);
        }
        outboxRepository.save(new OrderOutboxMessage(
                aggregateId,
                "Order",
                "ReleaseFundsCommand",
                RabbitMQConfig.COMMANDS_EXCHANGE,
                RabbitMQConfig.QUEUE_RELEASE_FUNDS,
                json
        ));

        // AT-14: grava o comando também no Event Store imutável (mesma TX)
        eventStoreService.append(
                java.util.UUID.randomUUID(),
                aggregateId.toString(),
                "Order",
                "ReleaseFundsCommand",
                json,
                java.time.Instant.now(),
                releaseCmd.correlationId(),
                releaseCmd.schemaVersion()
        );
    }
}
