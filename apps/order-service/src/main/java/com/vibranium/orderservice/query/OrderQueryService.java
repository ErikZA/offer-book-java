package com.vibranium.orderservice.query;

import com.vibranium.orderservice.domain.Order;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Service de Queries (Read side do CQRS)
 */
@Slf4j
@Service
public class OrderQueryService {

    private final OrderQueryRepository queryRepository;

    @Autowired
    public OrderQueryService(OrderQueryRepository queryRepository) {
        this.queryRepository = queryRepository;
    }

    /**
     * Busca uma ordem por ID
     */
    public Optional<Order> getOrderById(String orderId) {
        log.debug("Buscando ordem: {}", orderId);
        return queryRepository.findById(orderId);
    }

    /**
     * Busca todas as ordens de um usuário
     */
    public List<Order> getUserOrders(String userId) {
        log.debug("Buscando ordens do usuário: {}", userId);
        return queryRepository.findByUserId(userId);
    }

    /**
     * Busca com paginação
     */
    public Page<Order> getUserOrdersPaginated(String userId, Pageable pageable) {
        log.debug("Buscando ordens (paginado) do usuário: {}", userId);
        return queryRepository.findByUserId(userId, pageable);
    }

    /**
     * Busca o order book (ordens ativas) para um símbolo
     */
    public List<Order> getActiveOrderBook(String symbol) {
        log.debug("Buscando order book ativo para: {}", symbol);
        return queryRepository.findBySymbolAndStatusNot(symbol, "CANCELLED");
    }

    /**
     * Busca ordens de um lado específico do book
     */
    public List<Order> getOrderBookSide(String symbol, String side) {
        log.debug("Buscando {} side do order book para: {}", side, symbol);
        return queryRepository.findBySymbolAndSide(symbol, side);
    }

}
