package com.vibranium.orderservice.domain.repository;

import com.vibranium.contracts.enums.OrderStatus;
import com.vibranium.orderservice.domain.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
}
