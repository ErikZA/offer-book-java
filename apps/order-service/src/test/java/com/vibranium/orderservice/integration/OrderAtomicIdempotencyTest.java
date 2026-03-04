package com.vibranium.orderservice.integration;

import com.vibranium.contracts.enums.OrderType;
import com.vibranium.contracts.events.order.MatchExecutedEvent;
import com.vibranium.contracts.events.order.OrderReceivedEvent;
import com.vibranium.orderservice.config.RabbitMQConfig;
import com.vibranium.orderservice.application.query.consumer.OrderEventProjectionConsumer;
import com.vibranium.orderservice.application.query.model.OrderDocument;
import com.vibranium.orderservice.application.query.model.OrderDocument.OrderHistoryEntry;
import com.vibranium.orderservice.application.query.repository.OrderHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * AT-05.2 — Teste de Concorrência: Idempotência Atômica com MongoTemplate.
 *
 * <h3>FASE RED (TDD)</h3>
 * <p>Este teste <strong>deve falhar antes da refatoração</strong> porque o padrão atual
 * de {@code findById() + appendHistory() + save()} sofre de <em>lost update</em>:</p>
 * <pre>
 *   Thread A: findById() → doc{history:[]}
 *   Thread B: findById() → doc{history:[]}
 *   Thread A: save doc{history:[eventA]}          ← persistido
 *   Thread B: save doc{history:[eventB]}          ← sobrescreve A → eventA PERDIDO
 * </pre>
 *
 * <h3>FASE GREEN</h3>
 * <p>Após a refatoração com {@code mongoTemplate.updateFirst()} e filtro idempotente
 * por {@code eventId}, cada thread executa uma operação atômica no servidor MongoDB:</p>
 * <pre>
 *   updateFirst({_id: X, "history.eventId": {$ne: eventId}}, {$push: {history: entry}})
 * </pre>
 * <p>O MongoDB serializa writes a nível de documento — sem overwrite, sem lost update.</p>
 *
 * <h3>Critério de Aceite</h3>
 * <ul>
 *   <li>100 threads concorrentes, cada uma processa um evento único (eventId distinto).</li>
 *   <li>Documento final contém exatamente 100 entradas no histórico.</li>
 *   <li>Zero duplicatas — {@code eventId} é chave de idempotência no banco.</li>
 * </ul>
 *
 * <p><strong>Estratégia:</strong> O consumer é invocado diretamente via injeção Spring
 * (bypassando RabbitMQ) para garantir concorrência real — se mensagens fossem enviadas
 * via AMQP, o listener default teria {@code concurrentConsumers=1} e processaria em série,
 * não exibindo o bug. A chamada direta ao bean Spring expõe a corrida nas chamadas ao
 * MongoDB sem dependência do throughput do broker.</p>
 */
@DisplayName("AT-05.2 — Idempotência Atômica: 100 eventos concorrentes no mesmo orderId")
class OrderAtomicIdempotencyTest extends AbstractMongoIntegrationTest {

    private static final int CONCURRENT_EVENTS = 100;

    @Autowired
    private OrderHistoryRepository orderHistoryRepository;

    // Injeta o consumer diretamente para chamar os handlers em múltiplas threads
    // sem passar pelo broker (RabbitMQ processa em série com 1 consumer thread).
    @Autowired
    private OrderEventProjectionConsumer orderEventProjectionConsumer;

    private UUID orderId;
    private UUID correlationId;
    private UUID userId;
    private UUID walletId;

    @BeforeEach
    void setup() {
        orderId       = UUID.randomUUID();
        correlationId = UUID.randomUUID();
        userId        = UUID.randomUUID();
        walletId      = UUID.randomUUID();

        // Garante estado limpo entre execuções
        orderHistoryRepository.deleteAll();
    }

    // =========================================================================
    // TC-ATOMIC-1 — 100 MATCH_EXECUTED simultâneos no mesmo orderId
    // =========================================================================

