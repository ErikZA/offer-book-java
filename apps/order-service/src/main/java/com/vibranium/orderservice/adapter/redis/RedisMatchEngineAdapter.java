package com.vibranium.orderservice.adapter.redis;

import com.vibranium.contracts.enums.OrderType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.ZSetOperations;

/**
 * Adaptador do Motor de Match baseado em Redis Sorted Set.
 *
 * <p>Executa o Script Lua {@code match_engine.lua} atomicamente via
 * {@code EVALSHA}, garantindo que dois eventos {@code FundsReservedEvent}
 * concorrentes nunca executem contra o mesmo ASK ou BID simultanêamente.</p>
 *
 * <h2>Estrutura dos Sorted Sets</h2>
 * <ul>
 *   <li>Key {@code {vibranium}:asks}: ASKs com score = price × 1_000_000 (menor preço primeiro)</li>
 *   <li>Key {@code {vibranium}:bids}: BIDs com score = price × 1_000_000 (maior preço primeiro via ZREVRANGEBYSCORE)</li>
 * </ul>
 *
 * <h2>Hash Tags e Redis Cluster (AT-11.1)</h2>
 * <p>As keys usam a hash tag {@code {vibranium}} para garantir que {@code {vibranium}:asks}
 * e {@code {vibranium}:bids} calculem o mesmo hash slot CRC16. Isso é obrigatório para
 * execução de scripts Lua multi-key ({@code EVAL}/{@code EVALSHA}) em Redis Cluster:
 * sem hash tags, os slots difeririam e o Redis retornaria
 * {@code CROSSSLOT Keys in request don't hash to the same slot}.
 * A hash tag é ignorada em modo standalone — sem regressão.</p>
 *
 * <h2>Formato do valor (pipe-delimited)</h2>
 * <pre>{orderId}|{userId}|{walletId}|{quantity}|{correlationId}|{epochMs}</pre>
 */
@Component
public class RedisMatchEngineAdapter {

    private static final Logger logger = LoggerFactory.getLogger(RedisMatchEngineAdapter.class);

    /**
     * Multiplicador para converter preço em score inteiro.
     * Preserva 6 casas decimais sem perda de precisão em {@code double}.
     * Exemplo: 500.000001 → 500_000_001.0
     */
    private static final long PRICE_PRECISION = 1_000_000L;

    @Value("${app.redis.keys.asks}")
    private String asksKey;

    @Value("${app.redis.keys.bids}")
    private String bidsKey;

    private final StringRedisTemplate redisTemplate;

    /** Script Lua carregado do classpath — reutilizado para evitar reparsa a cada execução. */
    @SuppressWarnings("rawtypes")
    private DefaultRedisScript<List> matchScript;

    public RedisMatchEngineAdapter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct
    void initScript() {
        // Carrega e pré-compila o script Lua do classpath
        matchScript = new DefaultRedisScript<>();
        matchScript.setScriptSource(
                new ResourceScriptSource(new ClassPathResource("lua/match_engine.lua")));
        matchScript.setResultType(List.class);
    }

    // =========================================================================
    // API pública
    // =========================================================================

    /**
     * Resultado de uma tentativa de match.
     *
     * @param matched                  {@code true} se houve cruzamento com contraparte.
     * @param counterpartId            UUID da ordem contraparte (ou {@code null} se sem match).
     * @param counterpartUserId        userId da contraparte.
     * @param counterpartWalletId      walletId da contraparte.
     * @param matchedQty               Quantidade executada no match.
     * @param remainingCounterpartQty  Quantidade residual da contraparte após o match.
     *                                 {@code ZERO} quando a contraparte foi totalmente consumida
     *                                 (casos FULL e PARTIAL_BID da ordem ingressante BUY;
     *                                  casos FULL e PARTIAL_ASK da ordem ingressante SELL).
     *                                 Positivo quando a contraparte tem saldo remanescente
     *                                 (PARTIAL_ASK para BUY ingressante;
     *                                  PARTIAL_BID para SELL ingressante).
     *                                 O Lua já reinseriu atomicamente a contraparte residual
     *                                 no Sorted Set; este campo é informativo para o camada Java.
     * @param fillType                 "FULL", "PARTIAL_ASK" ou "PARTIAL_BID".
     */
    public record MatchResult(
            boolean matched,
            UUID counterpartId,
            String counterpartUserId,
            UUID counterpartWalletId,
            BigDecimal matchedQty,
            BigDecimal remainingCounterpartQty,
            String fillType
    ) {
        public static MatchResult noMatch() {
            return new MatchResult(false, null, null, null,
                    BigDecimal.ZERO, BigDecimal.ZERO, "NO_MATCH");
        }
    }

