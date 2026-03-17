package com.vibranium.orderservice.web.controller;

import com.vibranium.orderservice.application.query.service.ProjectionRebuildService;
import com.vibranium.orderservice.application.query.service.ProjectionRebuildService.RebuildResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Controller REST administrativo para reconstrução da projeção MongoDB.
 *
 * <p>Expõe o endpoint {@code POST /admin/projections/rebuild} que reconstrói o Read Model
 * (MongoDB {@link com.vibranium.orderservice.application.query.model.OrderDocument}) a partir
 * da fonte de verdade (PostgreSQL).</p>
 *
 * <p>Protegido por role {@code ADMIN} via {@link com.vibranium.orderservice.security.SecurityConfig}
 * — o matcher {@code /admin/**} exige {@code ROLE_ADMIN} no JWT.</p>
 *
 * @see ProjectionRebuildService
 */
@RestController
@RequestMapping("/admin/projections")
@ConditionalOnProperty(name = "app.mongodb.enabled", matchIfMissing = true)
public class AdminProjectionController {

    private static final Logger logger = LoggerFactory.getLogger(AdminProjectionController.class);

    private final ProjectionRebuildService projectionRebuildService;

    public AdminProjectionController(ProjectionRebuildService projectionRebuildService) {
        this.projectionRebuildService = projectionRebuildService;
    }

    /**
     * Reconstrói a projeção MongoDB a partir do PostgreSQL.
     *
     * <p>Modos disponíveis:</p>
     * <ul>
     *   <li>{@code full} (padrão): reconstrói todas as ordens.</li>
     *   <li>{@code incremental}: processa apenas ordens modificadas desde o último rebuild.</li>
     * </ul>
     *
     * @param mode Modo de rebuild: "full" ou "incremental".
     * @return JSON com contagem de ordens processadas e total.
     */
    @PostMapping("/rebuild")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> rebuild(
            @RequestParam(name = "mode", defaultValue = "full") String mode) {

        logger.info("Projection rebuild requested: mode={}", mode);

        RebuildResult result;
        if ("incremental".equals(mode)) {
            result = projectionRebuildService.rebuildIncremental();
        } else {
            result = projectionRebuildService.rebuildFull();
        }

        logger.info("Projection rebuild completed: mode={}, processed={}, total={}",
                mode, result.processed(), result.total());

        return ResponseEntity.ok(Map.of(
                "mode", mode,
                "processed", result.processed(),
                "total", result.total()
        ));
    }
}
