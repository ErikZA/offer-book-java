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

/**
 * Adaptador do Motor de Match baseado em Redis Sorted Set.
 *
 * <p>Executa o Script Lua {@code match_engine.lua} atomicamente via
 * {@code EVALSHA}, garantindo que dois eventos {@code FundsReservedEvent}
 * concorrentes nunca executem contra o mesmo ASK ou BID simultanêamente.</p>
 *
 * <h2>Estrutura dos Sorted Sets</h2>
 * <ul>
 *   <li>Key {@code vibranium:asks}: ASKs com score = price × 1_000_000 (menor preço primeiro)</li>
 *   <li>Key {@code vibranium:bids}: BIDs com score = price × 1_000_000 (maior preço primeiro via ZREVRANGEBYSCORE)</li>
 * </ul>
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
     * @param matched       {@code true} se houve cruzamento com contraparte.
     * @param counterpartId UUID da ordem contraparte (ou {@code null} se sem match).
     * @param counterpartUserId   userId da contraparte.
     * @param counterpartWalletId walletId da contraparte.
     * @param matchedQty    Quantidade executada no match.
     * @param fillType      "FULL", "PARTIAL_ASK" ou "PARTIAL_BID".
     */
    public record MatchResult(
            boolean matched,
            UUID counterpartId,
            String counterpartUserId,
            UUID counterpartWalletId,
            BigDecimal matchedQty,
            String fillType
    ) {
        public static MatchResult noMatch() {
            return new MatchResult(false, null, null, null, BigDecimal.ZERO, "NO_MATCH");
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
     */
    private static MatchResult parseResult(List<Object> result) {
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
                    fillType
            );
        } catch (Exception e) {
            logger.error("Erro ao parsear resultado do match engine: value={} error={}",
                    counterpartValue, e.getMessage());
            return MatchResult.noMatch();
        }
    }

    /** Converte elemento retornado pelo Redis em String (byte[] ou String). */
    private static String asString(Object value) {
        if (value instanceof byte[] bytes) return new String(bytes);
        return String.valueOf(value);
    }
}
