package com.vibranium.orderservice.integration;

import com.vibranium.contracts.enums.AssetType;
import com.vibranium.contracts.enums.OrderType;
import com.vibranium.contracts.events.order.MatchExecutedEvent;
import com.vibranium.contracts.events.order.OrderReceivedEvent;
import com.vibranium.contracts.events.wallet.FundsReservedEvent;
import com.vibranium.orderservice.config.RabbitMQConfig;
import com.vibranium.orderservice.query.model.OrderDocument;
import com.vibranium.orderservice.query.repository.OrderHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * AT-05.1 — Testes de integração para criação lazy determinística de {@link OrderDocument}.
 *
 * <p><strong>FASE RED (TDD):</strong> estes testes falham antes da refatoração porque:</p>
 * <ul>
 *   <li>{@code TC-LAZY-1}: {@code onFundsReserved()} lança {@link IllegalStateException}
 *       ao não encontrar o documento — evento vai para retry/DLQ, nunca cria o doc.</li>
 *   <li>{@code TC-LAZY-2}: {@code updateDocumentWithMatch()} faz {@code return} silencioso
 *       quando documento não existe — evento descartado, doc nunca é criado.</li>
 * </ul>
 *
 * <p><strong>FASE GREEN:</strong> após a refatoração, ambos os consumers criam o documento
 * de forma lazy via {@link OrderDocument#createMinimalPending(String, java.time.Instant)},
 * garantindo consistência eventual mesmo com eventos fora de ordem.</p>
 *
 * <p><strong>Roteamento:</strong></p>
 * <ul>
 *   <li>{@code FundsReservedEvent} → routing key {@code wallet.events.funds-reserved}
 *       (binding da fila {@code order.projection.funds-reserved}).</li>
 *   <li>{@code MatchExecutedEvent} → routing key definida em {@link com.vibranium.orderservice.config.RabbitMQConfig#RK_MATCH_EXECUTED}
 *       (binding da fila {@code order.projection.match-executed}).</li>
 * </ul>
 */
@DisplayName("AT-05.1 — Criação Lazy Determinística de OrderDocument (out-of-order events)")
class OrderOutOfOrderEventsIntegrationTest extends AbstractMongoIntegrationTest {

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
    // TC-LAZY-1: FUNDS_RESERVED chega antes de ORDER_RECEIVED
    // =========================================================================

    /**
     * [AT-05.1 / TC-LAZY-1] — FASE RED → GREEN.
     *
     * <p><strong>Cenário:</strong> {@code FundsReservedEvent} publicado ANTES de
     * {@code OrderReceivedEvent}. Situação real quando o wallet-service processa
     * mais rápido do que a projeção do order-service.</p>
     *
     * <p><strong>Comportamento esperado (GREEN):</strong></p>
     * <ol>
     *   <li>Documento criado lazily ao receber {@code FUNDS_RESERVED} (stub mínimo).</li>
     *   <li>Ao chegar {@code ORDER_RECEIVED}, documento é enriquecido com dados completos.</li>
     *   <li>Histórico contém ambos os eventos: {@code FUNDS_RESERVED} e {@code ORDER_RECEIVED}.</li>
     * </ol>
     *
     * <p><strong>Falha antes da refatoração (RED):</strong> {@code onFundsReserved()} lança
     * {@link IllegalStateException} — documento nunca é criado; o {@code await()} expira.</p>
     */
    @Test
    @DisplayName("TC-LAZY-1: FUNDS_RESERVED antes de ORDER_RECEIVED deve criar documento e acumular ambos no histórico")
    void whenFundsReservedBeforeOrderReceived_thenDocumentCreatedWithBothEventsInHistory() {
        // ---- FASE 1: publica FUNDS_RESERVED sem ORDER_RECEIVED prévio ----
        // Simula o wallet-service confirmando reserva antes da projeção ORDER_RECEIVED chegar.
        FundsReservedEvent fundsEvent = FundsReservedEvent.of(
                correlationId,
                orderId,
                walletId,
                AssetType.BRL,
                new BigDecimal("25000.00")
        );

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.EVENTS_EXCHANGE,
                RabbitMQConfig.RK_FUNDS_RESERVED,      // "wallet.events.funds-reserved"
                fundsEvent
        );

        // THEN (após FUNDS_RESERVED): documento deve existir com status OPEN e FUNDS_RESERVED no histórico.
        // RED: IllegalStateException lançada → retry → documento nunca criado → await expira com AssertionError.
        await().atMost(10, TimeUnit.SECONDS)
               .untilAsserted(() -> {
                   Optional<OrderDocument> doc = orderHistoryRepository.findById(orderId.toString());
                   assertThat(doc)
                           .as("Documento deve ser criado lazily ao receber FUNDS_RESERVED sem ORDER_RECEIVED prévio")
                           .isPresent();
                   assertThat(doc.get().getHistory())
                           .as("Histórico deve conter entrada FUNDS_RESERVED")
                           .anyMatch(h -> h.eventType().equals("FUNDS_RESERVED"));
               });

        // ---- FASE 2: publica ORDER_RECEIVED depois ----
        // Simula entrega atrasada — o documento já foi criado lazily.
        OrderReceivedEvent receivedEvent = OrderReceivedEvent.of(
                correlationId,
                orderId,
                userId,
                walletId,
                OrderType.BUY,
                new BigDecimal("50000.00"),
                new BigDecimal("0.5")
        );

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.EVENTS_EXCHANGE,
                RabbitMQConfig.RK_ORDER_RECEIVED,      // ver RabbitMQConfig.RK_ORDER_RECEIVED
                receivedEvent
        );

        // THEN (após ORDER_RECEIVED): histórico deve conter AMBOS os eventos.
        // Critério de aceite: zero eventos descartados silenciosamente.
        await().atMost(10, TimeUnit.SECONDS)
               .untilAsserted(() -> {
                   Optional<OrderDocument> doc = orderHistoryRepository.findById(orderId.toString());
                   assertThat(doc).isPresent();

                   assertThat(doc.get().getHistory())
                           .as("Histórico deve conter FUNDS_RESERVED e ORDER_RECEIVED (ordem de chegada não importa)")
                           .hasSize(2)
                           .anyMatch(h -> h.eventType().equals("FUNDS_RESERVED"))
                           .anyMatch(h -> h.eventType().equals("ORDER_RECEIVED"));

                   // Após ORDER_RECEIVED, campos de negócio devem estar enriquecidos
                   assertThat(doc.get().getUserId())
                           .as("userId deve ser preenchido quando ORDER_RECEIVED enriquecer o documento lazy")
                           .isEqualTo(userId.toString());
               });
    }

    // =========================================================================
    // TC-LAZY-2: MATCH_EXECUTED chega antes de qualquer outro evento
    // =========================================================================

    /**
     * [AT-05.1 / TC-LAZY-2] — FASE RED → GREEN.
     *
     * <p><strong>Cenário:</strong> {@code MatchExecutedEvent} publicado ANTES de qualquer
     * outro evento. Situação real quando o motor de match processa ordens de outro serviço
     * que ainda não foram refletidas neste Read Model.</p>
     *
     * <p><strong>Comportamento esperado (GREEN):</strong></p>
     * <ol>
     *   <li>Documento criado lazily ao receber {@code MATCH_EXECUTED} (stub mínimo).</li>
     *   <li>Histórico contém entrada {@code MATCH_EXECUTED}.</li>
     *   <li>Nenhum {@code return} silencioso — evento nunca é descartado.</li>
     * </ol>
     *
     * <p><strong>Falha antes da refatoração (RED):</strong> {@code updateDocumentWithMatch()}
     * executa {@code return} silencioso — documento nunca é criado; o {@code await()} expira.</p>
     */
    @Test
    @DisplayName("TC-LAZY-2: MATCH_EXECUTED antes de qualquer evento deve criar documento e registrar o match")
    void whenMatchExecutedBeforeAnyOtherEvent_thenDocumentCreatedWithMatchInHistory() {
        // GIVEN — MATCH_EXECUTED publicado sem ORDER_RECEIVED prévio
        // orderId atua como buyOrderId (lado que queremos rastrear)
        UUID sellOrderId     = UUID.randomUUID();
        UUID sellerUserId    = UUID.randomUUID();
        UUID sellerWalletId  = UUID.randomUUID();

        MatchExecutedEvent matchEvent = MatchExecutedEvent.of(
                correlationId,
                orderId,                            // buyOrderId — lado rastreado
                sellOrderId,
                userId,
                sellerUserId,
                walletId,
                sellerWalletId,
                new BigDecimal("50000.00"),
                new BigDecimal("0.5")
        );

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.EVENTS_EXCHANGE,
                RabbitMQConfig.RK_MATCH_EXECUTED,   // ver RabbitMQConfig.RK_MATCH_EXECUTED
                matchEvent
        );

        // THEN: documento deve existir com MATCH_EXECUTED no histórico.
        // RED: return silencioso em updateDocumentWithMatch() → doc nunca criado → await expira.
        await().atMost(10, TimeUnit.SECONDS)
               .untilAsserted(() -> {
                   Optional<OrderDocument> doc = orderHistoryRepository.findById(orderId.toString());
                   assertThat(doc)
                           .as("Documento deve ser criado lazily ao receber MATCH_EXECUTED sem documento prévio")
                           .isPresent();
                   assertThat(doc.get().getHistory())
                           .as("Histórico deve conter entrada MATCH_EXECUTED")
                           .anyMatch(h -> h.eventType().equals("MATCH_EXECUTED"));
                   assertThat(doc.get().getStatus())
                           .as("Status deve ser FILLED ou PARTIAL após match (nunca PENDING sem qty)")
                           .isIn("FILLED", "PARTIAL");
               });
    }

    // =========================================================================
    // TC-LAZY-3: MATCH_EXECUTED antes do evento → ORDER_RECEIVED depois enriquece doc
    // =========================================================================

    /**
     * [AT-05.1 / TC-LAZY-3] — Regressão: garante que ORDER_RECEIVED após documento lazy
     * enriquece os campos financeiros sem duplicar o histórico.
     */
    @Test
    @DisplayName("TC-LAZY-3: ORDER_RECEIVED após match lazy deve enriquecer documento sem duplicar histórico")
    void whenOrderReceivedAfterLazyMatchDoc_thenDocumentEnrichedWithoutDuplicatingHistory() {
        // ---- FASE 1: MATCH_EXECUTED sem documento prévio ----
        UUID sellOrderId    = UUID.randomUUID();
        UUID sellerUserId   = UUID.randomUUID();
        UUID sellerWalletId = UUID.randomUUID();

        MatchExecutedEvent matchEvent = MatchExecutedEvent.of(
                correlationId,
                orderId,
                sellOrderId,
                userId,
                sellerUserId,
                walletId,
                sellerWalletId,
                new BigDecimal("50000.00"),
                new BigDecimal("0.5")
        );

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.EVENTS_EXCHANGE,
                RabbitMQConfig.RK_MATCH_EXECUTED,
                matchEvent
        );

        // Aguarda documento lazy criado
        await().atMost(10, TimeUnit.SECONDS)
               .until(() -> orderHistoryRepository.existsById(orderId.toString()));

        // ---- FASE 2: ORDER_RECEIVED chega depois ----
        OrderReceivedEvent receivedEvent = OrderReceivedEvent.of(
                correlationId,
                orderId,
                userId,
                walletId,
                OrderType.BUY,
                new BigDecimal("50000.00"),
                new BigDecimal("0.5")
        );

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.EVENTS_EXCHANGE,
                RabbitMQConfig.RK_ORDER_RECEIVED,
                receivedEvent
        );

        // THEN: histórico deve ter MATCH_EXECUTED + ORDER_RECEIVED (sem duplicação)
        await().atMost(10, TimeUnit.SECONDS)
               .untilAsserted(() -> {
                   Optional<OrderDocument> doc = orderHistoryRepository.findById(orderId.toString());
                   assertThat(doc).isPresent();

                   assertThat(doc.get().getHistory())
                           .as("Histórico deve ter exatamente 2 entradas: MATCH_EXECUTED + ORDER_RECEIVED")
                           .hasSize(2)
                           .anyMatch(h -> h.eventType().equals("MATCH_EXECUTED"))
                           .anyMatch(h -> h.eventType().equals("ORDER_RECEIVED"));

                   assertThat(doc.get().getUserId())
                           .as("userId deve ser enriquecido pelo ORDER_RECEIVED tardio")
                           .isEqualTo(userId.toString());
               });
    }
}
