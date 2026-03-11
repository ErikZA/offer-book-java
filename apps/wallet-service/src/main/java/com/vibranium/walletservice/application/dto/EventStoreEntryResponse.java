package com.vibranium.walletservice.application.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO de resposta para consultas ao Event Store via endpoint de auditoria.
 *
 * <p>Projeção simplificada da {@link com.vibranium.walletservice.domain.model.EventStoreEntry}
 * para exposição na API REST administrativa.</p>
 *
 * @param sequenceId    sequência global do evento no Event Store.
 * @param eventId       UUID único do evento.
 * @param aggregateId   ID do agregado que originou o evento.
 * @param aggregateType tipo do agregado (ex: "Wallet").
 * @param eventType     tipo do evento (ex: "FundsReservedEvent").
 * @param payload       payload JSON do evento.
 * @param occurredOn    timestamp de quando o fato ocorreu.
 * @param correlationId ID de correlação da Saga.
 * @param schemaVersion versão do schema do evento.
 */
public record EventStoreEntryResponse(
        Long sequenceId,
        UUID eventId,
        String aggregateId,
        String aggregateType,
        String eventType,
        String payload,
        Instant occurredOn,
        UUID correlationId,
        int schemaVersion
) {}
