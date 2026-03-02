package com.vibranium.orderservice.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibranium.contracts.enums.FailureReason;
import com.vibranium.contracts.enums.OrderStatus;
import com.vibranium.contracts.events.order.OrderCancelledEvent;
import com.vibranium.orderservice.config.RabbitMQConfig;
import com.vibranium.orderservice.domain.model.Order;
import com.vibranium.orderservice.domain.model.OrderOutboxMessage;
import com.vibranium.orderservice.domain.repository.OrderOutboxRepository;
import com.vibranium.orderservice.domain.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Job periódico de limpeza de ordens {@code PENDING} expiradas pelo timeout da Saga.
 *
 * <h3>Problema (AT-09.1)</h3>
 * <p>Toda Saga deve ter ciclo de vida finito. Uma ordem que permanece em {@code PENDING}
 * indefinidamente — por falha de entrega da wallet, crash do serviço ou perda de
 * mensagem — é uma "ordem zumbi": o usuário não recebe feedback e os recursos
 * (tentativa de bloqueio) ficam em estado inconsistente.</p>
 *
 * <h3>Solução</h3>
 * <p>A cada {@code 60 segundos} (configurável via {@code app.saga.cleanup-delay-ms}),
 * este job busca ordens com:</p>
 * <ul>
 *   <li>{@code status = PENDING}</li>
 *   <li>{@code created_at < now() - app.saga.pending-timeout-minutes}</li>
 * </ul>
 * <p>e as cancela com motivo {@code SAGA_TIMEOUT}, persistindo um
 * {@link OrderCancelledEvent} na tabela de outbox para propagação eventual.</p>
 *
 * <h3>Idempotência</h3>
 * <p>O job é naturalmente idempotente: {@code findByStatusAndCreatedAtBefore} retorna
 * apenas ordens {@code PENDING}. Uma vez cancelada ({@code CANCELLED}), a ordem não
 * aparece em execuções subsequentes — sem risco de cancelamento duplicado.</p>
 *
 * <h3>Abstração temporal (AT-09.2)</h3>
 * <p>O {@link Clock} é injetado como bean (definido em {@link com.vibranium.orderservice.config.TimeConfig})
 * em vez de usar {@code Instant.now()} diretamente. Em produção usa {@code Clock.systemUTC()};
 * em testes, {@code Clock.fixed(...)} garante determinismo sem {@code Thread.sleep}.</p>
 *
 * <h3>Race condition com resposta tardia da wallet</h3>
 * <p>Se a wallet processar o pedido de reserva <em>após</em> o job cancelar a ordem,
 * o {@code FundsReservedEventConsumer} tentará chamar {@code order.markAsOpen()} em uma
 * ordem {@code CANCELLED}. Isso lançará {@link IllegalStateException} (a ordem exige
 * {@code PENDING} para {@code markAsOpen}), a mensagem irá para a DLQ, e a ordem
 * permanecerá {@code CANCELLED} — comportamento correto: o timeout é explícito e a
 * reserva de fundos que chegou deve ser revertida pela wallet via evento compensatório.</p>
 *
 * <p>O risco é baixo em produção com threshold ≥ 5min, pois latências da wallet são
 * tipicamente sub-segundo. O Optimistic Locking ({@code @Version}) também previne
 * escritas concorrentes silenciosas.</p>
 */
@Component
public class SagaTimeoutCleanupJob {

    private static final Logger logger = LoggerFactory.getLogger(SagaTimeoutCleanupJob.class);

    private final OrderRepository     orderRepository;
    private final OrderOutboxRepository outboxRepository;
    private final Clock               clock;
    private final ObjectMapper        objectMapper;

    /**
     * Threshold configurável para ordens PENDING. Padrão: 5 minutos.
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
     */
    public SagaTimeoutCleanupJob(OrderRepository orderRepository,
                                  OrderOutboxRepository outboxRepository,
                                  Clock clock,
                                  ObjectMapper objectMapper) {
        this.orderRepository  = orderRepository;
        this.outboxRepository = outboxRepository;
        this.clock            = clock;
        this.objectMapper     = objectMapper;
    }

    /**
     * Cancela ordens {@code PENDING} cujo {@code created_at} excedeu o timeout da Saga.
     *
     * <p>Execução:</p>
     * <ul>
     *   <li>Frequência: a cada 60 segundos (fixedDelay — aguarda término antes do próximo ciclo).</li>
     *   <li>Transacional: toda a operação é atômica — se uma ordem falhar, nenhuma é cancelada.</li>
     *   <li>Idempotente: somente ordens {@code PENDING} são candidatas; após cancelamento,
     *       a ordem sai do escopo da query e não é processada novamente.</li>
     * </ul>
     *
     * <p>Para cada ordem expirada:</p>
     * <ol>
     *   <li>Chama {@code order.cancel("SAGA_TIMEOUT")} — transita para {@code CANCELLED}.</li>
     *   <li>Persiste o estado atualizado via {@code orderRepository.save()}.</li>
     *   <li>Cria e persiste um {@link OrderCancelledEvent} no outbox para relay pelo scheduler.</li>
     *   <li>Emite {@code WARN} estruturado com {@code orderId}, {@code userId} e {@code age} (min).</li>
     * </ol>
     */
    @Scheduled(fixedDelayString = "${app.saga.cleanup-delay-ms:60000}")
    @Transactional
    public void cancelStalePendingOrders() {
        // Calcula o instante de corte: ordens criadas ANTES deste instante são candidatas
        // Usa clock.instant() em vez de Instant.now() para permitir testes determinísticos (AT-09.2)
        Instant cutoff = clock.instant().minus(timeoutMinutes, ChronoUnit.MINUTES);

        List<Order> stale = orderRepository.findByStatusAndCreatedAtBefore(
                OrderStatus.PENDING, cutoff);

        // Lista vazia é caso normal — nenhuma ação necessária, nenhum NPE possível
        if (stale.isEmpty()) {
            logger.debug("SagaTimeoutCleanupJob: nenhuma ordem PENDING expirada (cutoff={}).", cutoff);
            return;
        }

        logger.info("SagaTimeoutCleanupJob: {} ordem(ns) PENDING expirada(s) detectada(s) (cutoff={}).",
                stale.size(), cutoff);

        for (Order order : stale) {
            // 1. Transita para CANCELLED com motivo padronizado
            order.cancel("SAGA_TIMEOUT");
            orderRepository.save(order);

            // 2. Persiste OrderCancelledEvent no outbox para relay eventual pelo OrderOutboxPublisherService
            //    O Outbox Pattern garante que o evento não seja perdido mesmo se o broker estiver
            //    temporariamente indisponível no momento do timeout.
            try {
                OrderCancelledEvent cancelledEvent = OrderCancelledEvent.of(
                        order.getCorrelationId(),
                        order.getId(),
                        FailureReason.SAGA_TIMEOUT,
                        "Order stuck in PENDING state beyond timeout threshold of "
                                + timeoutMinutes + " minutes."
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

            // 3. Log de auditoria estruturado — calcula time in PENDING usando o clock abstrato
            //    (AT-09.2: nunca usa Instant.now() diretamente no job)
            long ageMinutes = Duration.between(order.getCreatedAt(), clock.instant()).toMinutes();
            logger.warn("Saga timeout detectado: orderId={} userId={} age={}min — cancelado com SAGA_TIMEOUT",
                    order.getId(), order.getUserId(), ageMinutes);
        }

        logger.info("SagaTimeoutCleanupJob: {} ordem(ns) cancelada(s) por timeout.", stale.size());
    }
}
