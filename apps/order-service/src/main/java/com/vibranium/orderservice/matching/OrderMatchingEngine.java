package com.vibranium.orderservice.matching;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Set;

/**
 * Engine de matching em memória usando Redis Sorted Sets
 * 
 * Estrutura:
 * - order_book:EUR/USD:BUY:  Sorted Set de ordens de compra (score = preço DESC)
 * - order_book:EUR/USD:SELL: Sorted Set de ordens de venda (score = preço ASC)
 */
@Slf4j
@Service
public class OrderMatchingEngine {

    private final RedisTemplate<String, String> redisTemplate;

    @Autowired
    public OrderMatchingEngine(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Busca matches possíveis para uma nova ordem
     */
    public void matchOrder(String orderId, String symbol, String side, BigDecimal price, BigDecimal quantity) {
        String buyBookKey = String.format("order_book:%s:BUY", symbol);
        String sellBookKey = String.format("order_book:%s:SELL", symbol);

        if ("BUY".equals(side)) {
            // Nova ordem de compra: busca no book de venda
            matchAgainstBook(orderId, sellBookKey, price, quantity);
        } else if ("SELL".equals(side)) {
            // Nova ordem de venda: busca no book de compra
            matchAgainstBook(orderId, buyBookKey, price, quantity);
        }

        // Adicionar ordem ao seu livro se não totalmente preenchida
        String orderBookKey = "BUY".equals(side) ? buyBookKey : sellBookKey;
        double scoreInverse = "BUY".equals(side) ? -price.doubleValue() : price.doubleValue();
        redisTemplate.opsForZSet().add(orderBookKey, orderId, scoreInverse);

        log.info("Ordem {} adicionada ao order book {}", orderId, orderBookKey);
    }

    private void matchAgainstBook(String orderId, String oppositeBookKey, BigDecimal price, BigDecimal quantity) {
        // Lógica simplificada: verificar possibilidades de match
        Set<String> potentialMatches = redisTemplate.opsForZSet().range(oppositeBookKey, 0, -1);
        log.debug("Buscando matches: {} candidatos encontrados", potentialMatches != null ? potentialMatches.size() : 0);

        // TODO: Implementar matching engine completo com partial fills
        // Emitir OrderMatchedEvent
    }

    /**
     * Remove ordem do order book
     */
    public void removeOrder(String symbol, String side, String orderId) {
        String bookKey = String.format("order_book:%s:%s", symbol, side);
        redisTemplate.opsForZSet().remove(bookKey, orderId);
        log.info("Ordem {} removida do book {}", orderId, bookKey);
    }

    /**
     * Obtém profundidade do livro (top N)
     */
    public Set<String> getOrderBookDepth(String symbol, String side, int depth) {
        String bookKey = String.format("order_book:%s:%s", symbol, side);
        return redisTemplate.opsForZSet().range(bookKey, 0, depth - 1);
    }

}
