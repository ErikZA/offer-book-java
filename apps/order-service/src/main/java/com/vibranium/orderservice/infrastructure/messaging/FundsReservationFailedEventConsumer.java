package com.vibranium.orderservice.infrastructure.messaging;

import com.rabbitmq.client.Channel;
import com.vibranium.contracts.enums.OrderStatus;
import com.vibranium.contracts.events.wallet.FundsReservationFailedEvent;
import com.vibranium.orderservice.infrastructure.redis.RedisMatchEngineAdapter;
import com.vibranium.orderservice.config.RabbitMQConfig;
import com.vibranium.orderservice.domain.model.Order;
import com.vibranium.orderservice.domain.model.ProcessedEvent;
import com.vibranium.orderservice.domain.repository.ProcessedEventRepository;
import com.vibranium.orderservice.domain.repository.OrderRepository;
import io.micrometer.tracing.Tracer;
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
 * Consumidor do evento de falha de reserva de fundos publicado pelo wallet-service.
 *
 * <p>Quando o wallet-service nao consegue bloquear os fundos necessarios
 * (saldo insuficiente, carteira nao encontrada, etc.), publica um
 * {@link FundsReservationFailedEvent}. Este consumidor transiciona
 * a ordem para {@code CANCELLED} e registra o motivo da falha.</p>
 *
 * <p>Esta e a etapa de compensacao da Saga: a ordem sai do estado
 * {@code PENDING} para {@code CANCELLED} sem nunca ter entrado no livro.</p>
 *
 * <p><strong>Estrategia de idempotencia:</strong> INSERT na tabela {@code tb_order_idempotency_keys}
 * com {@code eventId} como PK. Se ja existir ({@link DataIntegrityViolationException}),
 * a mensagem e duplicata e descartada com ACK sem reprocessar.</p>
 *
 * <p><strong>Rastreabilidade (AT-14.2):</strong> {@link org.slf4j.MDC} é populado com
 * {@code correlationId} e {@code orderId} via {@code try-with-resources} antes do primeiro log,
 * garantindo que todas as linhas de log incluam os campos de correlação da Saga.
 * O MDC é limpo automaticamente ao final do processamento, sem memory leak em threads
 * do pool AMQP reutilizadas.</p>
 *
 * <p><strong>Sequencia garantida:</strong>
 * {@code (1) INSERT idempotency_key -> (2) UPDATE Order -> (3) Commit -> (4) basicAck}</p>
 * <p><strong>Sequência garantida (AT-04.1):</strong>
 * {@code (1) INSERT idempotency_key -> (2) ZREM Redis -> (3) UPDATE Order -> (4) Commit -> (5) basicAck}</p>
 *
 * <p><strong>Consistência cross-store (AT-04.1):</strong> A remoção do Redis é executada
 * <em>antes</em> do {@code basicAck} para garantir que, mesmo em caso de reinicialização
 * pós-cancelamento-PostgreSQL e pré-ACK, o Redis esteja limpo no reprocessamento.
 * O {@code ZREM} é idempotente: membros inexistentes são ignorados silenciosamente.</p>
 */
@Component
public class FundsReservationFailedEventConsumer {

    private static final Logger logger = LoggerFactory.getLogger(FundsReservationFailedEventConsumer.class);

    private final OrderRepository            orderRepository;
    private final ProcessedEventRepository   processedEventRepository;
    // AT-14.1: Micrometer Tracing — enriquece o span do listener com atributos da Saga.
    // O span é gerado automaticamente pelo RabbitListenerObservation do Spring AMQP
    // ao receber a mensagem. Adicionamos saga.correlation_id e order.id para
    // rastreabilidade end-to-end no Jaeger (placeOrder → FundsReservationFailed).
    private final Tracer                     tracer;

    /**
     * Adaptador do Redis Order Book injetado para executar ZREM antes do basicAck (AT-04.1).
     * Garante consistência entre o estado transacional (PostgreSQL CANCELLED) e o
     * livro de ofertas em memória (Redis Sorted Set).
     */
    private final RedisMatchEngineAdapter     redisAdapter;

    /**
     * Cria o consumidor com todas as dependências obrigatórias.
     *
     * @param orderRepository          repositório JPA das ordens.
     * @param processedEventRepository repositório de idempotência por eventId.
     * @param redisAdapter             adaptador do Redis Order Book; usado para
     *                                 executar {@code ZREM} antes do {@code basicAck}
     *                                 garantindo consistência cross-store (AT-04.1).
     */
    public FundsReservationFailedEventConsumer(OrderRepository orderRepository,
                                               ProcessedEventRepository processedEventRepository,
                                               Tracer tracer,
                                               RedisMatchEngineAdapter redisAdapter) {
        this.orderRepository          = orderRepository;
        this.processedEventRepository = processedEventRepository;
        this.tracer                  = tracer;
        this.redisAdapter             = redisAdapter;
    }

