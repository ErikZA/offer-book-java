package com.vibranium.orderservice.domain.repository;

import com.vibranium.orderservice.domain.model.OrderOutboxMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
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

    /**
     * Retorna a mensagem mais recente do outbox com o aggregateId e eventType informados.
     *
     * <p>Utilizado pelo {@link com.vibranium.orderservice.adapter.messaging.FundsSettlementFailedEventConsumer}
     * para recuperar o {@code MatchExecutedEvent} armazenado durante o processamento
     * do {@code FundsReservedEvent}. O payload do {@code MatchExecutedEvent} contém
     * os IDs de carteira (buyer/seller) necessários para emitir os {@code ReleaseFundsCommand}
     * de compensação (AT-1.1.4).</p>
     *
     * <p>Os registros do outbox são marcados com {@code published_at} mas nunca deletados,
     * garantindo que este lookup seja sempre possível para auditoria e compensação.</p>
     *
     * @param aggregateId ID do agregado (orderId da ordem que disparou o match).
     * @param eventType   Tipo do evento (ex: {@code "MatchExecutedEvent"}).
     * @return Optional com a mensagem, ou empty se não encontrada.
     */
    Optional<OrderOutboxMessage> findFirstByAggregateIdAndEventType(UUID aggregateId, String eventType);

    /**
     * Seleciona mensagens pendentes com lock pessimista e SKIP LOCKED.
     *
     * <p>Permite que múltiplas instâncias do order-service processem
     * lotes diferentes de mensagens em paralelo sem duplicatas.</p>
     *
     * @param batchSize Número máximo de mensagens a retornar.
     * @return Lista de mensagens pendentes, locked pela transação corrente.
     */
    @Query(value = """
        SELECT * FROM tb_order_outbox
        WHERE published_at IS NULL
        ORDER BY created_at ASC
        LIMIT :batchSize
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<OrderOutboxMessage> findPendingWithLock(@Param("batchSize") int batchSize);
}
