package com.vibranium.contracts.events;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * Classe base para todos os eventos do domínio.
 * Garante consistência e rastreabilidade dos eventos.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public abstract class BaseEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * ID único do evento (idempotência)
     */
    @JsonProperty("event_id")
    private String eventId = UUID.randomUUID().toString();

    /**
     * Timestamp de criação do evento
     */
    @JsonProperty("timestamp")
    private Instant timestamp = Instant.now();

    /**
     * Versão do contrato (versionamento de eventos)
     */
    @JsonProperty("version")
    private Integer version = 1;

    /**
     * Tipo de evento (usar nome da classe)
     */
    @JsonProperty("event_type")
    public String getEventType() {
        return this.getClass().getSimpleName();
    }

    /**
     * Correlação com operação ou usuário
     */
    @JsonProperty("correlation_id")
    private String correlationId;

}