    /**
     * Processa a falha de reserva de fundos com ACK manual e idempotencia por tabela.
     *
     * <p>O {@code containerFactory = "manualAckContainerFactory"} habilita ACK manual.
     * O ACK so e enviado apos o commit JPA, eliminando a janela de duplicacao.</p>
     *
     * @param event       evento de falha publicado pelo wallet-service.
     * @param channel     canal AMQP para envio do ACK/NACK manual.
     * @param deliveryTag tag de entrega fornecida pelo broker.
     * @throws Exception  se o ACK/NACK manual falhar ou ocorrer erro de I/O no canal AMQP;
     *                    o container RabbitMQ trata a exceção e reenvia a mensagem.
     */
    @RabbitListener(
            queues = RabbitMQConfig.QUEUE_FUNDS_FAILED,
            containerFactory = "manualAckContainerFactory"
    )
    @Transactional
    public void onFundsReservationFailed(FundsReservationFailedEvent event,
                                          Channel channel,
                                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws Exception {
        String eventId = event.eventId().toString();

        // AT-14.2: MDC garante que todas as linhas de log desta execução incluam correlationId
        // e orderId sem passá-los manualmente a cada chamada de logger. O try-with-resources
        // remove correlationId automaticamente ao sair do bloco, prevenindo memory leak em
        // threads do pool AMQP (ThreadLocals são reutilizadas pelo Spring AMQP).
        try (var ignoredCorr = MDC.putCloseable("correlationId", event.correlationId().toString())) {
            MDC.put("orderId", event.orderId().toString());
            try {
                logger.info("FundsReservationFailedEvent recebido: eventId={} correlationId={} orderId={} reason={}",
                        eventId, event.correlationId(), event.orderId(), event.reason());

                // 1. Idempotencia por tabela: INSERT com eventId como PK unica
                //    DataIntegrityViolationException indica duplicata -> descarta com ACK
                try {
                    processedEventRepository.saveAndFlush(new ProcessedEvent(event.eventId()));
                } catch (DataIntegrityViolationException ex) {
                    logger.info("FundsReservationFailedEvent duplicado (idempotente): eventId={}", eventId);
                    channel.basicAck(deliveryTag, false);
                    return;
                }

                // 2. Localiza a ordem pelo correlationId da Saga
                Optional<Order> orderOpt = orderRepository.findByCorrelationId(event.correlationId());
                if (orderOpt.isEmpty()) {
                    logger.warn("Ordem nao encontrada para correlationId={} -- descartando evento",
                            event.correlationId());
                    channel.basicAck(deliveryTag, false);
                    return;
                }

                Order order = orderOpt.get();

                // AT-14.1: Enriquece o span ativo com atributos de domínio.
                // Permite ao Jaeger exibir o trecho de cancelação da Saga com o contexto correto.
                io.micrometer.tracing.Span currentSpan = tracer.currentSpan();
                if (currentSpan != null) {
                    currentSpan
                            .tag("saga.correlation_id", event.correlationId().toString())
                            .tag("order.id",            event.orderId().toString());
                }

                // 3. Cancela a ordem com o motivo tecnico da falha
                //    A idempotencia ja e garantida pela PK da tabela acima;
                //    o check de status abaixo e uma defesa extra contra corrupcao de dados.
                if (order.getStatus() == OrderStatus.CANCELLED) {
                    logger.debug("Ordem ja cancelada (defensivo): orderId={}", order.getId());
                      // AT-04.1: mesmo no caminho defensivo, garante remocao do Redis
                    // (idempotente: ZREM em membro inexistente e silencioso)
                    redisAdapter.removeFromBook(order.getId(), order.getOrderType());
                    channel.basicAck(deliveryTag, false);
                    return;
                }

                String reason = event.reason() != null ? event.reason().name() : "UNKNOWN";
                String detail = event.detail() != null
                        ? reason + ": " + event.detail()
                        : reason;


                                
                // 4. AT-04.1: Remove do Redis Order Book ANTES do basicAck.
                //    Garante que a ordem cancelada nao seja retornada como contraparte pelo
                //    motor de match, evitando MatchExecutedEvent invalido e tentativa de
                //    liquidacao com fundos inexistentes no wallet-service.
                //    Ordem de execucao obrigatoria: ZREM -> UPDATE Postgres -> basicAck
                redisAdapter.removeFromBook(order.getId(), order.getOrderType());

                order.cancel(detail);
                orderRepository.save(order);

                logger.info("Ordem cancelada e removida do Redis: orderId={} correlationId={} reason={}",
                        order.getId(), event.correlationId(), reason);

                // 5. ACK manual apos ZREM + commit bem-sucedido do JPA
                channel.basicAck(deliveryTag, false);

            } finally {
                // Remove orderId explicitamente; correlationId é removido pelo try-with-resources.
                MDC.remove("orderId");
            }
        }
    }
}
