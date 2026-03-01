package com.vibranium.orderservice.domain.repository;

import com.vibranium.orderservice.domain.model.OrderOutboxMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Repositório Spring Data JPA para a tabela {@code tb_order_outbox}.
 *
 * <p>Provê as queries necessárias para o
 * {@link com.vibranium.orderservice.application.service.OrderOutboxPublisherService}
 * fazer o relay das mensagens pendentes para o RabbitMQ.</p>
 *
 * <p>O índice parcial {@code idx_order_outbox_unpublished} no PostgreSQL
 * ({@code WHERE published_at IS NULL}) garante performance O(1) na busca
 * de mensagens pendentes.</p>
 */
public interface OrderOutboxRepository extends JpaRepository<OrderOutboxMessage, UUID> {

    /**
     * Retorna todas as mensagens que ainda não foram publicadas no broker.
     * Usa o índice parcial {@code WHERE published_at IS NULL}.
     *
     * @return Lista de mensagens pendentes, ordenadas por {@code created_at} implícita.
     */
    List<OrderOutboxMessage> findByPublishedAtIsNull();
}