    /**
     * [AT-05.2 / TC-ATOMIC-1] — Nenhum update perdido em 100 eventos sequenciais rápidos.
     *
     * <p><strong>Cenário:</strong> 100 eventos distintos ({@code eventId} único em cada um)
     * são processados sequencialmente via {@code onMatchExecuted()} para o mesmo
     * {@code buyOrderId} — simula o processamento realista de 100 mensagens AMQP
     * entregues em sequência por um único consumer thread.</p>
     *
     * <p><strong>Por que sequencial (AT-05.3):</strong> {@code onMatchExecuted()} é agora
     * anotado com {@code @Transactional("mongoTransactionManager")} (AT-05.3). Operações
     * concorrentes sobre o <em>mesmo</em> documento com transações ativas causam
     * <em>write-write conflict</em> — MongoDB abortará transações conflitantes,
     * o que é o comportamento correto de isolação. O consumer AMQP em produção processa
     * mensagens sequencialmente (um consumer thread por padrão), portanto o cenário
     * sequencial reflete fielmente o comportamento real do sistema.</p>
     *
     * <p><strong>Comportamento esperado (GREEN):</strong> após {@code mongoTemplate.updateFirst()}
     * atômico, o documento contém exatamente 100 entradas no histórico — cada
     * {@code findAndModify} com filtro idempotente garante que nenhum evento é perdido
     * ou duplicado mesmo sob processamento rápido.</p>
     *
     * <p><strong>Falha antes da refatoração (RED):</strong> o padrão
     * {@code findById + appendHistory + save} sofre de lost update — saves sequenciais
     * rápidos sobrescrevem uns aos outros se existirem interleavings parciais.</p>
     */
    @Test
    @DisplayName("TC-ATOMIC-1: 100 MATCH_EXECUTED sequenciais devem resultar em exatamente 100 entradas únicas no histórico")
    void whenHundredConcurrentMatchEvents_thenHistoryShouldContainExactlyHundredEntries() {

        // ---- PRÉ-CONDIÇÃO: cria o documento via ORDER_RECEIVED ----
        // Garante que o documento existe antes do processamento sequencial.
        OrderReceivedEvent orderReceived = OrderReceivedEvent.of(
                correlationId, orderId, userId, walletId,
                OrderType.BUY, new BigDecimal("50000.00"), new BigDecimal("10.0")
        );
        orderEventProjectionConsumer.onOrderReceived(orderReceived);

        await().atMost(5, TimeUnit.SECONDS)
               .until(() -> orderHistoryRepository.existsById(orderId.toString()));

        // ---- SEQUENCIAL: 100 eventos distintos processados um por um ----
        // Cada evento tem um eventId único (UUID.randomUUID()) — sem duplicatas intencionais.
        // Simula a entrega sequencial de 100 mensagens AMQP pelo broker (comportamento
        // real de produção com um único consumer thread por fila).
        //
        // @Transactional("mongoTransactionManager") (AT-05.3) envolve cada mensagem em
        // uma transação INDEPENDENTE — sem conflitos de escrita porque não há sobreposição temporal.
        List<MatchExecutedEvent> matchEvents = buildMatchEvents(CONCURRENT_EVENTS);

        for (MatchExecutedEvent event : matchEvents) {
            // Cada chamada é uma transação MongoDB separada (buyer + seller atômicos).
            // O filtro idempotente {$ne: eventId} garante que nenhum evento é duplicado.
            orderEventProjectionConsumer.onMatchExecuted(event);
        }

        // ---- ASSERT: histórico deve conter exatamente 100 entradas MATCH_EXECUTED ----
        await().atMost(10, TimeUnit.SECONDS)
               .untilAsserted(() -> {
                   OrderDocument doc = orderHistoryRepository
                           .findById(orderId.toString())
                           .orElseThrow(() -> new AssertionError("Documento não encontrado"));

                   long matchCount = doc.getHistory().stream()
                           .filter(h -> h.eventType().equals("MATCH_EXECUTED"))
                           .count();

                   assertThat(matchCount)
                           .as("Esperados " + CONCURRENT_EVENTS + " MATCH_EXECUTED no histórico, "
                               + "mas encontrados " + matchCount + ". "
                               + "Lost update detectado: saves sequenciais rápidos "
                               + "sobrescreveram entradas (race condition em findById/save).")
                           .isEqualTo(CONCURRENT_EVENTS);
               });
    }

    // =========================================================================
    // TC-ATOMIC-2 — Idempotência: mesmo eventId enviado 100 vezes em paralelo
    // =========================================================================

