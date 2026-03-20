package com.vibranium.orderservice.domain.repository;

import com.vibranium.orderservice.domain.model.OrderOutboxMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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
     * <p>Utilizado pelo {@link com.vibranium.orderservice.infrastructure.messaging.FundsSettlementFailedEventConsumer}
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

    /**
     * Conta mensagens pendentes (não publicadas) no outbox.
     *
     * <p>AT-15.2: utilizado pelo {@code Gauge vibranium.outbox.queue.depth}
     * para expor a profundidade do backlog ao Prometheus.</p>
     *
     * @return Número de mensagens com {@code published_at IS NULL}.
     */
    @Query("SELECT COUNT(m) FROM OrderOutboxMessage m WHERE m.publishedAt IS NULL")
    long countPending();

    /**
     * Retorna todas as mensagens de outbox de um agregado específico, ordenadas por criação.
     *
     * <p>Usado pelo {@link com.vibranium.orderservice.application.query.service.ProjectionRebuildService}
     * para reconstruir o histórico de eventos de uma ordem durante o rebuild da projeção MongoDB.</p>
     *
     * @param aggregateId ID do agregado (orderId).
     * @return Lista de mensagens ordenadas cronologicamente.
     */
    List<OrderOutboxMessage> findByAggregateIdOrderByCreatedAtAsc(UUID aggregateId);

    /**
     * Remove mensagens já processadas criadas antes do instante {@code cutoff}.
     *
     * <p>Utilizado pelo {@link com.vibranium.orderservice.application.service.OutboxCleanupJob}
     * para aplicar a política de retenção de 7 dias sem carregar entidades em memória.
     * O DELETE em lote via JPQL é mais eficiente do que o comportamento padrão
     * de derived delete (fetch + delete por entidade).</p>
     *
     * <p>Apenas mensagens com {@code published_at != null} são elegíveis, garantindo que
     * o outbox pendente jamais seja tocado por esta operação.</p>
     *
     * @param cutoff Instante limite — mensagens com {@code created_at} anterior a este
     *               valor e {@code published_at != null} serão removidas.
     * @return Quantidade de registros efetivamente deletados.
     */
    @Modifying
    @Query("DELETE FROM OrderOutboxMessage m WHERE m.publishedAt != null AND m.createdAt < :cutoff")
    long deleteByPublishedAtNotNullAndCreatedAtBefore(@Param("cutoff") java.time.Instant cutoff);


    /**
     * Claim atômico: {@code UPDATE ... WHERE processed=false}.
     *
     * <p>O publisher executa esta query para marcar a mensagem como processada
     * (publicada) de forma atômica. Se a query retornar {@code 0}, significa
     * que outra instância já processou a mensagem — o publisher atual deve
     * descartar silenciosamente o evento, sem lançar exceção nem tentar publicar
     * novamente.</p>
     *
     * <p>Este mecanismo é crítico para garantir que, mesmo com múltiplas instâncias
     * do serviço rodando em paralelo, cada mensagem seja publicada no RabbitMQ
     * exatamente uma vez. Instâncias concorrentes competem para reclamar mensagens,
     * e apenas uma delas consegue marcar a mensagem como processada — as demais recebem
     * {@code 0} e descartam silenciosamente o evento, evitando publicações duplicadas.</p>
     *
     * <p>O método é utilizado pelo {@link com.vibranium.orderservice.application.service.OrderOutboxPublisherService}
     * no método {@code beforePublish} para garantir que apenas uma instância publique
     * cada mensagem, mesmo em cenários de alta concorrência e falhas intermitentes.</p>
     *  
     * <p>Em caso de falha de infraestrutura (ex: RabbitMQ indisponível) durante a publicação,
     * a transação é revertida, o que significa que a mensagem permanece com {@code published_at IS NULL}
     * e será reprocessada no próximo ciclo do publisher. O claim atômico garante que, mesmo com falhas e múltiplas instâncias, 
     * cada mensagem seja publicada no RabbitMQ no máximo uma vez, garantindo a entrega "at least once" sem duplicações.</p>
     * @param id UUID da mensagem a ser reclamada.
     * @return {@code 1} se a mensagem foi reclamada com sucesso, {@code 0} se já estava processada por outra instância.
     */    
    @Modifying
    @Query("UPDATE OrderOutboxMessage m SET m.publishedAt = CURRENT_TIMESTAMP WHERE m.id = :id AND m.publishedAt IS NULL")
    int claimAndMarkPublished(@Param("id") UUID id);
}
