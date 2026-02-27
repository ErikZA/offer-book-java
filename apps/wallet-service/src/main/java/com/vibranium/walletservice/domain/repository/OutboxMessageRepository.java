package com.vibranium.walletservice.domain.repository;

import com.vibranium.walletservice.domain.model.OutboxMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Repositório Spring Data JPA para o Transactional Outbox.
 *
 * <p>O scheduler de relay consulta {@link #findByProcessedFalseOrderByCreatedAtAsc()}
 * periodicamente para publicar eventos pendentes no RabbitMQ (entrega at-least-once).
 * O índice parcial {@code WHERE processed = FALSE} garante performance constante
 * independentemente do volume histórico de mensagens.</p>
 */
public interface OutboxMessageRepository extends JpaRepository<OutboxMessage, UUID> {

    /**
     * Retorna todas as mensagens não processadas ordenadas por data de criação.
     * Utilizado pelo relay do outbox para publicar em ordem FIFO.
     */
    List<OutboxMessage> findByProcessedFalseOrderByCreatedAtAsc();

    /**
     * Conta mensagens por tipo de evento. Utilizado em testes de integração
     * para verificar que o número correto de eventos foi gravado no outbox.
     *
     * @param eventType Nome do tipo do evento (ex: {@code "FundsReservedEvent"}).
     * @return Quantidade de mensagens com o tipo informado.
     */
    long countByEventType(String eventType);
}
