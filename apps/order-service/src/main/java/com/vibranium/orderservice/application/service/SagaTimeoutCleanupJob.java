package com.vibranium.orderservice.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibranium.contracts.enums.AssetType;
import com.vibranium.contracts.enums.FailureReason;
import com.vibranium.contracts.enums.OrderStatus;
import com.vibranium.contracts.enums.OrderType;
import com.vibranium.contracts.events.order.OrderCancelledEvent;
import com.vibranium.contracts.commands.wallet.ReleaseFundsCommand;
import com.vibranium.orderservice.config.RabbitMQConfig;
import com.vibranium.orderservice.domain.model.Order;
import com.vibranium.orderservice.domain.model.OrderOutboxMessage;
import com.vibranium.orderservice.domain.repository.OrderOutboxRepository;
import com.vibranium.orderservice.domain.repository.OrderRepository;
import com.vibranium.orderservice.infrastructure.redis.RedisMatchEngineAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Job periódico de limpeza de ordens expiradas pelo timeout da Saga.
 *
 * <h3>Problema (AT-09.1 / AT-1.1.4)</h3>
 * <p>Toda Saga deve ter ciclo de vida finito. Uma ordem que permanece em {@code PENDING}
 * indefinidamente é uma "ordem zumbi": o usuário não
 * recebe feedback e os recursos ficam em estado inconsistente.</p>
 *
 * <h3>Solução</h3>
 * <p>A cada {@code 60 segundos} (configurável via {@code app.saga.cleanup-delay-ms}),
 * este job busca ordens com:</p>
 * <ul>
 *   <li>{@code status IN (PENDING)}</li>
 *   <li>{@code created_at < now() - app.saga.pending-timeout-minutes}</li>
 * </ul>
 * <p>Para <strong>todas</strong> as ordens expiradas (PENDING), emite
 * {@link OrderCancelledEvent} + {@link ReleaseFundsCommand}. A emissão incondicional
 * de {@code ReleaseFundsCommand} cobre a janela de corrida entre a reserva efetiva
 * no wallet-service e a atualização de status da ordem (PENDING → OPEN): uma ordem
 * PENDING pode já ter fundos bloqueados. O wallet-service trata
 * {@code InsufficientLockedFundsException} graciosamente — se os fundos nunca foram
 * reservados, o release é um no-op idempotente (AT-1.1.4).</p>
 *
 * <h3>Idempotência</h3>
 * <p>O job é naturalmente idempotente: {@code findByStatusInAndCreatedAtBefore} retorna
 * apenas ordens não-terminais. Uma vez cancelada ({@code CANCELLED}), a ordem não
 * aparece em execuções subsequentes — sem risco de cancelamento duplicado.</p>
 *
 * <h3>Abstração temporal (AT-09.2)</h3>
 * <p>O {@link Clock} é injetado como bean (definido em {@link com.vibranium.orderservice.config.TimeConfig})
 * em vez de usar {@code Instant.now()} diretamente. Em produção usa {@code Clock.systemUTC()};
 * em testes, {@code Clock.fixed(...)} garante determinismo sem {@code Thread.sleep}.</p>
  */
@Component
public class SagaTimeoutCleanupJob {

    private static final Logger logger = LoggerFactory.getLogger(SagaTimeoutCleanupJob.class);

    private final OrderRepository     orderRepository;
    private final OrderOutboxRepository outboxRepository;
    private final Clock               clock;
    private final ObjectMapper        objectMapper;
    private final RedisMatchEngineAdapter matchEngine;

    /**
     * Threshold configurável para ordens não-terminais. Padrão: 5 minutos.
     *
     * <p>Sobrescreva via {@code app.saga.pending-timeout-minutes} em
     * {@code application.yaml} ou variável de ambiente.</p>
     */
    @Value("${app.saga.pending-timeout-minutes:5}")
    private long timeoutMinutes;

