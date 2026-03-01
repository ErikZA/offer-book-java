package com.vibranium.orderservice.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Mensagem de Outbox representando um comando a ser enviado ao RabbitMQ.
 *
 * <p>Implementa o padrão <strong>Transactional Outbox</strong>:
 * em vez de publicar {@link com.vibranium.contracts.commands.wallet.ReserveFundsCommand}
 * diretamente no broker dentro de {@code @Transactional}, o {@code OrderCommandService}
 * grava esta entidade na <strong>mesma transação</strong> que persiste a {@link Order}.</p>
 *
 * <p>O {@link com.vibranium.orderservice.application.service.OrderOutboxPublisherService}
 * (scheduler) faz o relay assíncrono: lê mensagens com {@code publishedAt IS NULL},
 * publica no RabbitMQ e atualiza {@code publishedAt}.</p>
 *
 * <p>Inspirado no schema canônico do Debezium Outbox Event Router:
 * {@code aggregatetype / aggregateid / type / payload}.</p>
 */
@Entity
@Table(name = "tb_order_outbox")
public class OrderOutboxMessage {

    /** ID único da mensagem. Gerado pelo {@code OrderCommandService} (não pelo banco). */
    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /**
     * Tipo do agregado raiz que gerou o comando.
     * Sempre {@code "Order"} neste contexto — alinha com o schema Debezium.
     */
    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    /** ID do agregado afetado ({@code orderId}). */
    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    /**
     * Tipo do comando/evento serializado (ex: {@code "ReserveFundsCommand"}).
     * Usado pelo publisher para log/observabilidade; o roteamento real usa
     * {@code exchange} + {@code routingKey}.
     */
    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    /** Exchange RabbitMQ de destino. */
    @Column(name = "exchange", nullable = false, length = 100)
    private String exchange;

    /** Routing key de destino. */
    @Column(name = "routing_key", nullable = false, length = 100)
    private String routingKey;

    /**
     * Payload do comando serializado como JSON.
     * Mapeado para coluna {@code JSONB} no PostgreSQL para permitir queries ad-hoc de auditoria.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    /** Timestamp de criação — gravado junto com a Ordem na mesma transação JPA. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Timestamp de publicação bem-sucedida no broker.
     * {@code null} indica que a mensagem ainda aguarda relay pelo
     * {@link com.vibranium.orderservice.application.service.OrderOutboxPublisherService}.
     */
    @Column(name = "published_at")
    private Instant publishedAt;

    /** Construtor protegido exigido pelo JPA. Não usar diretamente. */
    protected OrderOutboxMessage() {}

    /**
     * Cria uma mensagem de outbox pronta para relay.
     *
     * @param aggregateId  orderId do agregado que originou o comando.
     * @param aggregateType tipo do agregado (ex: {@code "Order"}).
     * @param eventType    nome do tipo do comando (ex: {@code "ReserveFundsCommand"}).
     * @param exchange     exchange RabbitMQ de destino.
     * @param routingKey   routing key de destino.
     * @param payload      JSON serializado do comando.
     */
    public OrderOutboxMessage(UUID aggregateId, String aggregateType, String eventType,
                              String exchange, String routingKey, String payload) {
        this.id            = UUID.randomUUID();
        this.aggregateId   = aggregateId;
        this.aggregateType = aggregateType;
        this.eventType     = eventType;
        this.exchange      = exchange;
        this.routingKey    = routingKey;
        this.payload       = payload;
        this.createdAt     = Instant.now();
        this.publishedAt   = null;
    }

    /** Marca a mensagem como publicada com sucesso no broker. */
    public void markAsPublished() {
        this.publishedAt = Instant.now();
    }

    public UUID getId()            { return id; }
    public UUID getAggregateId()   { return aggregateId; }
    public String getAggregateType() { return aggregateType; }
    public String getEventType()   { return eventType; }
    public String getExchange()    { return exchange; }
    public String getRoutingKey()  { return routingKey; }
    public String getPayload()     { return payload; }
    public Instant getCreatedAt()  { return createdAt; }
    public Instant getPublishedAt() { return publishedAt; }
}
