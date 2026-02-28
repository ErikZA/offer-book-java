package com.vibranium.orderservice.application.service;

import com.vibranium.contracts.commands.wallet.ReserveFundsCommand;
import com.vibranium.contracts.enums.AssetType;
import com.vibranium.contracts.enums.OrderType;
import com.vibranium.contracts.events.order.OrderReceivedEvent;
import com.vibranium.orderservice.config.RabbitMQConfig;
import com.vibranium.orderservice.domain.model.Order;
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
 * Serviço de aplicação do Command Side do order-service.
 *
 * <p>Implementa o caso de uso "Colocar Ordem":
 * <ol>
 *   <li>Valida que o usuário (JWT {@code sub}) existe no registro local.</li>
 *   <li>Persiste a ordem no estado {@code PENDING} no PostgreSQL.</li>
 *   <li>Publica {@link ReserveFundsCommand} para o wallet-service via RabbitMQ.</li>
 * </ol>
 *
 * <p>Padrão Outbox NOT usado aqui intencionalmente: a publicação ocorre na
 * mesma transação que o save. Para resiliência eventual, o RabbitMQ está
 * configurado com retry (max 3 tentativas com backoff) e dead-letter queue.
 * O Outbox Pattern pode ser adicionado em sprint futuro se necessário.</p>
 *
 * <p>O serviço é stateless e testável: não possui estado mutable.</p>
 */
@Service
public class OrderCommandService {

    private static final Logger logger = LoggerFactory.getLogger(OrderCommandService.class);

    private final UserRegistryRepository userRegistryRepository;
    private final OrderRepository orderRepository;
    private final RabbitTemplate rabbitTemplate;

    public OrderCommandService(UserRegistryRepository userRegistryRepository,
                               OrderRepository orderRepository,
                               RabbitTemplate rabbitTemplate) {
        this.userRegistryRepository = userRegistryRepository;
        this.orderRepository        = orderRepository;
        this.rabbitTemplate         = rabbitTemplate;
    }

    /**
     * Aceita e enfileira uma ordem para processamento pela Saga.
     *
     * @param keycloakId  Identificador Keycloak do usuário (claim {@code sub} do JWT).
     * @param request     Dados da ordem validados pelo Bean Validation.
     * @return Confirmação assincrona com orderId, correlationId e status PENDING.
     * @throws UserNotRegisteredException se o keycloakId não estiver no registro local.
     */
    @Transactional
    public PlaceOrderResponse placeOrder(String keycloakId, PlaceOrderRequest request) {
        // 1. Validação de permissão: userId deve estar no registry local
        if (!userRegistryRepository.existsByKeycloakId(keycloakId)) {
            throw new UserNotRegisteredException(keycloakId);
        }

        // 2. Cria a ordem no estado PENDING — persiste ANTES de publicar o comando
        //    (garante que o evento FundsReservedEvent terá um registro para atualizar)
        UUID orderId       = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();

        Order order = Order.create(
                orderId, correlationId, keycloakId,
                request.walletId(), request.orderType(),
                request.price(), request.amount()
        );
        orderRepository.save(order);

        // 3. Calcula o valor a ser bloqueado na carteira:
        //    BUY  → bloqueia BRL = price * amount (valor total da compra)
        //    SELL → bloqueia VIBRANIUM = amount   (quantidade a vender)
        AssetType assetToLock;
        BigDecimal amountToLock;
        if (request.orderType() == OrderType.BUY) {
            assetToLock  = AssetType.BRL;
            amountToLock = request.price().multiply(request.amount());
        } else {
            assetToLock  = AssetType.VIBRANIUM;
            amountToLock = request.amount();
        }

        // 4. Publica o comando de reserva de fundos para o wallet-service
        ReserveFundsCommand cmd = new ReserveFundsCommand(
                correlationId, orderId, request.walletId(), assetToLock, amountToLock
        );

        rabbitTemplate.convertAndSend(
                RabbitMQConfig.COMMANDS_EXCHANGE,
                RabbitMQConfig.QUEUE_RESERVE_FUNDS,  // routing key = queue name (direct exchange)
                cmd
        );

        // 5. Publica OrderReceivedEvent para o Read Model (Query Side — US-003).
        //    Executado em Virtual Thread dedicada para NÃO bloquear a transação JPA nem
        //    adicionar latência ao path crítico do placeOrder (SLA ≤ 200ms p99).
        //    Falha tolerada: a projeção é eventual — se a publicação falhar, o Read Model
        //    ficará desatualizado até o próximo evento (FundsReserved ou MatchExecuted)
        //    que disparar a criação do documento via retry da fila.
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
                        // Falha não-crítica: loga e continua. O Command Side já persistiu a ordem.
                        // A projeção MongoDB será atualizada no próximo evento da Saga.
                        logger.error("Falha ao publicar OrderReceivedEvent para projeção: orderId={} error={}",
                                orderId, ex.getMessage());
                    }
                });

        logger.info("Ordem aceita: orderId={} correlationId={} userId={} type={} price={} amount={}",
                orderId, correlationId, keycloakId,
                request.orderType(), request.price(), request.amount());

        return new PlaceOrderResponse(orderId, correlationId, "PENDING");
    }
}
