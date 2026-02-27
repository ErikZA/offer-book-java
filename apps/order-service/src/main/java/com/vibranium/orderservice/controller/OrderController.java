package com.vibranium.orderservice.controller;

import com.vibranium.orderservice.command.CreateOrderCommand;
import com.vibranium.orderservice.command.OrderCommandService;
import com.vibranium.orderservice.domain.Order;
import com.vibranium.orderservice.query.OrderQueryService;
import com.vibranium.utils.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;

/**
 * Controller REST para Order Service
 * Endpoints: POST /orders (criar), GET /orders/{id} (consultar), etc
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderCommandService commandService;
    private final OrderQueryService queryService;

    @Autowired
    public OrderController(OrderCommandService commandService, OrderQueryService queryService) {
        this.commandService = commandService;
        this.queryService = queryService;
    }

    /**
     * POST /api/v1/orders - Criar nova ordem
     */
    @PostMapping
    public ResponseEntity<ApiResponse<String>> createOrder(@Valid @RequestBody CreateOrderCommand command) {
        log.info("Criando nova ordem para usuário: {}", command.getUserId());
        String orderId = commandService.createOrder(command);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(orderId, "Ordem criada com sucesso"));
    }

    /**
     * GET /api/v1/orders/{id} - Obter detalhes de uma ordem
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<Order>> getOrder(@PathVariable String id) {
        log.info("Recuperando ordem: {}", id);
        return queryService.getOrderById(id)
                .map(order -> ResponseEntity.ok(ApiResponse.success(order, "Ordem encontrada")))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /api/v1/orders/user/{userId} - Obter todas as ordens de um usuário
     */
    @GetMapping("/user/{userId}")
    public ResponseEntity<ApiResponse<List<Order>>> getUserOrders(@PathVariable String userId) {
        log.info("Recuperando ordens do usuário: {}", userId);
        List<Order> orders = queryService.getUserOrders(userId);
        return ResponseEntity.ok(ApiResponse.success(orders, "Ordens recuperadas"));
    }

    /**
     * GET /api/v1/orders/user/{userId}/paginated - Com paginação
     */
    @GetMapping("/user/{userId}/paginated")
    public ResponseEntity<ApiResponse<Page<Order>>> getUserOrdersPaginated(
            @PathVariable String userId,
            Pageable pageable) {
        log.info("Recuperando ordens (paginado) do usuário: {}", userId);
        Page<Order> orders = queryService.getUserOrdersPaginated(userId, pageable);
        return ResponseEntity.ok(ApiResponse.success(orders, "Ordens paginadas recuperadas"));
    }

    /**
     * GET /api/v1/orders/book/{symbol} - Obter order book de um símbolo
     */
    @GetMapping("/book/{symbol}")
    public ResponseEntity<ApiResponse<List<Order>>> getOrderBook(@PathVariable String symbol) {
        log.info("Recuperando order book para: {}", symbol);
        List<Order> orders = queryService.getActiveOrderBook(symbol);
        return ResponseEntity.ok(ApiResponse.success(orders, "Order book recuperado"));
    }

    /**
     * GET /api/v1/orders/book/{symbol}/{side} - Obter um lado do order book
     */
    @GetMapping("/book/{symbol}/{side}")
    public ResponseEntity<ApiResponse<List<Order>>> getOrderBookSide(
            @PathVariable String symbol,
            @PathVariable String side) {
        log.info("Recuperando {} order book para: {}", side, symbol);
        List<Order> orders = queryService.getOrderBookSide(symbol, side);
        return ResponseEntity.ok(ApiResponse.success(orders, "Order book side recuperado"));
    }

    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Order Service OK");
    }

}
