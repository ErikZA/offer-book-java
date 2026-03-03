package com.vibranium.walletservice.domain.repository;

import com.vibranium.walletservice.domain.model.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

/**
 * Repositório Spring Data JPA para a tabela de idempotência.
 *
 * <p>Provê a verificação de duplicidade de mensagens RabbitMQ via
 * {@link #existsById(Object)} — lookup O(1) pela chave primária (messageId).</p>
 */
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, String> {

    // Herda existsById(String messageId) do JpaRepository — suficiente para o use case de chegada

    /**
     * Remove chaves de idempotência cujo {@code processed_at} é anterior ao instante
     * {@code cutoff}.
     *
     * <p>Utilizado pelo {@link com.vibranium.walletservice.application.service.IdempotencyKeyCleanupJob}
     * para aplicar a política de retenção de 7 dias. O DELETE em lote via JPQL é mais
     * eficiente do que o comportamento padrão de derived delete (fetch + delete por entidade).</p>
     *
     * <p>Chaves mais recentes que {@code cutoff} são preservadas para garantir proteção
     * contra re-entrega de mensagens dentro da janela de retenção.</p>
     *
     * @param cutoff Instante limite — chaves com {@code processed_at} anterior a este
     *               valor serão removidas.
     * @return Quantidade de registros efetivamente deletados.
     */
    @Modifying
    @Query("DELETE FROM IdempotencyKey k WHERE k.processedAt < :cutoff")
    long deleteByProcessedAtBefore(@Param("cutoff") Instant cutoff);
}