    /**
     * Cria o job injetando todas as dependências via construtor.
     *
     * <p>O {@link Clock} é fornecido pelo bean definido em
     * {@link com.vibranium.orderservice.config.TimeConfig}. Em testes,
     * um {@code Clock.fixed} com {@code @Primary} sobrepõe automaticamente
     * este bean sem alteração de código de produção.</p>
     *
     * @param orderRepository   repositório JPA das ordens (Command Side).
     * @param outboxRepository  repositório JPA da tabela de outbox.
     * @param clock             abstração temporal injetável — nunca use {@code Instant.now()}.
     * @param objectMapper      serializador Jackson compartilhado para construir o payload do outbox.
     * @param matchEngine       adaptador Redis para remoção de ordens do book (RC-1).
     */
    public SagaTimeoutCleanupJob(OrderRepository orderRepository,
                                  OrderOutboxRepository outboxRepository,
                                  Clock clock,
                                  ObjectMapper objectMapper,
                                  RedisMatchEngineAdapter matchEngine) {
        this.orderRepository  = orderRepository;
        this.outboxRepository = outboxRepository;
        this.clock            = clock;
        this.objectMapper     = objectMapper;
        this.matchEngine      = matchEngine;
    }

    /**
     * Cancela ordens {@code PENDING}, {@code OPEN} e {@code PARTIAL} cujo {@code created_at}
     * excedeu o timeout da Saga, emitindo os eventos de compensação adequados.
     *
     * <p>Execução:</p>
     * <ul>
     *   <li>Frequência: a cada 60 segundos (fixedDelay — aguarda término antes do próximo ciclo).</li>
     *   <li>Transacional: toda a operação é atômica — se uma ordem falhar, nenhuma é cancelada.</li>
     *   <li>Idempotente: somente ordens não-terminais são candidatas; após cancelamento,
     *       a ordem sai do escopo da query e não é processada novamente.</li>
     * </ul>
     *
     * <p>Para cada ordem expirada:</p>
     * <ol>
     *   <li>Chama {@code order.cancel("SAGA_TIMEOUT")} — transita para {@code CANCELLED}.</li>
     *   <li>Persiste o estado atualizado via {@code orderRepository.save()}.</li>
     *   <li>Grava {@link OrderCancelledEvent} na tabela de outbox.</li>
     *   <li>Grava {@link ReleaseFundsCommand} <strong>incondicionalmente</strong> no outbox
     *       para que o wallet-service desbloqueie o saldo. Emissão incondicional cobre
     *       a janela de corrida entre reserva efetiva e atualização de status (AT-1.1.4).</li>
     *   <li>Remove ordens OPEN/PARTIAL do Redis book para evitar matches com ordens já
     *       canceladas. Ordens PENDING nunca foram adicionadas ao book (RC-1).</li>
     *   <li>Emite {@code WARN} estruturado com {@code orderId}, {@code userId} e {@code age}.</li>
     * </ol>
     */
    @Scheduled(fixedDelayString = "${app.saga.cleanup-delay-ms:60000}")
    @Transactional
    public void cancelStalePendingOrders() {
        // Calcula o instante de corte: ordens criadas ANTES deste instante são candidatas
        // Usa clock.instant() em vez de Instant.now() para permitir testes determinísticos (AT-09.2)
        Instant cutoff = clock.instant().minus(timeoutMinutes, ChronoUnit.MINUTES);

        // AT-1.1.4: inclui PENDING — ordens nesses estados
        // também são "zumbis" se passaram muito tempo sem ser resolvidas.
        List<OrderStatus> eligibleStatuses = List.of(
                OrderStatus.PENDING
        );

        List<Order> stale = orderRepository.findByStatusInAndCreatedAtBefore(eligibleStatuses, cutoff);

        // Lista vazia é caso normal — nenhuma ação necessária, nenhum NPE possível
        if (stale.isEmpty()) {
            logger.debug("SagaTimeoutCleanupJob: nenhuma ordem expirada (cutoff={}).", cutoff);
            return;
        }

        logger.info("SagaTimeoutCleanupJob: {} ordem(ns) expirada(s) detectada(s) (cutoff={}).",
                stale.size(), cutoff);

        for (Order order : stale) {
            // 1. Transita para CANCELLED com motivo padronizado
            order.cancel("SAGA_TIMEOUT");
            orderRepository.save(order);

            // 2. Persiste OrderCancelledEvent no outbox (relay eventual pelo OrderOutboxPublisherService)
            //    O Outbox Pattern garante que o evento não seja perdido mesmo se o broker estiver
            //    temporariamente indisponível no momento do timeout.
            try {
                OrderCancelledEvent cancelledEvent = OrderCancelledEvent.of(
                        order.getCorrelationId(),
                        order.getId(),
                        FailureReason.SAGA_TIMEOUT,
                        "Order stuck beyond timeout threshold of " + timeoutMinutes + " minutes."
                );
                String payload = objectMapper.writeValueAsString(cancelledEvent);

                outboxRepository.save(new OrderOutboxMessage(
                        order.getId(),
                        "Order",
                        "OrderCancelledEvent",
                        RabbitMQConfig.EVENTS_EXCHANGE,
                        RabbitMQConfig.RK_ORDER_CANCELLED,
                        payload
                ));
            } catch (JsonProcessingException ex) {
                // Falha de serialização é um erro de programação — lança para forçar rollback
                // e garantir consistência: ou o cancelamento + outbox persistem juntos, ou nada.
                throw new IllegalStateException(
                        "Falha ao serializar OrderCancelledEvent para outbox (orderId=%s): %s"
                                .formatted(order.getId(), ex.getMessage()), ex);
            }

            // 3. AT-1.1.4: Compensação — emite ReleaseFundsCommand INCONDICIONALMENTE.
            //    Emissão incondicional cobre a janela de corrida entre reserva efetiva no
            //    wallet-service e atualização de status da ordem (PENDING → OPEN). Uma ordem
            //    PENDING pode já ter fundos bloqueados. O wallet-service trata
            //    InsufficientLockedFundsException graciosamente: se os fundos nunca foram
            //    reservados, o release é um no-op idempotente.
            emitReleaseFundsCommand(order);

            // 4. Log de auditoria estruturado — calcula time usando o clock abstrato
            //    (AT-09.2: nunca usa Instant.now() diretamente no job)
            long ageMinutes = Duration.between(order.getCreatedAt(), clock.instant()).toMinutes();
            logger.warn("Saga timeout detectado: orderId={} userId={} age={}min fundsReleased=true — cancelado com SAGA_TIMEOUT",
                    order.getId(), order.getUserId(), ageMinutes);
        }

        logger.info("SagaTimeoutCleanupJob: {} ordem(ns) cancelada(s) por timeout.", stale.size());
    }

