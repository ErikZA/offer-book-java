package com.vibranium.walletservice.application.service;

import com.vibranium.walletservice.domain.model.EventStoreEntry;
import com.vibranium.walletservice.domain.repository.EventStoreRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Serviço de aplicação para gravação e consulta do Event Store imutável.
 *
 * <p>Responsável por:</p>
 * <ul>
 *   <li>Gravar eventos no Event Store (append-only) na mesma transação do outbox.</li>
 *   <li>Prover replay de eventos por agregado — completo ou limitado por timestamp.</li>
 *   <li>Suportar auditoria e compliance regulatório financeiro.</li>
 * </ul>
 *
 * <p><strong>Nota:</strong> este serviço NÃO gerencia transações próprias.
 * O método {@code append} deve ser chamado dentro de uma transação existente
 * (ex: dentro do {@link WalletService#reserveFunds} ou {@link WalletService#settleFunds})
 * para garantir atomicidade com o outbox e a entidade do agregado.</p>
 */
@Service
public class EventStoreService {

    private static final Logger logger = LoggerFactory.getLogger(EventStoreService.class);

    private final EventStoreRepository eventStoreRepository;

    public EventStoreService(EventStoreRepository eventStoreRepository) {
        this.eventStoreRepository = eventStoreRepository;
    }

    /**
     * Grava um evento no Event Store (append-only).
     *
     * <p>Deve ser chamado dentro de uma transação existente para garantir
     * atomicidade com a gravação no outbox e no agregado.</p>
     *
     * @param eventId       UUID único do evento.
     * @param aggregateId   ID do agregado (ex: walletId).
     * @param aggregateType tipo do agregado (ex: "Wallet").
     * @param eventType     tipo do evento (ex: "FundsReservedEvent").
     * @param payload       JSON serializado do evento.
     * @param occurredOn    timestamp de quando o fato ocorreu.
     * @param correlationId ID de correlação da Saga.
     * @param schemaVersion versão do schema do evento.
     */
    public void append(UUID eventId, String aggregateId, String aggregateType,
                       String eventType, String payload, Instant occurredOn,
                       UUID correlationId, int schemaVersion) {

        EventStoreEntry entry = new EventStoreEntry(
                eventId, aggregateId, aggregateType, eventType,
                payload, occurredOn, correlationId, schemaVersion
        );
        eventStoreRepository.save(entry);

        logger.debug("Event Store: appended {} for aggregate {}:{} (correlationId={})",
                eventType, aggregateType, aggregateId, correlationId);
    }

    /**
     * Retorna todos os eventos de um agregado, ordenados pela sequência de inserção.
     *
     * @param aggregateId ID do agregado.
     * @return Lista ordenada de eventos.
     */
    public List<EventStoreEntry> getEventsByAggregateId(String aggregateId) {
        return eventStoreRepository.findByAggregateIdOrderBySequenceIdAsc(aggregateId);
    }

    /**
     * Retorna eventos de um agregado até um instante específico (inclusive).
     * Usado para reconstrução temporal: "estado da Wallet X no instante T".
     *
     * @param aggregateId ID do agregado.
     * @param until       timestamp limite (inclusive).
     * @return Lista ordenada de eventos até o instante informado.
     */
    public List<EventStoreEntry> getEventsUntil(String aggregateId, Instant until) {
        return eventStoreRepository.findByAggregateIdAndOccurredOnLessThanEqualOrderBySequenceIdAsc(
                aggregateId, until);
    }
}
