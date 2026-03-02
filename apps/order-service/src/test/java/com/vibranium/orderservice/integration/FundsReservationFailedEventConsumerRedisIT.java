package com.vibranium.orderservice.integration;

import com.vibranium.contracts.enums.FailureReason;
import com.vibranium.contracts.enums.OrderType;
import com.vibranium.contracts.events.wallet.FundsReservationFailedEvent;
import com.vibranium.orderservice.adapter.redis.RedisMatchEngineAdapter;
import com.vibranium.orderservice.domain.model.Order;
import com.vibranium.orderservice.domain.repository.OrderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

/**
 * AT-04.1 — FASE RED → GREEN: valida que o {@code FundsReservationFailedEventConsumer}
 * remove a ordem do Redis Order Book (ZREM) antes de emitir o {@code basicAck}.
 *
 * <h2>Problema resolvido</h2>
 * <p>Sem esta mudança, uma ordem que falhou na reserva de fundos permanece no
 * Sorted Set Redis após ser CANCELADA no PostgreSQL. Isso permite que o Motor de
 * Match a retorne como contraparte, gerando um {@code MatchExecutedEvent} inválido
 * e tentativa de liquidação com fundos inexistentes no {@code wallet-service}.</p>
 *
 * <h2>Estado FASE RED</h2>
 * <p>Antes da implementação:</p>
 * <ul>
 *   <li>{@code FundsReservationFailedEventConsumer} não injeta {@link RedisMatchEngineAdapter}.</li>
 *   <li>{@link RedisMatchEngineAdapter#removeFromBook(UUID, OrderType)} não existe.</li>
 *   <li>O teste {@link #deveRemoverOrdemDoSortedSetAposEventoDeFalha} falha em
 *       {@code assertThat(membersDepois).isEmpty()} pois a ordem permanece no Redis.</li>
 * </ul>
 *
 * <p>Após a implementação (FASE GREEN), todos os testes ficam verdes.</p>
 */
@DisplayName("AT-04.1 — FundsReservationFailedEventConsumer deve remover ordem do Redis")
class FundsReservationFailedEventConsumerRedisIT extends AbstractIntegrationTest {

    private static final String EVENTS_EXCHANGE  = "vibranium.events";
    private static final String RK_FUNDS_FAILED  = "wallet.events.funds-reservation-failed";
    private static final long   PRICE_PRECISION  = 1_000_000L;

    @Value("${app.redis.keys.asks:{vibranium}:asks}")
    private String asksKey;

    @Value("${app.redis.keys.bids:{vibranium}:bids}")
    private String bidsKey;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private RedisMatchEngineAdapter redisAdapter;

    // IDs reutilizados por todos os testes deste arquivo
    private UUID orderId;
    private UUID correlationId;
    private UUID walletId;

    /**
     * Prepara o estado inicial: limpa Redis + banco, cria uma SELL Order (ASK)
     * no estado PENDING no PostgreSQL e insere o membro correspondente no sorted
     * set {@code {vibranium}:asks}.
     */
    @BeforeEach
    void setUp() {
        orderId       = UUID.randomUUID();
        correlationId = UUID.randomUUID();
        walletId      = UUID.randomUUID();

        orderRepository.deleteAll();
        redisTemplate.delete(asksKey);
        redisTemplate.delete(bidsKey);

        // Persiste SELL Order no estado PENDING (simula ordem recém-criada aguardando reserva)
        Order sellOrder = Order.create(
                orderId, correlationId,
                UUID.randomUUID().toString(),    // userId
                walletId,
                OrderType.SELL,
                new BigDecimal("500.00"),
                new BigDecimal("10.00000000")
        );
        orderRepository.save(sellOrder);

        // Insere o membro no sorted set ASK (formato: orderId|userId|walletId|qty|correlId|epochMs)
        String memberValue = buildMemberValue(orderId, walletId, correlationId,
                new BigDecimal("10.00000000"));
        double score = new BigDecimal("500.00")
                .multiply(BigDecimal.valueOf(PRICE_PRECISION))
                .doubleValue();
        redisTemplate.opsForZSet().add(asksKey, memberValue, score);
    }

