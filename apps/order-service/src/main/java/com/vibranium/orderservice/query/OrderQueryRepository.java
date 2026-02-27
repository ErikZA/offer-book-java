package com.vibranium.orderservice.query;

import com.vibranium.orderservice.domain.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository para leitura de ordens (Query side do CQRS)
 */
@Repository
public interface OrderQueryRepository extends MongoRepository<Order, String> {

    List<Order> findByUserId(String userId);

    List<Order> findBySymbol(String symbol);

    List<Order> findBySymbolAndSide(String symbol, String side);

    Page<Order> findByUserId(String userId, Pageable pageable);

    // Para o order book: ordens ativas por símbolo
    List<Order> findBySymbolAndStatusNot(String symbol, String status);

}
