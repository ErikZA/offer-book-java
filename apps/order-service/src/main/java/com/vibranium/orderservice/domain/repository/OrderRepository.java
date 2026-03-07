package com.vibranium.orderservice.domain.repository;

import com.vibranium.contracts.enums.OrderStatus;
import com.vibranium.orderservice.domain.model.Order;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.hibernate.jpa.HibernateHints.HINT_FETCH_SIZE;

/**
 * Repositório Spring Data JPA para {@link Order}.
 *
 * <p>Consultas focadas no Command Side da Saga:</p>
 * <ul>
 *   <li>Busca por {@code correlationId} — handler da Saga atualiza estado pelo correlationId.</li>
 *   <li>Contagem por status — monitoramento e throughput de testes de carga.</li>
 * </ul>
 *
 * <p>Atenção: este repositório é do Command Side. Consultas de leitura
 * (histórico, order book) devem vir do Read Model (MongoDB/Redis), nunca daqui.</p>
 */
@Repository
public interface OrderRepository extends JpaRepository<Order, UUID> {

    /**
     * Busca uma ordem pelo seu correlationId de Saga.
     *
     * <p>Usado pelos consumidores de eventos (FundsReservedEvent,
     * FundsReservationFailedEvent) para localizar a ordem a ser atualizada.</p>
     *
     * @param correlationId UUID da correlação da Saga.
     * @return Optional com a ordem, ou empty se não encontrada.
     */
    Optional<Order> findByCorrelationId(UUID correlationId);

    /**
     * Conta ordens com o correlationId informado.
     *
     * <p>Usado para detectar duplicação de Saga — deve retornar sempre 0 ou 1.</p>
     *
     * @param correlationId UUID da correlação da Saga.
     * @return Número de ordens com esse correlationId.
     */
    long countByCorrelationId(UUID correlationId);

    /**
     * Conta ordens por status — usado em testes de throughput e monitoramento.
     *
     * @param status Status desejado.
     * @return Número de ordens no status especificado.
     */
    long countByStatus(OrderStatus status);

    /**
     * Retorna ordens cujo status é o informado e cujo {@code createdAt} é anterior
     * ao instante de corte — utilizado pelo {@link com.vibranium.orderservice.application.service.SagaTimeoutCleanupJob}
     * para identificar ordens presas em {@code PENDING} além do tempo limite da Saga.
     *
     * <p>O índice parcial {@code idx_orders_status_created_at} (Flyway V6) suporta
     * esta query com performance O(log n), evitando full scan em alta carga.</p>
     *
     * <p>Spring Data deriva o SQL: {@code WHERE status = ? AND created_at < ?}</p>
     *
     * @param status  Status esperado (geralmente {@code OrderStatus.PENDING}).
     * @param cutoff  Instante de corte: apenas ordens criadas ANTES deste instante são retornadas.
     * @return Lista de ordens elegíveis para cancelamento por timeout; pode ser vazia, nunca {@code null}.
     */
    List<Order> findByStatusAndCreatedAtBefore(OrderStatus status, Instant cutoff);

    /**
     * Retorna ordens cujo status está entre os informados e cujo {@code createdAt} é anterior
     * ao instante de corte.
     *
     * <p>Utilizado pelo {@link com.vibranium.orderservice.application.service.SagaTimeoutCleanupJob}
     * para incluir ordens {@code OPEN} e {@code PARTIAL} no cleanup por timeout (AT-1.1.4).
     * Ordens nesses estados já tiveram fundos reservados e precisam de compensação
     * via {@link com.vibranium.contracts.commands.wallet.ReleaseFundsCommand}.</p>
     *
     * <p>Spring Data deriva: {@code WHERE status IN (?) AND created_at < ?}</p>
     *
     * @param statuses  Coleção de status elegíveis.
     * @param cutoff    Instante de corte.
     * @return Lista de ordens elegíveis; nunca {@code null}.
     */
    List<Order> findByStatusInAndCreatedAtBefore(Collection<OrderStatus> statuses, Instant cutoff);

    /**
     * Stream de todas as ordens com cursor PostgreSQL (fetch size 500).
     *
     * <p>Usado pelo {@link com.vibranium.orderservice.application.query.service.ProjectionRebuildService}
     * para reconstruir a projeção MongoDB sem carregar todas as ordens em memória.
     * O {@code HINT_FETCH_SIZE} configura o JDBC fetch size, forçando o driver PostgreSQL
     * a usar um cursor server-side que busca 500 linhas por vez.</p>
     *
     * <p><strong>Obrigatório:</strong> deve ser consumido dentro de {@code @Transactional}
     * para manter o cursor aberto (autocommit=false é pré-requisito do PostgreSQL cursor).</p>
     *
     * @return Stream lazy de todas as ordens.
     */
    @QueryHints(@QueryHint(name = HINT_FETCH_SIZE, value = "500"))
    @Query("SELECT o FROM Order o")
    Stream<Order> streamAll();

    /**
     * Stream de ordens criadas ou atualizadas após o instante informado.
     *
     * <p>Usado pelo rebuild incremental para processar apenas ordens modificadas
     * desde o último rebuild, evitando reprocessamento desnecessário.</p>
     *
     * @param since Instante de corte (exclusive): retorna ordens com createdAt ou updatedAt posterior.
     * @return Stream lazy das ordens modificadas.
     */
    @QueryHints(@QueryHint(name = HINT_FETCH_SIZE, value = "500"))
    @Query("SELECT o FROM Order o WHERE o.createdAt > :since OR (o.updatedAt IS NOT NULL AND o.updatedAt > :since)")
    Stream<Order> streamModifiedAfter(@Param("since") Instant since);

    /**
     * Conta ordens criadas ou atualizadas após o instante informado.
     *
     * @param since Instante de corte.
     * @return Número de ordens modificadas após o instante.
     */
    @Query("SELECT COUNT(o) FROM Order o WHERE o.createdAt > :since OR (o.updatedAt IS NOT NULL AND o.updatedAt > :since)")
    long countModifiedAfter(@Param("since") Instant since);
}
