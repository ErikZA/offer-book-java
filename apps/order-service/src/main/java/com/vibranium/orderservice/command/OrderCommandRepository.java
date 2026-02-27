package com.vibranium.orderservice.command;

import com.vibranium.orderservice.domain.Order;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository para escrita de ordens (Command side do CQRS)
 */
@Repository
public interface OrderCommandRepository extends MongoRepository<Order, String> {

}
