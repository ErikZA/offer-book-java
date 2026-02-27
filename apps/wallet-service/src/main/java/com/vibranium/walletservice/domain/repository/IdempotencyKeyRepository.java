package com.vibranium.walletservice.domain.repository;

import com.vibranium.walletservice.domain.model.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositório Spring Data JPA para a tabela de idempotência.
 *
 * <p>Provê a verificação de duplicidade de mensagens RabbitMQ via
 * {@link #existsById(Object)} — lookup O(1) pela chave primária (messageId).</p>
 */
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, String> {
    // Herda existsById(String messageId) do JpaRepository — suficiente para o use case
}
