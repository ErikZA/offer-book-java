package com.vibranium.orderservice.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibranium.contracts.commands.wallet.ReserveFundsCommand;
import com.vibranium.contracts.enums.AssetType;
import com.vibranium.contracts.enums.OrderType;
import com.vibranium.contracts.events.order.OrderReceivedEvent;
import com.vibranium.orderservice.config.RabbitMQConfig;
import com.vibranium.orderservice.domain.model.Order;
import com.vibranium.orderservice.domain.model.OrderOutboxMessage;
import com.vibranium.orderservice.domain.repository.OrderOutboxRepository;
import com.vibranium.orderservice.domain.repository.OrderRepository;
import com.vibranium.orderservice.domain.repository.UserRegistryRepository;
import com.vibranium.orderservice.web.dto.PlaceOrderRequest;
import com.vibranium.orderservice.web.dto.PlaceOrderResponse;
import com.vibranium.orderservice.web.exception.UserNotRegisteredException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Servico de aplicacao do Command Side do order-service.
 *
 * <p>Implementa o caso de uso "Colocar Ordem" com Outbox Pattern para garantia de entrega:</p>
 * <ol>
 *   <li>Valida que o usuario (JWT {@code sub}) existe no registro local.</li>
 *   <li>Persiste a ordem no estado {@code PENDING} no PostgreSQL.</li>
 *   <li>Grava o {@link ReserveFundsCommand} em {@code tb_order_outbox} dentro da
 *       <strong>mesma transacao</strong> da Ordem — garantindo atomicidade.</li>
 *   <li>O {@link OrderOutboxPublisherService} (scheduler) faz o relay para o RabbitMQ.</li>
 * </ol>
 *
 * <p><strong>Motivacao do Outbox Pattern aqui:</strong> a publicacao direta do
 * {@code ReserveFundsCommand} via {@link RabbitTemplate} dentro de {@code @Transactional}
 * pode perder o comando se o broker estiver indisponivel no momento do commit.
 * Gravando na tabela de outbox, o comando sobrevive a falhas transientes do broker
 * e e reenviado pelo scheduler na proxima janela.</p>
 *
 * <p>O servico e stateless e testavel: nao possui estado mutavel.</p>
 */
@Service
public class OrderCommandService {

    private static final Logger logger = LoggerFactory.getLogger(OrderCommandService.class);

    private final UserRegistryRepository userRegistryRepository;
    private final OrderRepository        orderRepository;
    private final OrderOutboxRepository  outboxRepository;
    private final ObjectMapper           objectMapper;
    private final RabbitTemplate         rabbitTemplate;

    public OrderCommandService(UserRegistryRepository userRegistryRepository,
                               OrderRepository orderRepository,
                               OrderOutboxRepository outboxRepository,
                               ObjectMapper objectMapper,
                               RabbitTemplate rabbitTemplate) {
        this.userRegistryRepository = userRegistryRepository;
        this.orderRepository        = orderRepository;
        this.outboxRepository       = outboxRepository;
        this.objectMapper           = objectMapper;
        this.rabbitTemplate         = rabbitTemplate;
    }

    /**
     * Aceita e enfileira uma ordem para processamento pela Saga.
     *
     * <p>O {@link ReserveFundsCommand} NAO e publicado diretamente no broker.
     * Em vez disso, e gravado em {@code tb_order_outbox} na mesma transacao
     * da Ordem. O {@link OrderOutboxPublisherService} faz o relay assincronamente.</p>
     *
     * @param keycloakId  Identificador Keycloak do usuario (claim {@code sub} do JWT).
     * @param request     Dados da ordem validados pelo Bean Validation.
     * @return Confirmacao assincrona com orderId, correlationId e status PENDING.
     * @throws UserNotRegisteredException se o keycloakId nao estiver no registro local.
     */
    @Transactional
    public PlaceOrderResponse placeOrder(String keycloakId, PlaceOrderRequest request) {
        // 1. Validacao de permissao: userId deve estar no registry local
        if (!userRegistryRepository.existsByKeycloakId(keycloakId)) {
            throw new UserNotRegisteredException(keycloakId);
        }

        // 2. Cria a ordem no estado PENDING
        UUID orderId       = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();

        Order order = Order.create(
                orderId, correlationId, keycloakId,
                request.walletId(), request.orderType(),
                request.price(), request.amount()
        );
        orderRepository.save(order);

        // 3. Calcula o ativo e valor a bloquear na carteira
        //    BUY  -> bloqueia BRL = price * amount
        //    SELL -> bloqueia VIBRANIUM = amount
        AssetType assetToLock;
        BigDecimal amountToLock;
        if (request.orderType() == OrderType.BUY) {
            assetToLock  = AssetType.BRL;
            amountToLock = request.price().multiply(request.amount());
        } else {
            assetToLock  = AssetType.VIBRANIUM;
            amountToLock = request.amount();
        }

        // 4. Outbox Pattern: grava o ReserveFundsCommand na mesma transacao da Ordem.
        //    O scheduler (OrderOutboxPublisherService) faz o relay para o RabbitMQ.
        //    Isso garante que o comando nao seja perdido mesmo que o broker esteja
        //    indisponivel no momento do commit desta transacao.
        ReserveFundsCommand cmd = new ReserveFundsCommand(
                correlationId, orderId, request.walletId(), assetToLock, amountToLock
        );

        try {
            String payloadJson = objectMapper.writeValueAsString(cmd);
            outboxRepository.save(new OrderOutboxMessage(
                    orderId,
                    "Order",
                    "ReserveFundsCommand",
                    RabbitMQConfig.COMMANDS_EXCHANGE,
                    RabbitMQConfig.QUEUE_RESERVE_FUNDS,
                    payloadJson
            ));
        } catch (JsonProcessingException ex) {
            // Falha de serializacao e um erro de programacao, nao de infraestrutura
            throw new IllegalStateException("Falha ao serializar ReserveFundsCommand: " + ex.getMessage(), ex);
        }

        // 5. Publica OrderReceivedEvent para o Read Model (Query Side).
        //    Executado em Virtual Thread dedicada para NAO bloquear a transacao JPA.
        //    Falha tolerada: a projecao e eventual.
        final OrderReceivedEvent receivedEvent = OrderReceivedEvent.of(
                correlationId, orderId, UUID.fromString(keycloakId),
                request.walletId(), request.orderType(), request.price(), request.amount()
        );
        Thread.ofVirtual()
                .name("projection-recv-" + orderId)
                .start(() -> {
                    try {
                        rabbitTemplate.convertAndSend(
                                RabbitMQConfig.EVENTS_EXCHANGE,
                                RabbitMQConfig.RK_ORDER_RECEIVED,
                                receivedEvent
                        );
                    } catch (Exception ex) {
                        logger.error("Falha ao publicar OrderReceivedEvent para projecao: orderId={} error={}",
                                orderId, ex.getMessage());
                    }
                });

        logger.info("Ordem aceita (outbox): orderId={} correlationId={} userId={} type={} price={} amount={}",
                orderId, correlationId, keycloakId,
                request.orderType(), request.price(), request.amount());

        return new PlaceOrderResponse(orderId, correlationId, "PENDING");
    }
}
