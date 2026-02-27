package com.vibranium.orderservice.web.dto;

import java.util.UUID;

/**
 * DTO de saída do endpoint {@code POST /api/v1/orders}.
 *
 * <p>Retornado com HTTP 202 Accepted: a ordem foi registrada no estado
 * {@code PENDING} e o comando de reserva de fundos foi publicado no RabbitMQ.
 * O cliente deve usar o {@code correlationId} para rastrear o progresso
 * da Saga via WebSocket ou polling.</p>
 *
 * @param orderId       UUID da ordem criada.
 * @param correlationId UUID de correlação da Saga (para tracing distribuído).
 * @param status        Estado inicial: sempre {@code "PENDING"}.
 */
public record PlaceOrderResponse(
        UUID orderId,
        UUID correlationId,
        String status
) {}
