package com.vibranium.orderservice.web.controller;

import com.vibranium.orderservice.application.service.OrderCommandService;
import com.vibranium.orderservice.application.dto.PlaceOrderRequest;
import com.vibranium.orderservice.application.dto.PlaceOrderResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller REST do Command Side do order-service.
 *
 * <p>Expõe o endpoint de aceitação de ordens. Retorna {@code 202 Accepted}
 * pois o processamento é assíncrono: a resposta confirma apenas que a ordem
 * foi registrada e o comando de reserva foi publicado no RabbitMQ.</p>
 *
 * <p>O controller é intencionalmente thin: apenas extrai o userId do JWT
 * e delega tudo para o {@link OrderCommandService}.</p>
 *
 * <p>Segurança: {@code @AuthenticationPrincipal Jwt} injeta automaticamente
 * o token validado pelo filtro de Resource Server do Spring Security.
 * O {@code sub} claim é o keycloakId do usuário.</p>
 */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderCommandController {

    private final OrderCommandService orderCommandService;

    public OrderCommandController(OrderCommandService orderCommandService) {
        this.orderCommandService = orderCommandService;
    }

    /**
     * Aceita uma nova ordem de compra ou venda de VIBRANIUM.
     *
     * <p>Fluxo interno:</p>
     * <ol>
     *   <li>Bean Validation valida o body ({@code @Valid}).</li>
     *   <li>{@code userId} é extraído do claim {@code sub} do JWT.</li>
     *   <li>Serviço verifica se userId está no registro local.</li>
     *   <li>Ordem é persistida em {@code PENDING} e comando publicado.</li>
     * </ol>
     *
     * @param request Dados da ordem (walletId, orderType, price, amount).
     * @param jwt     Token JWT injetado pelo Spring Security Resource Server.
     * @return Confirmação assíncrona com orderId, correlationId e status PENDING.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PlaceOrderResponse placeOrder(
            @RequestBody @Valid PlaceOrderRequest request,
            @AuthenticationPrincipal Jwt jwt) {

        // Extrai o keycloakId do claim 'sub' do JWT — nunca do body para evitar spoofing
        String keycloakId = jwt.getSubject();

        return orderCommandService.placeOrder(keycloakId, request);
    }
}