    /**
     * Emite {@link ReleaseFundsCommand} para desbloquear os fundos reservados quando
     * a ordem é cancelada por timeout após já estar no livro (OPEN) ou parcialmente
     * executada (PARTIAL).
     *
     * <p>Lógica de cálculo do valor a liberar:</p>
     * <ul>
     *   <li>BUY: libera {@code price × remainingAmount} BRL — a reserva original foi
     *       {@code price × amount}; a parcela já liquidada foi removida do locked pelo
     *       wallet-service durante o settlement parcial.</li>
     *   <li>SELL: libera {@code remainingAmount} VIBRANIUM — mesma lógica proporcional.</li>
     * </ul>
     *
     * @param order Ordem cancelada (já com status {@code CANCELLED}; dados pré-cancelamento
     *              preservados nos campos imutáveis {@code walletId}, {@code price},
     *              {@code remainingAmount}, {@code orderType}).
     */
    private void emitReleaseFundsCommand(Order order) {
        // Determina o ativo e o valor a liberar conforme o tipo de ordem
        AssetType assetToRelease;
        BigDecimal amountToRelease;
        if (order.getOrderType() == OrderType.BUY) {
            // BUY: o lock foi em BRL = price × remainingAmount (saldo proporcional ainda bloqueado)
            assetToRelease  = AssetType.BRL;
            amountToRelease = order.getPrice().multiply(order.getRemainingAmount());
        } else {
            // SELL: o lock foi em VIBRANIUM = remainingAmount
            assetToRelease  = AssetType.VIBRANIUM;
            amountToRelease = order.getRemainingAmount();
        }

        ReleaseFundsCommand releaseCmd = new ReleaseFundsCommand(
                order.getCorrelationId(),
                order.getId(),
                order.getWalletId(),
                assetToRelease,
                amountToRelease,
                1
        );

        try {
            String releaseCmdJson = objectMapper.writeValueAsString(releaseCmd);
            outboxRepository.save(new OrderOutboxMessage(
                    order.getId(),
                    "Order",
                    "ReleaseFundsCommand",
                    RabbitMQConfig.COMMANDS_EXCHANGE,
                    RabbitMQConfig.QUEUE_RELEASE_FUNDS,
                    releaseCmdJson
            ));
        } catch (JsonProcessingException ex) {
            // Falha de serialização força rollback — mantém consistência total do Outbox Pattern.
            throw new IllegalStateException(
                    "Falha ao serializar ReleaseFundsCommand para outbox (orderId=%s): %s"
                            .formatted(order.getId(), ex.getMessage()), ex);
        }
    }
}
