package com.vibranium.contracts.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Contrato base para todos os Eventos de Domínio da plataforma Vibranium.
 *
 * <p>Um DomainEvent representa um <strong>fato já ocorrido</strong> e imutável
 * no sistema. Toda implementação deve ser um {@code record} Java para garantir
 * imutabilidade em trânsito pelo RabbitMQ.</p>
 *
 * <p>Os quatro metadados obrigatórios garantem:</p>
 * <ul>
 *   <li>{@code eventId}       — idempotência no consumidor (deduplicação)</li>
 *   <li>{@code correlationId} — rastreabilidade distribuída (tracing Saga)</li>
 *   <li>{@code aggregateId}   — identificação do agregado afetado</li>
 *   <li>{@code occurredOn}    — ordering e auditoria temporal</li>
 * </ul>
 *
 * <p><strong>Configuração do ObjectMapper (obrigatória nos microsserviços):</strong></p>
 * <pre>{@code
 * objectMapper.registerModule(new JavaTimeModule())
 *             .enable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
 *             .disable(SerializationFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS)
 *             .disable(DeserializationFeature.READ_DATE_TIMESTAMPS_AS_NANOSECONDS);
 * }</pre>
 * <p>Isso garante que {@code Instant} seja serializado como epoch-millis (long),
 * compatível entre todos os serviços consumidores.</p>
 */
public interface DomainEvent {

    /**
     * Identificador único deste evento. Usado para idempotência:
     * se o RabbitMQ entregar duas vezes, o consumidor descarta a duplicata.
     */
    UUID eventId();

    /**
     * ID de correlação que atravessa toda a Saga, do comando original
     * até o último evento de compensação. Mapeado ao Trace-ID do OpenTelemetry.
     */
    UUID correlationId();

    /**
     * ID do agregado que originou o evento (ex: orderId, walletId).
     */
    String aggregateId();

    /**
     * Timestamp de quando o fato ocorreu, em UTC.
     * Serializado como epoch-millis via configuração do ObjectMapper.
     */
    Instant occurredOn();

    /**
     * Nome do tipo de evento para roteamento no RabbitMQ e Event Store.
     * Implementação padrão retorna o nome simples da classe.
     */
    default String eventType() {
        return this.getClass().getSimpleName();
    }
}