    @AfterEach
    void tearDown() {
        orderRepository.deleteAll();
        redisTemplate.delete(asksKey);
        redisTemplate.delete(bidsKey);
    }

    // =========================================================================
    // Cenário 1 — Consumer deve remover o membro do Sorted Set após processar o evento
    // =========================================================================

    /**
     * ⚠️ <strong>FASE RED:</strong> falha antes da implementação porque o consumer
     * não chama {@code redisAdapter.removeFromBook()} — a ordem permanece no sorted set
     * depois do processamento.
     *
     * <p><strong>Fluxo:</strong></p>
     * <ol>
     *   <li>Membro já inserido em {@code {vibranium}:asks} pelo {@code @BeforeEach}.</li>
     *   <li>Publica {@code FundsReservationFailedEvent} na exchange.</li>
     *   <li>Aguarda até 10s que a ordem tenha status {@code CANCELLED} no banco.</li>
     *   <li>Verifica que o sorted set {@code asks} não contém mais a ordem.</li>
     * </ol>
     */
    @Test
    @DisplayName("⚠️ [RED] Consumer deve remover a ordem do {vibranium}:asks após FundsReservationFailedEvent")
    void deveRemoverOrdemDoSortedSetAposEventoDeFalha() {
        // Confirma que o membro existe ANTES do evento — pré-condição do teste
        Set<String> membersAntes = redisTemplate.opsForZSet()
                .rangeByScore(asksKey, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
        assertThat(membersAntes)
                .as("Pré-condição: membro deve existir no sorted set antes do evento")
                .anyMatch(m -> m.startsWith(orderId.toString()));

        // Publica o evento de falha de reserva de fundos
        FundsReservationFailedEvent event = FundsReservationFailedEvent.of(
                correlationId, orderId, walletId.toString(),
                FailureReason.INSUFFICIENT_FUNDS,
                "Saldo insuficiente para reserva (teste AT-04.1)"
        );
        rabbitTemplate.convertAndSend(EVENTS_EXCHANGE, RK_FUNDS_FAILED, event);

        // Aguarda cancelamento no PostgreSQL — indica que o consumer processou o evento
        await().atMost(10, TimeUnit.SECONDS)
                .pollInterval(200, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    Order updated = orderRepository.findById(orderId).orElseThrow();
                    assertThat(updated.getStatus().name())
                            .as("Order deve estar CANCELLED no PostgreSQL")
                            .isEqualTo("CANCELLED");
                });

        // ⚠️ FASE RED: falha aqui antes da implementação
        // A ordem deve ter sido removida do sorted set (ZREM antes do basicAck)
        Set<String> membersDepois = redisTemplate.opsForZSet()
                .rangeByScore(asksKey, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
        assertThat(membersDepois)
                .as("FASE RED → GREEN: ordem deve ter sido removida do {vibranium}:asks após CANCELLED")
                .noneMatch(m -> m.startsWith(orderId.toString()));
    }

    // =========================================================================
    // Cenário 2 — removeFromBook deve ser idempotente
    // =========================================================================

    /**
     * Valida idempotência do {@link RedisMatchEngineAdapter#removeFromBook(UUID, OrderType)}.
     *
     * <p>Chamadas repetidas NÃO lançam exceção e o Redis permanece consistente.
     * {@code ZREM} em membro inexistente é um no-op no Redis (retorna 0) — essa
     * propriedade deve ser preservada pela implementação Java.</p>
     *
     * <p><strong>FASE RED:</strong> falha com {@code MethodNotFoundError} ou
     * {@code NoSuchMethodException} enquanto {@code removeFromBook()} não existir
     * no {@link RedisMatchEngineAdapter}.</p>
     */
    @Test
    @DisplayName("⚠️ [RED] removeFromBook deve ser idempotente — segunda chamada não lança exceção")
    void removeFromBook_deveSerIdempotente() {
        // 1ª chamada — remove o membro existente
        assertThatCode(() -> redisAdapter.removeFromBook(orderId, OrderType.SELL))
                .as("1ª chamada: não deve lançar exceção")
                .doesNotThrowAnyException();

        // Confirma que o membro foi removido
        Set<String> membersAposRemoção = redisTemplate.opsForZSet()
                .rangeByScore(asksKey, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
        assertThat(membersAposRemoção)
                .as("Membro deve ter sido removido após 1ª chamada")
                .noneMatch(m -> m.startsWith(orderId.toString()));

        // 2ª chamada — membro já inexistente; deve ser silenciosa
        assertThatCode(() -> redisAdapter.removeFromBook(orderId, OrderType.SELL))
                .as("2ª chamada (membro ausente): deve ser silenciosa (idempotência)")
                .doesNotThrowAnyException();

        // Redis deve permanecer íntegro após chamada dupla
        Set<String> membersAposIdempotencia = redisTemplate.opsForZSet()
                .rangeByScore(asksKey, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
        assertThat(membersAposIdempotencia)
                .as("Redis deve permanecer consistente após chamada idempotente")
                .noneMatch(m -> m.startsWith(orderId.toString()));
    }

    // =========================================================================
    // Cenário 3 — BUY Order → remove de {vibranium}:bids
    // =========================================================================

    /**
     * Valida que {@code removeFromBook} usa a key correta para ordens BUY ({@code bids}).
     *
     * <p><strong>FASE RED:</strong> falha enquanto {@code removeFromBook()} não existir.</p>
     */
    @Test
    @DisplayName("⚠️ [RED] removeFromBook deve remover BUY Order de {vibranium}:bids")
    void removeFromBook_deveLimparBidsSortedSet() {
        // Prepara: insere uma BUY Order em bids
        UUID buyOrderId      = UUID.randomUUID();
        UUID buyCorrelId     = UUID.randomUUID();
        UUID buyWalletId     = UUID.randomUUID();
        String bidMember = buildMemberValue(buyOrderId, buyWalletId, buyCorrelId,
                new BigDecimal("5.00000000"));
        double bidScore  = new BigDecimal("500.00")
                .multiply(BigDecimal.valueOf(PRICE_PRECISION))
                .doubleValue();
        redisTemplate.opsForZSet().add(bidsKey, bidMember, bidScore);

        // Confirma pré-condição
        Set<String> antes = redisTemplate.opsForZSet()
                .rangeByScore(bidsKey, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
        assertThat(antes).anyMatch(m -> m.startsWith(buyOrderId.toString()));

        // Remove via removeFromBook
        assertThatCode(() -> redisAdapter.removeFromBook(buyOrderId, OrderType.BUY))
                .doesNotThrowAnyException();

        // Verifica remoção em bids
        Set<String> depois = redisTemplate.opsForZSet()
                .rangeByScore(bidsKey, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
        assertThat(depois)
                .as("BUY Order deve ser removida de {vibranium}:bids")
                .noneMatch(m -> m.startsWith(buyOrderId.toString()));
    }

    // =========================================================================
    // Helper
    // =========================================================================

    /**
     * Constrói o valor pipe-delimited do membro do Sorted Set no mesmo formato
     * usado pelo {@code RedisMatchEngineAdapter.buildValue()}:
     * <pre>orderId|userId|walletId|qty|correlId|epochMs</pre>
     */
    private static String buildMemberValue(UUID orderId, UUID walletId,
                                           UUID correlId, BigDecimal qty) {
        return String.join("|",
                orderId.toString(),
                UUID.randomUUID().toString(),   // userId (irrelevante para este teste)
                walletId.toString(),
                qty.toPlainString(),
                correlId.toString(),
                String.valueOf(System.currentTimeMillis())
        );
    }
}
