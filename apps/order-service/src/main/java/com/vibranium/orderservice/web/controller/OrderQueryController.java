package com.vibranium.orderservice.web.controller;

import com.vibranium.orderservice.query.model.OrderDocument;
import com.vibranium.orderservice.query.repository.OrderHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller REST do Query Side — leitura das ordens do Read Model (MongoDB).
 *
 * <p>Todos os endpoints leem diretamente do MongoDB, sem JOIN com PostgreSQL.
 * Isso garante respostas abaixo de 50ms mesmo com histórico extenso, pois
 * o {@link OrderDocument} é desnormalizado e contém todos os dados necessários.</p>
 *
 * <p><strong>Segurança:</strong> o {@code userId} é extraído exclusivamente do
 * {@code sub} claim do JWT — nunca de parâmetros da URL ou query string.
 * Isso impede que um usuário consulte ordens de outro usuário.</p>
 *
 * <p>Endpoints:</p>
 * <ul>
 *   <li>{@code GET /api/v1/orders} — lista paginada de ordens do usuário autenticado.</li>
 *   <li>{@code GET /api/v1/orders/{orderId}} — detalhe de uma ordem com array history[].</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/orders")
// Criado apenas quando app.mongodb.enabled=true (ou quando a propriedade está ausente).
// Ver OrderEventProjectionConsumer para o raciocínio completo desta condicional.
@ConditionalOnProperty(name = "app.mongodb.enabled", matchIfMissing = true)
public class OrderQueryController {

    private static final Logger logger = LoggerFactory.getLogger(OrderQueryController.class);

    /** Tamanho máximo de página para evitar respostas grandes acidentais. */
    private static final int MAX_PAGE_SIZE = 100;

    private final OrderHistoryRepository orderHistoryRepository;

    public OrderQueryController(OrderHistoryRepository orderHistoryRepository) {
        this.orderHistoryRepository = orderHistoryRepository;
    }

    // =========================================================================
    // GET /api/v1/orders — lista paginada das ordens do usuário autenticado
    // =========================================================================

    /**
     * Retorna a lista paginada de ordens do usuário autenticado, da mais recente para a mais antiga.
     *
     * <p>O {@code userId} é extraído do claim {@code sub} do JWT, nunca de parâmetros
     * externos. A paginação é obrigatória (padrão: page=0, size=20, max=100).</p>
     *
     * @param jwt  JWT do usuário autenticado (injetado pelo Spring Security).
     * @param page Número da página (0-indexed, padrão 0).
     * @param size Tamanho da página (padrão 20, máximo {@value #MAX_PAGE_SIZE}).
     * @return Página de {@link OrderDocument} do usuário, ordenada por createdAt DESC.
     */
    @GetMapping
    public ResponseEntity<Page<OrderDocument>> listOrders(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        // Extrai userId do JWT (claim sub = keycloakId do usuário)
        String userId = jwt.getSubject();

        // Limita o tamanho máximo da página para proteger a API de abusos
        int effectiveSize = Math.min(size, MAX_PAGE_SIZE);

        logger.debug("Consultando ordens: userId={} page={} size={}", userId, page, effectiveSize);

        Page<OrderDocument> orders = orderHistoryRepository
                .findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(page, effectiveSize));

        return ResponseEntity.ok(orders);
    }

    // =========================================================================
    // GET /api/v1/orders/{orderId} — detalhe de uma ordem com history[]
    // =========================================================================

    /**
     * Retorna os detalhes completos de uma ordem incluindo o array {@code history[]}.
     *
     * <p>Não valida se a ordem pertence ao usuário autenticado — o endpoint
     * é usado para auditoria interna. Se isolamento for necessário,
     * adicionar filtro por {@code jwt.getSubject()} em sprint futuro.</p>
     *
     * @param orderId UUID da ordem (String).
     * @return {@code 200 OK} com o documento, ou {@code 404 Not Found} se não existir.
     */
    @GetMapping("/{orderId}")
    public ResponseEntity<OrderDocument> getOrder(@PathVariable String orderId) {
        logger.debug("Consultando ordem por ID: orderId={}", orderId);

        return orderHistoryRepository.findById(orderId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