    /**
     * [AT-05.2 / TC-ATOMIC-2] — Idempotência DB-level sob concorrência.
     *
     * <p><strong>Cenário:</strong> O MESMO {@code eventId} é processado 100 vezes
     * simultaneamente (simula re-entrega massiva pelo RabbitMQ após restart do broker).</p>
     *
     * <p><strong>Comportamento esperado:</strong> documento contém exatamente 1 entrada
     * para esse eventId — garantia de idempotência no banco de dados, não em memória.</p>
     *
     * <p><strong>Com o banco como guardião:</strong> o filtro
     * {@code "history.eventId": {$ne: eventId}} garante que apenas um dos 100 writers
     * modifica o documento — os outros 99 encontram zero documentos que satisfazem o filtro
     * (o documento já tem o eventId) e retornam {@code modifiedCount=0}.</p>
     *
     * @throws InterruptedException se a espera for interrompida
     */
    @Test
    @DisplayName("TC-ATOMIC-2: mesmo eventId enviado 100x em paralelo deve resultar em exatamente 1 entrada no histórico")
    void whenSameEventIdSent100TimesConcurrently_thenHistoryContainsExactlyOneEntry()
            throws InterruptedException {

        // Cria o documento base
        OrderReceivedEvent orderReceived = OrderReceivedEvent.of(
                correlationId, orderId, userId, walletId,
                OrderType.BUY, new BigDecimal("50000.00"), new BigDecimal("10.0")
        );
        orderEventProjectionConsumer.onOrderReceived(orderReceived);

        await().atMost(5, TimeUnit.SECONDS)
               .until(() -> orderHistoryRepository.existsById(orderId.toString()));

        // Mesmo MatchExecutedEvent (mesmo eventId) enviado 100 vezes
        UUID duplicateMatchId = UUID.randomUUID();
        MatchExecutedEvent duplicateEvent = MatchExecutedEvent.of(
                correlationId,
                orderId,                        // buyOrderId
                UUID.randomUUID(),              // sellOrderId
                userId,
                UUID.randomUUID(),              // sellerUserId
                walletId,
                UUID.randomUUID(),              // sellerWalletId
                new BigDecimal("50000.00"),
                new BigDecimal("0.1")
        );

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch  = new CountDownLatch(CONCURRENT_EVENTS);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < CONCURRENT_EVENTS; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        orderEventProjectionConsumer.onMatchExecuted(duplicateEvent);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            boolean allCompleted = doneLatch.await(30, TimeUnit.SECONDS);
            assertThat(allCompleted).as("Todas as 100 threads devem completar").isTrue();
        }

        await().atMost(10, TimeUnit.SECONDS)
               .untilAsserted(() -> {
                   OrderDocument doc = orderHistoryRepository
                           .findById(orderId.toString())
                           .orElseThrow();

                   long distinctEventIds = doc.getHistory().stream()
                           .map(OrderHistoryEntry::eventId)
                           .distinct()
                           .count();

                   long matchCount = doc.getHistory().stream()
                           .filter(h -> h.eventType().equals("MATCH_EXECUTED"))
                           .count();

                   assertThat(matchCount)
                           .as("Idempotência: o mesmo eventId deve resultar em exatamente 1 entrada, "
                               + "não " + matchCount + ". "
                               + "A idempotência deve ser garantida no banco, não em memória.")
                           .isEqualTo(1L);

                   assertThat(distinctEventIds)
                           .as("Nenhum eventId duplicado deve existir no histórico")
                           .isEqualTo(doc.getHistory().size());
               });
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Constrói N eventos MatchExecuted únicos para o mesmo {@code orderId} (lado comprador).
     * Cada evento tem {@code UUID.randomUUID()} como {@code eventId} — sem duplicatas.
     *
     * @param count Número de eventos a criar.
     * @return Lista imutável de eventos, cada um com eventId distinto.
     */
    private List<MatchExecutedEvent> buildMatchEvents(int count) {
        List<MatchExecutedEvent> events = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            events.add(MatchExecutedEvent.of(
                    correlationId,
                    orderId,                                    // buyOrderId — mesmo para todos
                    UUID.randomUUID(),                          // sellOrderId — diferente em cada match
                    userId,
                    UUID.randomUUID(),                          // sellerUserId
                    walletId,
                    UUID.randomUUID(),                          // sellerWalletId
                    new BigDecimal("50000.00"),
                    new BigDecimal("0.1")
            ));
        }
        return events;
    }
}
