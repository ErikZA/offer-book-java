package com.vibranium.walletservice.web.controller;

import com.vibranium.walletservice.application.dto.EventStoreEntryResponse;
import com.vibranium.walletservice.application.service.EventStoreService;
import com.vibranium.walletservice.domain.model.EventStoreEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Controller REST administrativo para consulta ao Event Store imutável do wallet-service.
 *
 * <p>Expõe endpoints de auditoria para compliance regulatório financeiro:</p>
 * <ul>
 *   <li>Consulta de todos os eventos de um agregado (replay completo).</li>
 *   <li>Consulta de eventos até um ponto no tempo (reconstrução temporal).</li>
 * </ul>
 *
 * <p>Protegido por role {@code ADMIN} via {@code @PreAuthorize} — apenas tokens JWT
 * com a role {@code ADMIN} são aceitos (AT-4.2.1).</p>
 */
@RestController
@RequestMapping("/admin/events")
public class AdminEventStoreController {

    private static final Logger logger = LoggerFactory.getLogger(AdminEventStoreController.class);

    private final EventStoreService eventStoreService;

    public AdminEventStoreController(EventStoreService eventStoreService) {
        this.eventStoreService = eventStoreService;
    }

    /**
     * Retorna todos os eventos de um agregado, ordenados pela sequência de inserção.
     *
     * <p>Se o parâmetro {@code until} for informado, retorna apenas eventos
     * até o instante especificado (inclusive), permitindo reconstrução temporal.</p>
     *
     * @param aggregateId ID do agregado (ex: walletId).
     * @param until       (opcional) timestamp limite para replay temporal (ISO-8601).
     * @return Lista ordenada de eventos do agregado.
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<EventStoreEntryResponse>> getEvents(
            @RequestParam String aggregateId,
            @RequestParam(required = false) Instant until) {

        logger.info("Audit query: aggregateId={} until={}", aggregateId, until);

        List<EventStoreEntry> entries;
        if (until != null) {
            entries = eventStoreService.getEventsUntil(aggregateId, until);
        } else {
            entries = eventStoreService.getEventsByAggregateId(aggregateId);
        }

        List<EventStoreEntryResponse> response = entries.stream()
                .map(e -> new EventStoreEntryResponse(
                        e.getSequenceId(),
                        e.getEventId(),
                        e.getAggregateId(),
                        e.getAggregateType(),
                        e.getEventType(),
                        e.getPayload(),
                        e.getOccurredOn(),
                        e.getCorrelationId(),
                        e.getSchemaVersion()
                ))
                .toList();

        return ResponseEntity.ok(response);
    }
}
