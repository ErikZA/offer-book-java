package com.vibranium.contracts.events;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * @deprecated Substituída pela interface {@link DomainEvent} + {@code record}s imutáveis.
 * Esta classe abstrata foi mantida apenas para compatibilidade durante a transição.
 * Utilize os eventos em {@code com.vibranium.contracts.events.wallet} e
 * {@code com.vibranium.contracts.events.order} que implementam {@link DomainEvent}.
 */
@Deprecated(since = "1.0.0", forRemoval = true)
public abstract class BaseEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    private String eventId = UUID.randomUUID().toString();
    private Instant timestamp = Instant.now();
    private Integer version = 1;
    private String correlationId;

    public String getEventType() {
        return this.getClass().getSimpleName();
    }

    public String getEventId()        { return eventId; }
    public Instant getTimestamp()     { return timestamp; }
    public Integer getVersion()       { return version; }
    public String getCorrelationId()  { return correlationId; }
}

