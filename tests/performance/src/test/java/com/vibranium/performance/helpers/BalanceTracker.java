package com.vibranium.performance.helpers;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rastreador thread-safe de saldos esperados por usuário.
 *
 * <p>Registra todas as ordens de compra e venda durante a simulação e calcula
 * o saldo esperado de cada carteira ao final. Utilizado na fase de validação
 * (after hook) para comparar com os valores reais do sistema.</p>
 *
 * <h3>Cálculo de saldo esperado:</h3>
 * <ul>
 *   <li>BRL esperado = inicial - (totalBuyVib × preço) + (totalSellVib × preço)</li>
 *   <li>VIB esperado = inicial + totalBuyVib - totalSellVib</li>
 * </ul>
 */
public final class BalanceTracker {

    private static final int SCALE = 8;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    // userId → [totalBuyVib, totalSellVib]
    private final ConcurrentHashMap<String, BigDecimal[]> userTrades = new ConcurrentHashMap<>();

    /**
     * Registra uma ordem de compra (BUY) para o usuário.
     *
     * @param userId Keycloak ID do usuário comprador.
     * @param vibAmount Quantidade de VIB comprada.
     */
    public synchronized void recordBuy(String userId, BigDecimal vibAmount) {
        BigDecimal[] totals = userTrades.computeIfAbsent(userId,
                k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
        totals[0] = totals[0].add(vibAmount);
    }

    /**
     * Registra uma ordem de venda (SELL) para o usuário.
     *
     * @param userId Keycloak ID do usuário vendedor.
     * @param vibAmount Quantidade de VIB vendida.
     */
    public synchronized void recordSell(String userId, BigDecimal vibAmount) {
        BigDecimal[] totals = userTrades.computeIfAbsent(userId,
                k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
        totals[1] = totals[1].add(vibAmount);
    }

    /**
     * Calcula o saldo BRL esperado após todas as ordens serem liquidadas.
     */
    public BigDecimal expectedBrl(String userId, BigDecimal initialBrl, BigDecimal price) {
        BigDecimal[] totals = userTrades.getOrDefault(userId,
                new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
        BigDecimal brlSpent = totals[0].multiply(price).setScale(SCALE, ROUNDING);
        BigDecimal brlEarned = totals[1].multiply(price).setScale(SCALE, ROUNDING);
        return initialBrl.subtract(brlSpent).add(brlEarned).setScale(SCALE, ROUNDING);
    }

    /**
     * Calcula o saldo VIB esperado após todas as ordens serem liquidadas.
     */
    public BigDecimal expectedVib(String userId, BigDecimal initialVib) {
        BigDecimal[] totals = userTrades.getOrDefault(userId,
                new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
        return initialVib.add(totals[0]).subtract(totals[1]).setScale(SCALE, ROUNDING);
    }

    /**
     * Retorna snapshot das negociações registradas para relatório.
     *
     * @return Mapa userId → [totalBuyVib, totalSellVib].
     */
    public Map<String, BigDecimal[]> snapshot() {
        return Map.copyOf(userTrades);
    }
}
