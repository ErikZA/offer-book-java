package com.vibranium.contracts.events.wallet;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.vibranium.contracts.events.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Evento publicado quando uma nova carteira é criada com sucesso para
 * um usuário recém registrado na plataforma.
 *
 * <p>Representa a conclusão do passo de Onboarding (Fase Zero da Saga).
 * A carteira nasce com saldo zero (BRL=0, VIBRANIUM=0).</p>
 *
 * <p>Consumidores: notificações, auditoria, MongoDB Event Store.</p>
 *
 * @param eventId       Identificador único deste evento (idempotência).
 * @param correlationId Correlação com a Saga de onboarding.
 * @param aggregateId   ID da carteira criada (walletId).
 * @param occurredOn    Timestamp de criação.
 * @param walletId      UUID da nova carteira.
 * @param userId        UUID do usuário dono da carteira (vem do Keycloak).
 */
public record WalletCreatedEvent(

        UUID eventId,
        UUID correlationId,
        String aggregateId,

        @JsonFormat(shape = JsonFormat.Shape.NUMBER_INT)
        Instant occurredOn,

        UUID walletId,
        UUID userId

) implements DomainEvent {

    /** Factory method que gera eventId e occurredOn automaticamente. */
    public static WalletCreatedEvent of(UUID correlationId, UUID walletId, UUID userId) {
        return new WalletCreatedEvent(
                UUID.randomUUID(),
                correlationId,
                walletId.toString(),
                Instant.now(),
                walletId,
                userId
        );
    }
}
