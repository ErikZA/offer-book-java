package com.vibranium.orderservice.integration;

import com.mongodb.MongoException;
import com.vibranium.contracts.enums.OrderType;
import com.vibranium.contracts.events.order.MatchExecutedEvent;
import com.vibranium.contracts.events.order.OrderReceivedEvent;
import com.vibranium.orderservice.application.query.consumer.OrderEventProjectionConsumer;
import com.vibranium.orderservice.application.query.model.OrderDocument;
import com.vibranium.orderservice.application.query.repository.OrderHistoryRepository;
import com.vibranium.orderservice.application.query.service.OrderAtomicHistoryWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * AT-05.3 — Atomicidade no {@code onMatchExecuted()}: TDD RED → GREEN.
 *
 * <h3>Problema (sem transação)</h3>
 * <pre>
 *   updateDocumentWithMatch(buyOrderId)  → findAndModify #1: SUCESSO
 *   updateDocumentWithMatch(sellOrderId) → findAndModify #2: FALHA ← JVM/rede
 *   ────────────────────────────────────────────────────────────────────────
 *   Estado final: buyer atualizado, seller NÃO → inconsistência permanente
 * </pre>
 *
 * <h3>Solução (com transação MongoDB)</h3>
 * <p>Os dois {@code findAndModify} ocorrem dentro da mesma sessão com
 * {@code startTransaction}. Se o segundo falha, o MongoDB reverte ambas as
 * operações automaticamente:</p>
 * <pre>
 *   session.startTransaction()
 *     findAndModify(buyOrderId)   → OK (buffered)
 *     findAndModify(sellOrderId)  → EXCEPTION
 *   session.abortTransaction()    → buyer revertido também
 * </pre>
 *
 * <h3>Fase RED</h3>
 * <p>O teste {@link #whenSecondUpdateFails_buyerMustBeRolledBack_Red} <strong>falha
 * antes da refatoração</strong> porque sem {@code @Transactional} + {@link
 * org.springframework.data.mongodb.MongoTransactionManager}, o buyer fica atualizado
 * mesmo quando o seller lança exceção — inconsistência parcial.</p>
 *
 * <h3>Fase GREEN</h3>
 * <p>Após adicionar {@link org.springframework.data.mongodb.MongoTransactionManager}
 * e {@code @Transactional("mongoTransactionManager")} em {@code onMatchExecuted()},
 * a transação é abortada no todo → buyer é revertido → teste passa.</p>
 *
 * <h3>Retry seguro (idempotência)</h3>
 * <p>O teste {@link #whenSecondUpdateFails_thenRetryConvergesCorrectly} verifica que
 * reprocessar o mesmo evento após a falha (re-entrega RabbitMQ) aplica ambos os lados
 * corretamente — graças à idempotência por {@code eventId + "-" + orderId}.</p>
 *
 * <p><strong>Nota sobre contexto:</strong> o uso de {@code @SpyBean} suja o
 * {@code ApplicationContext}; esta classe usa um contexto isolado.</p>
 */
@DisplayName("AT-05.3 — Atomicidade cross-document em onMatchExecuted()")
class OrderMatchAtomicityTest extends AbstractMongoIntegrationTest {

    // SpyBean substitui o bean no contexto — permite injetar falha artificial
    // na segunda chamada a appendMatchAndDecrement sem alterar a lógica de produção.
    @SpyBean
    private OrderAtomicHistoryWriter atomicWriterSpy;

    @Autowired
    private OrderEventProjectionConsumer orderEventProjectionConsumer;

    @Autowired
    private OrderHistoryRepository orderHistoryRepository;

    private UUID buyOrderId;
    private UUID sellOrderId;
    private UUID correlationId;
    private UUID buyerId;
    private UUID sellerId;
    private UUID buyerWalletId;
    private UUID sellerWalletId;

    @BeforeEach
    void setup() {
        buyOrderId    = UUID.randomUUID();
        sellOrderId   = UUID.randomUUID();
        correlationId = UUID.randomUUID();
        buyerId       = UUID.randomUUID();
        sellerId      = UUID.randomUUID();
        buyerWalletId = UUID.randomUUID();
        sellerWalletId = UUID.randomUUID();

        // Limpa coleção entre testes
        orderHistoryRepository.deleteAll();

        // Reseta o spy: remove comportamento artificial do teste anterior
        Mockito.reset(atomicWriterSpy);
    }

    // =========================================================================
    // TC-AT05.3-1 — RED: falha no segundo update deve reverter o primeiro
    // =========================================================================

    /**
     * [AT-05.3 / TC-AT05.3-1] — FASE RED → GREEN.
     *
     * <p><strong>Cenário:</strong> primeiro {@code appendMatchAndDecrement} (buyer) sucede;
     * o segundo (seller) lança {@link RuntimeException} simulando falha de infraestrutura.</p>
     *
     * <p><strong>Fase RED (sem transação):</strong></p>
     * <ol>
     *   <li>Buyer é atualizado no MongoDB (findAndModify commitado imediatamente).</li>
     *   <li>Seller dispara exceção.</li>
     *   <li>Buyer permanece com MATCH_EXECUTED no histórico → inconsistência detectada.</li>
     *   <li>Asserção falha: buyer não deveria estar atualizado.</li>
     * </ol>
     *
     * <p><strong>Fase GREEN (com transação):</strong></p>
     * <ol>
     *   <li>Ambos os findAndModify ocorrem na mesma sessão de transação.</li>
     *   <li>Exceção no seller → {@code session.abortTransaction()} → buyer revertido.</li>
     *   <li>Buyer não tem MATCH_EXECUTED → asserção passa.</li>
     * </ol>
     */
    @Test
    @DisplayName("TC-AT05.3-1 [RED→GREEN]: falha no seller reverte buyer (transação MongoDB)")
    void whenSecondUpdateFails_buyerMustBeRolledBack_Red() {
        // Pré-condição: cria ambos os documentos para que a lógica seja update (não upsert).
        // Upsert dentro de transação tem comportamento diferente — isolar o caminho de update.
        createOrderDocument(buyOrderId, buyerId, buyerWalletId, "BUY");
        createOrderDocument(sellOrderId, sellerId, sellerWalletId, "SELL");

        MatchExecutedEvent event = MatchExecutedEvent.of(
                correlationId,
                buyOrderId,
                sellOrderId,
                buyerId,
                sellerId,
                buyerWalletId,
                sellerWalletId,
                new BigDecimal("50000.00"),
                new BigDecimal("1.0")
        );

        // Configura o spy: primeira chamada (buyer) → executa normalmente;
        // segunda chamada (seller) → lança RuntimeException artificial.
        AtomicInteger callCount = new AtomicInteger(0);
        doAnswer(invocation -> {
            int call = callCount.incrementAndGet();
            if (call == 2) {
                // Simula falha de infraestrutura no segundo findAndModify (seller)
                throw new RuntimeException(
                        "AT-05.3 — Falha artificial no segundo update (seller) para teste RED");
            }
            // Chamada real para a primeira invocação (buyer)
            return invocation.callRealMethod();
        }).when(atomicWriterSpy).appendMatchAndDecrement(any(), any(), any(), any());

        // Act: onMatchExecuted deve propagar a RuntimeException do seller para que
        // o RabbitMQ container possa realizar NAK + re-entrega.
        assertThatThrownBy(() -> orderEventProjectionConsumer.onMatchExecuted(event))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Falha artificial no segundo update");

        // Assert: buyer NÃO deve ter MATCH_EXECUTED no histórico.
        //
        // FASE RED (sem @Transactional):
        //   - buyer JÁ foi atualizado pelo primeiro findAndModify (commitado imediatamente)
        //   - asserção abaixo FALHA: doc.history contém MATCH_EXECUTED para o buyer
        //   - evidência da inconsistência parcial AT-05.3
        //
        // FASE GREEN (com @Transactional("mongoTransactionManager")):
        //   - transação abortada no todo → buyer revertido pelo MongoDB
        //   - asserção PASSA: doc.history NÃO contém MATCH_EXECUTED
        OrderDocument buyerDoc = orderHistoryRepository
                .findById(buyOrderId.toString())
                .orElseThrow(() -> new AssertionError("Documento buyer não encontrado"));

        long matchEntriesOnBuyer = buyerDoc.getHistory().stream()
                .filter(h -> "MATCH_EXECUTED".equals(h.eventType()))
                .count();

        assertThat(matchEntriesOnBuyer)
                .as("""
                    AT-05.3 RED → GREEN:
                    Buyer foi atualizado (%d entradas MATCH_EXECUTED), mas seller falhou.
                    FASE RED: sem transação MongoDB, o buyer fica inconsistentemente atualizado.
                    FASE GREEN: com @Transactional + MongoTransactionManager, rollback reverte buyer.
                    """, matchEntriesOnBuyer)
                .isZero();
    }

    // =========================================================================
    // TC-AT05.3-2 — Retry seguro: re-entrega após falha converge para estado correto
    // =========================================================================

    /**
     * [AT-05.3 / TC-AT05.3-2] — Idempotência garante retry convergente.
     *
     * <p><strong>Cenário:</strong></p>
     * <ol>
     *   <li>Primeira tentativa: buyer OK, seller falha → exceção → re-entrega.</li>
     *   <li>Segunda tentativa (retry): buyer é no-op (idempotente — eventId já no histórico),
     *       seller é aplicado → ambos os lados consistentes.</li>
     * </ol>
     *
     * <p><strong>Comportamento esperado (passa em ambas as fases):</strong></p>
     * <ul>
     *   <li>Com transação (GREEN): primeiro retry aplica ambos (buyer foi revertido).</li>
     *   <li>Sem transação (RED parcial): primeiro retry é no-op para buyer (já no histórico),
     *       aplicado para seller → eventual consistency mesmo sem rollback.</li>
     * </ul>
     *
     * <p>Este teste verifica que o sistema SEMPRE converge para o estado correto após re-entrega,
     * independentemente da presença de transação — confirmando que a idempotência é uma
     * camada de defesa adicional válida mesmo sem a garantia atômica.</p>
     */
    @Test
    @DisplayName("TC-AT05.3-2: retry após falha converge para estado correto (idempotência)")
    void whenSecondUpdateFails_thenRetryConvergesCorrectly() {
        createOrderDocument(buyOrderId, buyerId, buyerWalletId, "BUY");
        createOrderDocument(sellOrderId, sellerId, sellerWalletId, "SELL");

        MatchExecutedEvent event = MatchExecutedEvent.of(
                correlationId,
                buyOrderId,
                sellOrderId,
                buyerId,
                sellerId,
                buyerWalletId,
                sellerWalletId,
                new BigDecimal("50000.00"),
                new BigDecimal("1.0")
        );

        // Primeira tentativa: falha no segundo update (seller)
        AtomicInteger callCount = new AtomicInteger(0);
        doAnswer(invocation -> {
            if (callCount.incrementAndGet() == 2) {
                throw new RuntimeException("AT-05.3 — Falha no primeiro attempt (seller)");
            }
            return invocation.callRealMethod();
        }).when(atomicWriterSpy).appendMatchAndDecrement(any(), any(), any(), any());

        // Primeira tentativa falha — simula o que o RabbitMQ faria após NAK
        try {
            orderEventProjectionConsumer.onMatchExecuted(event);
        } catch (RuntimeException ignored) {
            // Exceção esperada — o RabbitMQ re-entregaria a mensagem
        }

        // Reset spy: remove a falha artificial para o retry (re-entrega normal pelo broker)
        Mockito.reset(atomicWriterSpy);

        // Segunda tentativa (retry): deve aplicar o que falta sem duplicar o que já existe
        orderEventProjectionConsumer.onMatchExecuted(event);

        // Assert: ambos os documentos devem ter exatamente 1 entrada MATCH_EXECUTED
        // (idempotência garante que o buyer não foi duplicado pelo retry)
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            OrderDocument buyerDoc = orderHistoryRepository
                    .findById(buyOrderId.toString())
                    .orElseThrow(() -> new AssertionError("Documento buyer não encontrado"));

            OrderDocument sellerDoc = orderHistoryRepository
                    .findById(sellOrderId.toString())
                    .orElseThrow(() -> new AssertionError("Documento seller não encontrado"));

            long buyerMatches = buyerDoc.getHistory().stream()
                    .filter(h -> "MATCH_EXECUTED".equals(h.eventType()))
                    .count();

            long sellerMatches = sellerDoc.getHistory().stream()
                    .filter(h -> "MATCH_EXECUTED".equals(h.eventType()))
                    .count();

            assertThat(buyerMatches)
                    .as("Buyer deve ter exatamente 1 MATCH_EXECUTED após retry (idempotente)")
                    .isEqualTo(1L);

            assertThat(sellerMatches)
                    .as("Seller deve ter exatamente 1 MATCH_EXECUTED após retry (aplicado no retry)")
                    .isEqualTo(1L);
        });
    }

    // =========================================================================
    // TC-AT05.3-3 — Retry local para WriteConflict transiente
    // =========================================================================

    /**
     * [AT-05.3 / TC-AT05.3-3] — Conflito transiente de escrita deve ser resolvido localmente.
     *
     * <p>Simula um {@code WriteConflict} (código Mongo 112) no segundo update da primeira
     * tentativa. O consumer deve retentar localmente, reaplicar buyer+seller na segunda
     * tentativa e concluir sem propagar exceção para o broker.</p>
     */
    @Test
    @DisplayName("TC-AT05.3-3: WriteConflict transiente deve ser resolvido com retry local")
    void whenTransientWriteConflictOccurs_thenConsumerRetriesAndSucceeds() {
        createOrderDocument(buyOrderId, buyerId, buyerWalletId, "BUY");
        createOrderDocument(sellOrderId, sellerId, sellerWalletId, "SELL");

        MatchExecutedEvent event = MatchExecutedEvent.of(
                correlationId,
                buyOrderId,
                sellOrderId,
                buyerId,
                sellerId,
                buyerWalletId,
                sellerWalletId,
                new BigDecimal("50000.00"),
                new BigDecimal("1.0")
        );

        AtomicInteger callCount = new AtomicInteger(0);
        doAnswer(invocation -> {
            int call = callCount.incrementAndGet();
            if (call == 2) {
                throw new DataIntegrityViolationException(
                        "WriteConflict transiente simulado",
                        new MongoException(112, "WriteConflict")
                );
            }
            return invocation.callRealMethod();
        }).when(atomicWriterSpy).appendMatchAndDecrement(any(), any(), any(), any());

        // Não deve propagar exceção: o retry local resolve o conflito temporário.
        orderEventProjectionConsumer.onMatchExecuted(event);

        // 1ª tentativa: buyer + seller(falha), 2ª tentativa: buyer + seller(sucesso) => >= 4 chamadas.
        assertThat(callCount.get())
                .as("Retry local deve reexecutar buyer+seller após WriteConflict transiente")
                .isGreaterThanOrEqualTo(4);

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            OrderDocument buyerDoc = orderHistoryRepository
                    .findById(buyOrderId.toString())
                    .orElseThrow(() -> new AssertionError("Documento buyer não encontrado"));

            OrderDocument sellerDoc = orderHistoryRepository
                    .findById(sellOrderId.toString())
                    .orElseThrow(() -> new AssertionError("Documento seller não encontrado"));

            long buyerMatches = buyerDoc.getHistory().stream()
                    .filter(h -> "MATCH_EXECUTED".equals(h.eventType()))
                    .count();

            long sellerMatches = sellerDoc.getHistory().stream()
                    .filter(h -> "MATCH_EXECUTED".equals(h.eventType()))
                    .count();

            assertThat(buyerMatches).isEqualTo(1L);
            assertThat(sellerMatches).isEqualTo(1L);
        });
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Cria um {@link OrderDocument} mínimo via {@code onOrderReceived} para garantir que
     * os documentos de buyer/seller existam antes dos testes de match.
     *
     * <p>Usar o consumer público garante que o estado inicial seja exatamente o mesmo
     * que em produção — sem escrever diretamente no MongoDB via {@code mongoTemplate.save()}.</p>
     */
    private void createOrderDocument(UUID orderId, UUID userId, UUID walletId, String orderType) {
        OrderReceivedEvent event = OrderReceivedEvent.of(
                UUID.randomUUID(), // correlationId único por documento
                orderId,
                userId,
                walletId,
                OrderType.valueOf(orderType),
                new BigDecimal("50000.00"),
                new BigDecimal("10.0")
        );
        orderEventProjectionConsumer.onOrderReceived(event);

        await().atMost(5, TimeUnit.SECONDS)
               .until(() -> orderHistoryRepository.existsById(orderId.toString()));
    }
}
