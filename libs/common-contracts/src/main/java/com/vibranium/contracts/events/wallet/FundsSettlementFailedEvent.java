package com.vibranium.contracts.events.wallet;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.vibranium.contracts.enums.FailureReason;
import com.vibranium.contracts.events.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Evento compensatório publicado quando a liquidação de um trade falha.
 *
 * <p>Indica que o {@link com.vibranium.contracts.commands.wallet.SettleFundsCommand}
 * não foi executado — geralmente por erro ACID ou duplicidade.
 * A Saga deve acionar mecanismos de compensação manuais (alerta operacional)
 * pois os fundos continuam bloqueados.</p>
 *
 * <p><strong>Atenção:</strong> este é um evento crítico de incidente — deve
 * acionar alertas no sistema de observabilidade (Grafana/OpsGenie).</p>
 *
 * @param eventId       Identificador único deste evento.
 * @param correlationId Correlação com a Saga.
 * @param aggregateId   matchId que falhou.
 * @param occurredOn    Timestamp da falha.
 * @param matchId       ID do match que não foi liquidado.
 * @param reason        Razão padronizada da falha.
 * @param detail        Mensagem técnica para logs/auditoria.
 */
public record FundsSettlementFailedEvent(

        UUID eventId,
        UUID correlationId,
        String aggregateId,

        @JsonFormat(shape = JsonFormat.Shape.NUMBER_INT)
        Instant occurredOn,

        UUID matchId,
        FailureReason reason,
        String detail,

        // Versionamento do contrato — permite deploy independente entre producer e consumer.
        int schemaVersion

) implements DomainEvent {

    /** Compact constructor: garante schemaVersion=1 para payloads antigos (backward compat). */
    public FundsSettlementFailedEvent {
        if (schemaVersion == 0) schemaVersion = 1;
    }

    /** Factory method com geração automática de eventId e occurredOn. */
    public static FundsSettlementFailedEvent of(UUID correlationId, UUID matchId,
                                                FailureReason reason, String detail) {
        return new FundsSettlementFailedEvent(
                UUID.randomUUID(),
                correlationId,
                matchId.toString(),
                Instant.now(),
                matchId,
                reason,
                detail,
                1
        );
    }
}
