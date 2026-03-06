package com.vibranium.walletservice.domain.repository;

import com.vibranium.walletservice.domain.model.OutboxMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Repositório Spring Data JPA para o Transactional Outbox.
 *
 * <p>O relay do Outbox usa polling com {@code SELECT FOR UPDATE SKIP LOCKED}
 * e {@link #claimAndMarkProcessed(UUID)} para garantir processamento
 * exatamente-uma-vez em cenários de múltiplas instâncias. O índice parcial
 * {@code WHERE processed = FALSE} garante performance O(pendentes).</p>
 */
public interface OutboxMessageRepository extends JpaRepository<OutboxMessage, UUID> {

    /**
     * Retorna todas as mensagens não processadas ordenadas por data de criação.
     * Mantido para compatibilidade com testes existentes e fallback de consulta.
     */
    List<OutboxMessage> findByProcessedFalseOrderByCreatedAtAsc();

    /**
     * Seleciona mensagens pendentes com lock pessimista e SKIP LOCKED.
     *
     * <p>A cláusula {@code FOR UPDATE} adquire um row-level lock exclusivo.
     * {@code SKIP LOCKED} faz com que linhas já bloqueadas por outra transação
     * sejam silenciosamente ignoradas — permitindo que múltiplas instâncias
     * processem lotes diferentes em paralelo sem deadlocks.</p>
     *
     * <p>O índice parcial {@code WHERE processed = FALSE} garante
     * performance O(pendentes) mesmo com milhões de registros na tabela.</p>
     *
     * @param batchSize Número máximo de mensagens a retornar por ciclo.
     * @return Lista de mensagens pendentes, locked pela transação corrente.
     */
    @Query(value = """
        SELECT * FROM outbox_message
        WHERE processed = false
        ORDER BY created_at ASC
        LIMIT :batchSize
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<OutboxMessage> findPendingWithLock(@Param("batchSize") int batchSize);

    /**
     * Retorna mensagens não processadas em página, limitando o volume carregado
     * por ciclo de processamento. Configurável via {@code app.outbox.batch-size}.
     *
     * <p>Previne OOM em cenários de acúmulo de mensagens pendentes — substitui
     * o overload sem {@link Pageable} quando se deseja controle de lote.</p>
     *
     * @param pageable Configuração de página e tamanho do lote.
     * @return Página de mensagens pendentes ordenadas por {@code created_at} ASC.
     */
    Page<OutboxMessage> findByProcessedFalseOrderByCreatedAtAsc(Pageable pageable);

    /**
     * Tenta marcar atomicamente uma mensagem como processada usando
     * {@code WHERE processed = false} como condição de guarda (optimistic claim).
     *
     * <p><b>Garantia de concorrência:</b> em ambientes multi-instância onde cada
     * instância recebe o mesmo evento via WAL (slots de replicação distintos),
     * apenas a primeira instância que executar este UPDATE com sucesso
     * (retorna {@code 1}) irá publicar no RabbitMQ. As demais recebem {@code 0}
     * e descartam silenciosamente o evento — sem publicação duplicada.</p>
     *
     * @param id UUID da mensagem a ser reclamada.
     * @return {@code 1} se a mensagem foi reclamada com sucesso, {@code 0} se
     *         já estava processada por outra instância.
     */
    @Modifying
    @Query("UPDATE OutboxMessage m SET m.processed = true WHERE m.id = :id AND m.processed = false")
    int claimAndMarkProcessed(@Param("id") UUID id);

    /**
     * Conta mensagens por tipo de evento. Utilizado em testes de integração
     * para verificar que o número correto de eventos foi gravado no outbox.
     *
     * @param eventType Nome do tipo do evento (ex: {@code "FundsReservedEvent"}).
     * @return Quantidade de mensagens com o tipo informado.
     */
    long countByEventType(String eventType);

    /**
     * Conta mensagens não processadas por tipo de evento.
     * Utilizado no teste de carga para verificar que todas as mensagens
     * foram publicadas após o ciclo do publisher.
     *
     * @param eventType Nome do tipo do evento.
     * @return Quantidade de mensagens com {@code processed = false}.
     */
    long countByProcessedFalseAndEventType(String eventType);

    /**
     * Remove mensagens já processadas criadas antes do instante {@code cutoff}.
     *
     * <p>Utilizado pelo {@link com.vibranium.walletservice.application.service.OutboxCleanupJob}
     * para aplicar a política de retenção de 7 dias sem carregar entidades em memória.
     * O DELETE em lote via JPQL é mais eficiente do que o comportamento padrão
     * de derived delete (fetch + delete por entidade).</p>
     *
     * <p>Apenas mensagens com {@code processed=true} são elegíveis, garantindo que
     * o outbox pendente jamais seja tocado por esta operação.</p>
     *
     * @param cutoff Instante limite — mensagens com {@code created_at} anterior a este
     *               valor e {@code processed=true} serão removidas.
     * @return Quantidade de registros efetivamente deletados.
     */
    @Modifying
    @Query("DELETE FROM OutboxMessage m WHERE m.processed = true AND m.createdAt < :cutoff")
    long deleteByProcessedTrueAndCreatedAtBefore(@Param("cutoff") java.time.Instant cutoff);

    /**
     * Conta mensagens pendentes (não processadas) no outbox.
     *
     * <p>AT-15.2: utilizado pelo {@code Gauge vibranium.outbox.queue.depth}
     * para expor a profundidade do backlog ao Prometheus.</p>
     *
     * @return Número de mensagens com {@code processed = false}.
     */
    long countByProcessedFalse();
}

