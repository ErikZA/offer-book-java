package com.vibranium.contracts.events.wallet;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.vibranium.contracts.enums.FailureReason;
import com.vibranium.contracts.events.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Evento compensatório publicado quando a liberação de fundos bloqueados falha.
 *
 * <p>Indica que o {@link com.vibranium.contracts.commands.wallet.ReleaseFundsCommand}
 * não pôde ser executado — geralmente por erro ACID, registro de bloqueio não
 * encontrado, ou violação de idempotência.</p>
 *
 * <p><strong>Atenção:</strong> este é um evento crítico — significa que fundos
 * permanecem bloqueados indevidamente e requer intervenção operacional.
 * Deve acionar alertas no sistema de observabilidade (Grafana/OpsGenie).</p>
 *
 * <p>Consumidor: {@code order-service} — ao receber este evento, deve registrar
 * incidente e acionar processo de reconciliação manual.</p>
 *
 * @param eventId       Identificador único deste evento.
 * @param correlationId Correlação com a Saga.
 * @param aggregateId   ID da carteira ou da ordem (conforme disponível).
 * @param occurredOn    Timestamp da falha.
 * @param orderId       Ordem cujo bloqueio não pôde ser liberado.
 * @param reason        Razão padronizada da falha (enum FailureReason).
 * @param detail        Mensagem técnica adicional para logs/auditoria.
 * @param schemaVersion Versão do contrato para compatibilidade consumer/producer.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FundsReleaseFailedEvent(

        UUID eventId,
        UUID correlationId,
        String aggregateId,

        @JsonFormat(shape = JsonFormat.Shape.NUMBER_INT)
        Instant occurredOn,

        UUID orderId,
        FailureReason reason,
        String detail,

        // Versionamento do contrato — permite deploy independente entre producer e consumer.
        int schemaVersion

) implements DomainEvent {

    /** Compact constructor: garante schemaVersion=1 para payloads antigos (backward compat). */
    public FundsReleaseFailedEvent {
        if (schemaVersion == 0) schemaVersion = 1;
    }

    /** Factory method com geração automática de eventId e occurredOn. */
    public static FundsReleaseFailedEvent of(UUID correlationId, UUID orderId,
                                             String aggregateId,
                                             FailureReason reason, String detail) {
        return new FundsReleaseFailedEvent(
                UUID.randomUUID(),
                correlationId,
                aggregateId,
                Instant.now(),
                orderId,
                reason,
                detail,
                1
        );
    }
}
