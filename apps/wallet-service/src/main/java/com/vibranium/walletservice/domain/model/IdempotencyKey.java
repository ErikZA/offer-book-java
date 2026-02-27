package com.vibranium.walletservice.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Registro de idempotência para proteção contra at-least-once delivery do RabbitMQ.
 *
 * <p>Antes de processar qualquer comando ou evento, o listener verifica se o
 * {@code messageId} já existe nesta tabela. Se existir, a mensagem é descartada
 * com ACK (processamento idempotente). Se não existir, a chave é gravada dentro
 * da MESMA transação da operação principal.</p>
 *
 * <p>A chave primária é o {@code messageId} — string que vem do header AMQP
 * {@code message-id}. A unicidade é garantida pela PK do PostgreSQL.</p>
 */
@Entity
@Table(name = "idempotency_key")
public class IdempotencyKey {

    /**
     * O message-id vindo do header AMQP. Atua como PK para garantir unicidade.
     * Normalmente é um UUID, mas pode ser qualquer string até 36 chars.
     */
    @Id
    @Column(name = "message_id", length = 36, nullable = false)
    private String messageId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    /** Construtor protegido exigido pelo JPA. Não usar diretamente. */
    protected IdempotencyKey() {}

    /**
     * Cria um registro de idempotência para o {@code messageId} informado.
     *
     * @param messageId Identificador único da mensagem (header AMQP message-id).
     */
    public IdempotencyKey(String messageId) {
        this.messageId = messageId;
        this.processedAt = Instant.now();
    }

    public String getMessageId() { return messageId; }

    public Instant getProcessedAt() { return processedAt; }
}
