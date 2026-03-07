package com.vibranium.orderservice.infrastructure.redis;

import com.vibranium.contracts.enums.OrderType;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import io.micrometer.core.instrument.Counter;
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
   * <li>Key {@code {vibranium}:asks}: ASKs com score = price × 100_000_000 (menor preço primeiro)</li>
 *   <li>Key {@code {vibranium}:bids}: BIDs com score = price × 100_000_000 (maior preço primeiro via ZREVRANGEBYSCORE)</li>
 * </ul> *
 * <h2>AT-04.2 — Índice Reverso (order_index)</h2>
 * <p>O script {@code match_engine.lua} popula atomicamente o hash auxiliar
 * {@code {vibranium}:order_index} em cada inserção no livro:</p>
 * <pre>{@code HSET {vibranium}:order_index <orderId> "<bookKey>|<score>|<member>"}</pre>
 * <p>Isso permite que {@code removeFromBook} execute em O(1) via
 * {@code HGET + ZREM + HDEL} no script {@code remove_from_book.lua},
 * eliminando a necessidade de {@code ZSCAN} (O(n)).</p> *
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
     * Preserva 8 casas decimais sem perda de precisão em {@code double}.
     * {@code tonumber()} no Lua é IEEE-754 double de 64 bits: suporta inteiros
     * exatos até 2^53 (~9 × 10^15), valor máximo de score gerado com esta
     * constante para preço de 90_000 USD → 9_000_000_000_000_000, dentro do limite.
     * Exemplo: 500.00000001 → 50_000_000_001 (AT-3.2.1)
     */
    private static final long PRICE_PRECISION = 100_000_000L;

    @Value("${app.redis.keys.asks}")
    private String asksKey;

    @Value("${app.redis.keys.bids}")
    private String bidsKey;

    /**
     * Hash auxiliar de índice reverso (AT-04.2).
     * Mapeamento: {@code orderId → bookKey|score|member}.
     * Permite remoção O(1) sem ZSCAN.
     */
    @Value("${app.redis.keys.order-index}")
    private String orderIndexKey;

    private final StringRedisTemplate redisTemplate;

    /** AT-15.2: Timer para latência do EVALSHA no Redis (vibranium.redis.match.latency). */
    private final Timer redisMatchLatencyTimer;

    /**
     * AT-11: Circuit Breaker Resilience4j para o motor de match Redis.
     * Protege contra cascata de falhas quando o Redis fica indisponível.
     * Configuração via application.yaml (instância: redisMatchEngine).
     */
    private final CircuitBreaker circuitBreaker;

    /** Script Lua carregado do classpath — reutilizado para evitar reparsa a cada execução. */
    @SuppressWarnings("rawtypes")
    private DefaultRedisScript<List> matchScript;

    /**
     * Script Lua de remoção O(1) via índice reverso (AT-04.2).
     * Executa atomicamente: HGET + ZREM + HDEL.
     */
    private DefaultRedisScript<Long> removeScript;

    /**
     * AT-17: Script Lua de compensação (undo_match.lua).
     * Reverte atomicamente um match executado pelo match_engine.lua,
     * restaurando contrapartes no sorted set e removendo inserções residuais.
     */
    private DefaultRedisScript<Long> undoMatchScript;

    /** AT-17: Contador de compensações Redis executadas. */
    private final Counter compensationCounter;

    public RedisMatchEngineAdapter(StringRedisTemplate redisTemplate,
                                   MeterRegistry meterRegistry,
                                   CircuitBreaker circuitBreaker) {
        this.redisTemplate = redisTemplate;
        this.circuitBreaker = circuitBreaker;
        this.redisMatchLatencyTimer = Timer.builder("vibranium.redis.match.latency")
                .description("Latency of Redis EVALSHA match engine execution")
                .register(meterRegistry);
        this.compensationCounter = Counter.builder("vibranium.redis.compensation")
                .description("Number of Redis undo_match compensations executed (AT-17)")
                .register(meterRegistry);
    }

    @PostConstruct
    void initScript() {
        // Carrega e pré-compila o script de match do classpath
        matchScript = new DefaultRedisScript<>();
        matchScript.setScriptSource(
                new ResourceScriptSource(new ClassPathResource("lua/match_engine.lua")));
        matchScript.setResultType(List.class);

        // AT-04.2: carrega o script de remoção O(1) via índice reverso
        removeScript = new DefaultRedisScript<>();
        removeScript.setScriptSource(
                new ResourceScriptSource(new ClassPathResource("lua/remove_from_book.lua")));
        removeScript.setResultType(Long.class);

        // AT-17: carrega o script de compensação (undo_match)
        undoMatchScript = new DefaultRedisScript<>();
        undoMatchScript.setScriptSource(
                new ResourceScriptSource(new ClassPathResource("lua/undo_match.lua")));
        undoMatchScript.setResultType(Long.class);
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

        /**
         * Resultado de deduplicação: orderId já existe no book (AT-16).
         * Comportamento esperado — não indica erro.
         */
        public static MatchResult alreadyInBook() {
            return new MatchResult(false, null, null, null,
                    BigDecimal.ZERO, BigDecimal.ZERO, "ALREADY_IN_BOOK");
        }

        /** {@code true} se a ordem já existia no book (deduplicação AT-16). */
        public boolean isAlreadyInBook() {
            return "ALREADY_IN_BOOK".equals(fillType);
        }
    }

    /**
     * Tenta casar uma ordem com contraparte(s) no livro de ofertas Redis.
     *
     * <p>Executa o Script Lua {@code match_engine.lua} que percorre o livro em loop
     * até que {@code remainingQty == 0} ou o livro esteja esgotado (ou MAX_MATCHES
     * atingido). Retorna todos os matches ocorridos em um único tick atômico.</p>
     *
     * @param orderId       UUID da ordem ingressante.
     * @param userId        keycloakId do usuário.
     * @param walletId      UUID da carteira.
     * @param orderType     BUY ou SELL.
     * @param price         Preço limite da ordem.
     * @param quantity      Quantidade desejada.
     * @param correlationId UUID de correlação da Saga.
     * @return Lista de {@link MatchResult} com todos os matches ocorridos; vazia se sem match.
     *         Cada elemento representa uma contraparte distinta executada.
     */
    @SuppressWarnings("unchecked")
    public List<MatchResult> tryMatch(UUID orderId, String userId, UUID walletId,
                                      OrderType orderType, BigDecimal price,
                                      BigDecimal quantity, UUID correlationId) {

        // Monta o valor do membro do Sorted Set
        String orderValue = buildValue(orderId, userId, walletId, quantity, correlationId);

        // Converte preço em score inteiro (sem ponto flutuante)
        long priceScore = price.multiply(BigDecimal.valueOf(PRICE_PRECISION)).longValue();

        // AT-11: decora a execução Redis com o Circuit Breaker.
        // Se o circuito estiver OPEN, lança CallNotPermittedException sem tentar Redis.
        // Se CLOSED ou HALF_OPEN, executa normalmente e registra sucesso/falha.
        List<Object> result = circuitBreaker.executeSupplier(() ->
                redisMatchLatencyTimer.record(() ->
                        redisTemplate.execute(
                                matchScript,
                                Arrays.asList(asksKey, bidsKey, orderIndexKey),
                                orderType.name(),
                                String.valueOf(priceScore),
                                orderValue,
                                quantity.toPlainString()
                        )));

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
     * Parseia o resultado Lua retornando todos os matches ocorridos.
     *
     * <p>Suporta dois formatos de resposta do script Lua:</p>
     * <ul>
     *   <li><strong>Legado</strong> {@code ["MATCH", val, qty, fill, rem?]} — single-match;
     *       retorna lista com 1 elemento (compatibilidade com scripts antigos).</li>
     *   <li><strong>Novo</strong> {@code ["MULTI_MATCH"|"PARTIAL", N, (val, qty, fill, rem)×N, ?rem]}
     *       — multi-match; retorna lista com N elementos.</li>
     *   <li>{@code ["NO_MATCH"]} ou lista nula/vazia → lista vazia.</li>
     * </ul>
     *
     * <p>Redis devolve os elementos como {@code byte[]} ou {@code String},
     * dependendo do serializer. Ambos são cobertos via {@link #asString}.</p>
     *
     * <p><strong>Visibilidade package-private</strong> intencional: permite testes
     * unitários diretos em {@code RedisMatchEngineAdapterParseResultTest} sem
     * necessidade de mock de {@code StringRedisTemplate}.</p>
     */
    static List<MatchResult> parseResult(List<Object> result) {
        if (result == null || result.isEmpty()) {
            return List.of();
        }

        String first = asString(result.get(0));

        // ── Formato legado: ["MATCH", val, qty, fill, rem?] ───────────────────
        if ("MATCH".equals(first)) {
            if (result.size() < 4) {
                logger.warn("Resultado Lua legado incompleto (MATCH): {}", result);
                return List.of();
            }
            MatchResult mr = parseSingleMatchEntry(
                    asString(result.get(1)),
                    asString(result.get(2)),
                    asString(result.get(3)),
                    result.size() >= 5 ? asString(result.get(4)) : null
            );
            return mr == null ? List.of() : List.of(mr);
        }

        // ── Sem match ────────────────────────────────────────────────────────
        if ("NO_MATCH".equals(first)) {
            return List.of();
        }

        // ── AT-16: orderId já existe no book (deduplicação Lua) ─────────────
        if ("ALREADY_IN_BOOK".equals(first)) {
            return List.of(MatchResult.alreadyInBook());
        }

        // ── Formato novo: ["MULTI_MATCH"|"PARTIAL", N, (val,qty,fill,rem)×N, ?rem] ──
        if ("MULTI_MATCH".equals(first) || "PARTIAL".equals(first)) {
            if (result.size() < 2) {
                logger.warn("Resultado Lua incompleto ({}): sem count", first);
                return List.of();
            }
            int count;
            try {
                count = Integer.parseInt(asString(result.get(1)));
            } catch (NumberFormatException e) {
                logger.warn("Count do Lua inválido: {}", asString(result.get(1)));
                return List.of();
            }
            // Cada match ocupa exatamente 4 posições iniciando no índice 2
            List<MatchResult> matches = new ArrayList<>(count);
            int baseIndex = 2;
            for (int i = 0; i < count; i++) {
                int offset = baseIndex + i * 4;
                // offset+3 deve existir (4 campos por match)
                if (offset + 3 >= result.size()) {
                    logger.warn("Resultado Lua truncado: {} matches declarados, {} disponíveis",
                            count, i);
                    break;
                }
                String rem = (offset + 3 < result.size() - 1)
                        // campo rem está dentro do bloco (não é o último elemento da lista PARTIAL)
                        ? asString(result.get(offset + 3))
                        // último bloco: o 4º campo pode coexistir com remainingIncoming
                        : asString(result.get(offset + 3));
                MatchResult mr = parseSingleMatchEntry(
                        asString(result.get(offset)),
                        asString(result.get(offset + 1)),
                        asString(result.get(offset + 2)),
                        rem
                );
                if (mr != null) matches.add(mr);
            }
            return matches;
        }

        logger.warn("Formato de resposta Lua desconhecido: primeiro elemento = '{}'", first);
        return List.of();
    }

    /**
     * Parseia um único bloco de match a partir dos campos individuais extraídos do array Lua.
     *
     * @param counterpartValue valor pipe-delimited da contraparte
     * @param matchedQtyStr    quantidade executada como string
     * @param fillType         "FULL", "PARTIAL_ASK" ou "PARTIAL_BID"
     * @param remainingStr     quantidade residual da contraparte como string (pode ser null)
     * @return {@link MatchResult} populado, ou {@code null} se o valor for malformado.
     */
    private static MatchResult parseSingleMatchEntry(String counterpartValue,
                                                      String matchedQtyStr,
                                                      String fillType,
                                                      String remainingStr) {
        String[] parts = counterpartValue.split("\\|");
        if (parts.length < 5) {
            logger.error("Valor da contraparte malformado (< 5 partes): {}", counterpartValue);
            return null;
        }
        BigDecimal remainingCounterpartQty = BigDecimal.ZERO;
        if (remainingStr != null) {
            try {
                remainingCounterpartQty = new BigDecimal(remainingStr);
            } catch (NumberFormatException nfe) {
                logger.warn("remainingQty do Lua não pôde ser parseado: {}", remainingStr);
            }
        }
        try {
            return new MatchResult(
                    true,
                    UUID.fromString(parts[0]),        // orderId da contraparte
                    parts[1],                         // userId da contraparte
                    UUID.fromString(parts[2]),        // walletId da contraparte
                    new BigDecimal(matchedQtyStr),
                    remainingCounterpartQty,
                    fillType
            );
        } catch (Exception e) {
            logger.error("Erro ao parsear entrada de match: value={} error={}",
                    counterpartValue, e.getMessage());
            return null;
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
     * Reverte atomicamente um match no Redis via {@code undo_match.lua} (AT-17).
     *
     * <p>Invocado no catch da Fase 3 do {@code FundsReservedEventConsumer} quando
     * o commit JPA falha após um match bem-sucedido. Restaura as contrapartes
     * consumidas/modificadas pelo {@code match_engine.lua} e remove a inserção
     * residual da ordem ingressante (se PARTIAL).</p>
     *
     * <p><strong>Idempotência:</strong> executar 2× com os mesmos argumentos produz
     * o mesmo resultado — o Lua verifica via {@code ZSCORE} se o membro já existe
     * antes de re-inserir.</p>
     *
     * <p><strong>Tentativa única:</strong> NÃO faz retry. Se o Redis falhar durante
     * a compensação, loga como CRITICAL e retorna -1. O caller deve alertar para
     * resolução manual.</p>
     *
     * @param incomingOrderType  tipo da ordem ingressante (BUY ou SELL)
     * @param incomingOrderId    orderId da ordem ingressante
     * @param matchResults       lista de MatchResult retornada pelo {@code tryMatch()}
     * @param counterpartPrices  mapeamento counterpartId → preço original (para recálculo do score).
     *                           Para PARTIAL matches o Lua usa order_index como fallback;
     *                           para FULL matches este mapa é obrigatório (order_index foi HDEL).
     *                           O Consumer obtém os preços via {@code orderRepository.findById()}.
     * @return número de contrapartes restauradas, ou -1 se o Redis falhou
     */
    public int undoMatch(OrderType incomingOrderType, UUID incomingOrderId,
                         List<MatchResult> matchResults,
                         java.util.Map<UUID, BigDecimal> counterpartPrices) {
        if (matchResults == null || matchResults.isEmpty()) {
            logger.debug("undoMatch: nada a compensar (resultado vazio) orderId={}", incomingOrderId);
            return 0;
        }

        try {
            List<String> args = new ArrayList<>();
            args.add(incomingOrderType.name());
            args.add(incomingOrderId.toString());
            args.add(String.valueOf(matchResults.size()));

            for (MatchResult mr : matchResults) {
                // Reconstruir o valor pipe-delimited da contraparte.
                // Os campos correlId e epochMs não estão no MatchResult — usamos placeholders.
                // O match_engine.lua retorna o member original como counterpartValue, mas
                // parseSingleMatchEntry descarta correlId/epochMs. Para o undo, os campos
                // essenciais são orderId, userId, walletId e qty (usados no próximo match);
                // correlId e epochMs do member NÃO são usados pelo parseResult().
                BigDecimal originalQty = "FULL".equals(mr.fillType())
                        ? mr.matchedQty()
                        : mr.matchedQty().add(mr.remainingCounterpartQty());

                String counterpartValue = String.join("|",
                        mr.counterpartId().toString(),
                        mr.counterpartUserId(),
                        mr.counterpartWalletId().toString(),
                        originalQty.toPlainString(),
                        "compensated",
                        "0"
                );

                // Score da contraparte: obtido do mapa de preços (Consumer fez DB lookup).
                // Para PARTIAL matches onde o mapa não tem a entrada, o undo_match.lua
                // faz fallback via HGET no order_index (que preserva o score).
                // Para FULL matches o order_index foi HDEL — o score do mapa é obrigatório.
                long counterpartScore = 0L;
                BigDecimal counterpartPrice = counterpartPrices.get(mr.counterpartId());
                if (counterpartPrice != null) {
                    counterpartScore = counterpartPrice.multiply(
                            BigDecimal.valueOf(PRICE_PRECISION)).longValue();
                }

                args.add(counterpartValue);
                args.add(String.valueOf(counterpartScore));
                args.add(mr.fillType());
                args.add(originalQty.toPlainString());
                args.add(mr.counterpartId().toString());
            }

            Long restored = redisTemplate.execute(
                    undoMatchScript,
                    Arrays.asList(asksKey, bidsKey, orderIndexKey),
                    args.toArray(new String[0])
            );

            int restoredCount = restored != null ? restored.intValue() : 0;

            logger.info("undoMatch compensação executada: orderId={} incomingType={} matchCount={} restored={}",
                    incomingOrderId, incomingOrderType, matchResults.size(), restoredCount);

            // AT-17: incrementa métrica de compensação
            compensationCounter.increment();

            return restoredCount;

        } catch (Exception ex) {
            // CRITICAL: Redis falhou durante compensação — inconsistência residual inevitável.
            // NÃO faz retry (regra obrigatória). Alertar para resolução manual.
            logger.error("CRITICAL: undoMatch falhou (Redis indisponível durante compensação) — " +
                            "inconsistência residual inevitável. orderId={} incomingType={} error={}",
                    incomingOrderId, incomingOrderType, ex.getMessage(), ex);
            return -1;
        }
    }

    /**
     * Remove a ordem do Sorted Set e do índice reverso via script Lua atômico (AT-04.2).
     *
     * <h3>Caminho principal — O(1) via índice reverso</h3>
     * <p>Executa {@code remove_from_book.lua}: {@code HGET → ZREM → HDEL} no mesmo
     * {@code EVAL}. Para ordens inseridas pelo {@code match_engine.lua}, o índice
     * sempre estará populado e a remoção leva exatamente 3 comandos Redis.</p>
     *
     * <h3>Fallback — ZSCAN O(n)</h3>
     * <p>Ordens inseridas diretamente no Sorted Set (sem passar pelo Lua pipeline,
     * ex: em testes ou durante disaster recovery) não possuem entrada no índice.
     * Nesse caso, o script retorna {@code 0} e o método recorre ao ZSCAN para
     * garantir compatibilidade retroativa. Uma mensagem WARN é emitida para
     * sinalizar o uso do caminho lento.</p>
     *
     * <p>Operação idempotente: se a ordem não for encontrada em nenhum dos dois
     * caminhos, retorna silenciosamente (sem exceção).</p>
     *
     * @param orderId UUID da ordem a remover.
     * @param type    {@code SELL} → remove de {@code asks};
     *                {@code BUY}  → remove de {@code bids}.
     * @throws RuntimeException se ocorrer falha na comunicação com o Redis;
     *                          relançada para NACK/DLQ no container AMQP.
     */
    public void removeFromBook(UUID orderId, OrderType type) {
        // Caminho principal (AT-04.2): remoção O(1) via índice reverso + Lua atômico.
        // KEYS[1]=order_index, KEYS[2]=asks, KEYS[3]=bids declarados para conformidade
        // com Redis Cluster (todos no mesmo hash slot {vibranium}).
        Long luaResult = redisTemplate.execute(
                removeScript,
                Arrays.asList(orderIndexKey, asksKey, bidsKey),
                orderId.toString()
        );

        if (luaResult != null && luaResult == 1L) {
            logger.info("removeFromBook: O(1) via índice reverso orderId={}", orderId);
            return;
        }

        if (luaResult != null && luaResult == -1L) {
            // Entrada corrompida no índice — Lua já executou HDEL; log como warning
            logger.warn("removeFromBook: entrada corrompida no índice — HDEL executado orderId={}", orderId);
            return;
        }

        // Fallback: orderId ausente do índice (inserção fora do Lua pipeline).
        // ZSCAN garante compat. retroativa; WARN sinaliza o caminho lento.
        String key    = OrderType.SELL.equals(type) ? asksKey : bidsKey;
        String prefix = orderId.toString() + "|";
        logger.warn("removeFromBook: orderId={} ausente do índice reverso — fallback ZSCAN (O(n)) key={}",
                orderId, key);

        ScanOptions options = ScanOptions.scanOptions()
                .match(prefix + "*")
                .count(100)
                .build();

        try (Cursor<ZSetOperations.TypedTuple<String>> cursor =
                     redisTemplate.opsForZSet().scan(key, options)) {
            while (cursor.hasNext()) {
                ZSetOperations.TypedTuple<String> tuple = cursor.next();
                String member = tuple.getValue();
                if (member != null && member.startsWith(prefix)) {
                    Long removed = redisTemplate.opsForZSet().remove(key, member);
                    logger.info("removeFromBook: ZSCAN fallback ZREM key={} orderId={} removed={}",
                            key, orderId, removed);
                    return;
                }
            }
        } catch (Exception e) {
            logger.error("removeFromBook: erro no ZSCAN fallback orderId={} key={}: {}",
                    orderId, key, e.getMessage(), e);
            throw e;
        }

        // Membro não encontrado em nenhum caminho — idempotente esperado em reprocessamentos
        logger.debug("removeFromBook: membro não encontrado (idempotente) orderId={} key={}", orderId, key);
    }

    /** Converte elemento retornado pelo Redis em String (byte[] ou String). */
    private static String asString(Object value) {
        if (value instanceof byte[] bytes) return new String(bytes);
        return String.valueOf(value);
    }
}
