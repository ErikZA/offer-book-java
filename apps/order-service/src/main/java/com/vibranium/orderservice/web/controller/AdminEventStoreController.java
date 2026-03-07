package com.vibranium.orderservice.web.controller;

import com.vibranium.orderservice.application.dto.EventStoreEntryResponse;
import com.vibranium.orderservice.application.service.EventStoreService;
import com.vibranium.orderservice.domain.model.EventStoreEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Controller REST administrativo para consulta ao Event Store imutável.
 *
 * <p>Expõe endpoints de auditoria para compliance regulatório:</p>
 * <ul>
 *   <li>Consulta de todos os eventos de um agregado (replay completo).</li>
 *   <li>Consulta de eventos até um ponto no tempo (reconstrução temporal).</li>
 * </ul>
 *
 * <p>Protegido por role {@code ADMIN} via Spring Security — apenas tokens JWT
 * com a claim {@code realm_access.roles} contendo {@code ADMIN} são aceitos.</p>
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
     * @param aggregateId ID do agregado (ex: orderId).
     * @param until       (opcional) timestamp limite para replay temporal (ISO-8601).
     * @return Lista ordenada de eventos do agregado.
     */
    @GetMapping
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