    /**
     * Tenta casar uma ordem com contraparte no livro de ofertas Redis.
     *
     * @param orderId       UUID da ordem ingressante.
     * @param userId        keycloakId do usuário.
     * @param walletId      UUID da carteira.
     * @param orderType     BUY ou SELL.
     * @param price         Preço limite da ordem.
     * @param quantity      Quantidade desejada.
     * @param correlationId UUID de correlação da Saga.
     * @return {@link MatchResult} com o resultado do match.
     */
    @SuppressWarnings("unchecked")
    public MatchResult tryMatch(UUID orderId, String userId, UUID walletId,
                                OrderType orderType, BigDecimal price,
                                BigDecimal quantity, UUID correlationId) {

        // Monta o valor do membro do Sorted Set
        String orderValue = buildValue(orderId, userId, walletId, quantity, correlationId);

        // Converte preço em score inteiro (sem ponto flutuante)
        long priceScore = price.multiply(BigDecimal.valueOf(PRICE_PRECISION)).longValue();

        // Executa o script Lua atomicamente
        List<Object> result = redisTemplate.execute(
                matchScript,
                Arrays.asList(asksKey, bidsKey),
                orderType.name(),
                String.valueOf(priceScore),
                orderValue,
                quantity.toPlainString()
        );

        return parseResult(result);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Formata o valor do membro do Sorted Set:
     * {@code orderId|userId|walletId|qty|correlId|epochMs}
     */
    private static String buildValue(UUID orderId, String userId, UUID walletId,
                                     BigDecimal quantity, UUID correlationId) {
        return String.join("|",
                orderId.toString(),
                userId,
                walletId.toString(),
                quantity.toPlainString(),
                correlationId.toString(),
                String.valueOf(Instant.now().toEpochMilli())
        );
    }

    /**
     * Parseia o resultado Lua (lista de strings):
     * <ul>
     *   <li>{@code ["NO_MATCH"]} → sem match</li>
     *   <li>{@code ["MATCH", counterpartValue, matchedQty, fillType]} → match</li>
     * </ul>
     *
     * <p>Redis devolve os elementos como {@code byte[]} ou {@code String},
     * dependendo do serializer. Cobrimos os dois casos.</p>
     *
     * <p><strong>Visibilidade package-private</strong> intencional: permite testes
     * unitários diretos em {@code RedisMatchEngineAdapterParseResultTest} sem
     * necessidade de mock de {@code StringRedisTemplate}.</p>
     */
    static MatchResult parseResult(List<Object> result) {
        if (result == null || result.isEmpty()) {
            return MatchResult.noMatch();
        }

        String first = asString(result.get(0));
        if (!"MATCH".equals(first)) {
            return MatchResult.noMatch();
        }

        if (result.size() < 4) {
            logger.warn("Resultado do Lua incompleto: {}", result);
            return MatchResult.noMatch();
        }

        String counterpartValue = asString(result.get(1));
        String matchedQtyStr    = asString(result.get(2));
        String fillType         = asString(result.get(3));

        // 5º elemento: remainingCounterpartQty — adicionado no Subtask 2.1 (US-002)
        // Fallback para BigDecimal.ZERO por compatibilidade com versões antigas do script Lua
        BigDecimal remainingCounterpartQty = BigDecimal.ZERO;
        if (result.size() >= 5) {
            try {
                remainingCounterpartQty = new BigDecimal(asString(result.get(4)));
            } catch (NumberFormatException nfe) {
                logger.warn("5º elemento do Lua não pôde ser parseado como BigDecimal: {}",
                        asString(result.get(4)));
            }
        }

        String[] parts = counterpartValue.split("\\|");
        if (parts.length < 5) {
            logger.error("Valor da contraparte malformado: {}", counterpartValue);
            return MatchResult.noMatch();
        }

        try {
            return new MatchResult(
                    true,
                    UUID.fromString(parts[0]),   // orderId da contraparte
                    parts[1],                    // userId da contraparte
                    UUID.fromString(parts[2]),   // walletId da contraparte
                    new BigDecimal(matchedQtyStr),
                    remainingCounterpartQty,
                    fillType
            );
        } catch (Exception e) {
            logger.error("Erro ao parsear resultado do match engine: value={} error={}",
                    counterpartValue, e.getMessage());
            return MatchResult.noMatch();
        }
    }

    /**
     * Reinsere o residual de uma ordem parcialmente executada no Sorted Set correto.
     *
     * <p><strong>USO NORMAL:</strong> Este método NÃO precisa ser chamado no fluxo padrão.
     * O script Lua {@code match_engine.lua} já executa o requeue atomicamente como parte
     * da transação (ZREM → ZADD) dentro do mesmo {@code EVAL} — garantindo que nenhum
     * consumidor concorrente leia uma janela inconsistente.</p>
     *
     * <p><strong>QUANDO USAR:</strong> Exclusivamente em cenários de disaster recovery,
     * replay de eventos ou reprocessamento forçado fora do caminho feliz da Saga.
     * Chamar este método após {@code tryMatch()} resultará em double-entry no Sorted Set
     * pois o Lua já inseriu o residual.</p>
     *
     * <p>Implementação: constrói o valor pipe-delimited a partir dos campos do
     * {@link MatchResult} e executa {@code ZADD} na chave do lado correto:</p>
     * <ul>
     *   <li>{@code PARTIAL_ASK} (contraparte ASK sobrou) → chave {@code {vibranium}:asks}</li>
     *   <li>{@code PARTIAL_BID} (contraparte BID sobrou) → chave {@code {vibranium}:bids}</li>
     * </ul>
     *
     * @param result    MatchResult de um match parcial com {@code remainingCounterpartQty > 0}.
     * @param price     Preço da ordem contraparte, necessário para recalcular o score.
     * @param correlId  CorrelationId original da ordem contraparte.
     * @param epochMs   Timestamp original (épocaMilissegundos) da ordem contraparte.
     * @throws IllegalArgumentException se {@code result} não for parcial ou {@code remainingCounterpartQty <= 0}.
     */
    public void requeueResidual(MatchResult result, BigDecimal price,
                                UUID correlId, long epochMs) {
        if (!result.matched()) {
            throw new IllegalArgumentException("requeueResidual requer MatchResult com matched=true");
        }
        if (result.remainingCounterpartQty().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    "requeueResidual requer remainingCounterpartQty > 0; recebido: "
                    + result.remainingCounterpartQty());
        }

        // Reconstrói o valor do membro: orderId|userId|walletId|qty|correlId|epochMs
        String residualValue = String.join("|",
                result.counterpartId().toString(),
                result.counterpartUserId(),
                result.counterpartWalletId().toString(),
                result.remainingCounterpartQty().toPlainString(),
                correlId.toString(),
                String.valueOf(epochMs)
        );

        long priceScore = price.multiply(BigDecimal.valueOf(PRICE_PRECISION)).longValue();

        // PARTIAL_ASK → contraparte é um ASK com residual; PARTIAL_BID → contraparte é um BID
        String targetKey = "PARTIAL_ASK".equals(result.fillType()) ? asksKey : bidsKey;

        redisTemplate.opsForZSet().add(targetKey, residualValue, priceScore);

        logger.info("requeueResidual (fallback manual): fillType={} counterpartId={} remainingQty={} key={}",
                result.fillType(), result.counterpartId(),
                result.remainingCounterpartQty(), targetKey);
    }

    /**
     * Remove a ordem do Sorted Set correspondente (ASK ou BID) via ZSCAN + ZREM.
     *
     * <p>Operação idempotente: se o membro não for encontrado (ex: já foi removido
     * ou nunca foi inserido), retorna silenciosamente sem lançar exceção.
     * {@code ZREM} em membro inexistente é um no-op no Redis (retorna 0).</p>
     *
     * <p><strong>Complexidade:</strong> O(N) via ZSCAN — aceitável para AT-04.1.
     * AT-04.2 introduzirá índice reverso {@code {vibranium}:order_index} para
     * remoção O(1), eliminando a necessidade do scan.</p>
     *
     * <p><strong>Limitação do ZSCAN:</strong> o match pattern é aplicado
     * client-side pelo Redis; em sorted sets muito grandes, o ZSCAN pode
     * iterar muitos elementos antes de encontrar o alvo. O índice reverso
     * (AT-04.2) resolverá esse gargalo definitivamente via {@code HGET} + {@code ZREM}.</p>
     *
     * @param orderId UUID da ordem a remover.
     * @param type    {@code SELL} → remove de {@code asks};
     *                {@code BUY}  → remove de {@code bids}.
     * @throws RuntimeException se ocorrer falha na comunicação com o Redis durante
     *                          o ZSCAN ou o ZREM; a exceção original é relançada para
     *                          que o container AMQP execute NACK e roteie para a DLQ.
     */
    public void removeFromBook(UUID orderId, OrderType type) {
        // BUY inserido em bids; SELL inserido em asks
        String key    = OrderType.SELL.equals(type) ? asksKey : bidsKey;
        String prefix = orderId.toString() + "|";

        // ZSCAN com match pattern: filtra apenas membros iniciados com o orderId.
        // O Redis aplica o filtro internamente, mas o pattern matching é best-effort —
        // iteramos o cursor e verificamos o prefixo para garantir precisão.
        ScanOptions options = ScanOptions.scanOptions()
                .match(prefix + "*")
                .count(100)  // hint de batch ao Redis (não limita resultados)
                .build();

        try (Cursor<ZSetOperations.TypedTuple<String>> cursor =
                     redisTemplate.opsForZSet().scan(key, options)) {
            while (cursor.hasNext()) {
                ZSetOperations.TypedTuple<String> tuple = cursor.next();
                String member = tuple.getValue();
                if (member != null && member.startsWith(prefix)) {
                    // ZREM é idempotente: se o membro foi removido por outro thread
                    // entre o ZSCAN e o ZREM, retorna 0 sem erro.
                    Long removed = redisTemplate.opsForZSet().remove(key, member);
                    logger.info("removeFromBook: ZREM executado key={} orderId={} removed={}",
                            key, orderId, removed);
                    return; // cada orderId tem no máximo 1 membro no sorted set
                }
            }
        } catch (Exception e) {
            logger.error("removeFromBook: erro ao escanear/remover orderId={} key={}: {}",
                    orderId, key, e.getMessage(), e);
            throw e;
        }

        // Membro não encontrado — comportamento idempotente esperado em reprocessamentos
        logger.debug("removeFromBook: membro não encontrado (idempotente) orderId={} key={}",
                orderId, key);
    }

    /** Converte elemento retornado pelo Redis em String (byte[] ou String). */
    private static String asString(Object value) {
        if (value instanceof byte[] bytes) return new String(bytes);
        return String.valueOf(value);
    }
}
