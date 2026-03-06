package com.vibranium.orderservice.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibranium.contracts.enums.OrderType;
import com.vibranium.orderservice.domain.model.Order;
import com.vibranium.orderservice.domain.model.OrderOutboxMessage;
import com.vibranium.orderservice.domain.repository.OrderOutboxRepository;
import com.vibranium.orderservice.domain.repository.OrderRepository;
import com.vibranium.orderservice.domain.repository.UserRegistryRepository;
import com.vibranium.orderservice.application.dto.PlaceOrderRequest;
import com.vibranium.orderservice.application.dto.PlaceOrderResponse;
import com.vibranium.orderservice.web.exception.UserNotRegisteredException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * [FASE RED → GREEN] — Testes unitários para {@link OrderCommandService}.
 *
 * <p>Valida os três critérios fundamentais do Outbox Pattern refatorado:</p>
 * <ol>
 *   <li>Uma ordem e <strong>duas</strong> entradas de outbox são persistidas na mesma chamada.</li>
 *   <li>Falha de serialização (Jackson) propaga {@link IllegalStateException} — garantindo
 *       que nenhuma mensagem isolada seja gravada sem a correspondente.</li>
 *   <li>{@code RabbitTemplate} <strong>não</strong> é dependência do {@code OrderCommandService}
 *       — toda publicação ocorre via scheduler {@link OrderOutboxPublisherService}.</li>
 * </ol>
 *
 * <p><strong>Estratégia TDD:</strong><br>
 * Os testes são escritos contra o estado <em>desejado</em> do serviço (sem {@code RabbitTemplate}
 * no construtor). Em FASE RED o build falha pois o construtor atual exige {@code RabbitTemplate}.
 * Em FASE GREEN o serviço é refatorado e os testes passam.</p>
 *
 * <p>Dois Eventos no Outbox — motivação (Transactional Outbox Schema):<br>
 * O schema canônico ({@code aggregatetype / aggregateid / type / payload})
 * permite que <em>cada evento/comando</em> gere uma entrada independente no outbox,
 * roteada pelo {@code exchange} + {@code routingKey} armazenados na própria linha.
 * Isso elimina qualquer acoplamento com o broker no momento do {@code @Transactional}.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrderCommandService — Outbox Pattern (AT-01.1)")
class OrderCommandServiceTest {

    // =========================================================================
    // Mocks — injetados pelo MockitoExtension via @InjectMocks
    // NOTA: RabbitTemplate deliberadamente AUSENTE para detectar dependência ilegal
    // =========================================================================

    @Mock
    private UserRegistryRepository userRegistryRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderOutboxRepository outboxRepository;

    @Mock
    private ObjectMapper objectMapper;

    // =========================================================================
    // Subject Under Test — construído sem RabbitTemplate (target state pós-refactor)
    // =========================================================================

    /**
     * Construído manualmente para afirmar a ausência de {@code RabbitTemplate}.
     *
     * <p>[FASE RED] Este teste NÃO COMPILA enquanto o construtor corrente ainda
     * exigir {@code RabbitTemplate} como quinto parâmetro.</p>
     */
    private OrderCommandService service;

    @Captor
    private ArgumentCaptor<OrderOutboxMessage> outboxCaptor;

    @Captor
    private ArgumentCaptor<Order> orderCaptor;

    // =========================================================================
    // Fixtures
    // =========================================================================

    private static final String KEYCLOAK_ID = UUID.randomUUID().toString();
    private static final UUID   WALLET_ID   = UUID.randomUUID();

    private PlaceOrderRequest buyRequest;
    private PlaceOrderRequest sellRequest;

    @BeforeEach
    void setUp() {
        /*
         * [FASE RED] Construtor SEM RabbitTemplate — falha de compilação até
         * que a assinatura do construtor seja corrigida na FASE GREEN.
         */
        service = new OrderCommandService(
                userRegistryRepository,
                orderRepository,
                outboxRepository,
                objectMapper,
                new SimpleMeterRegistry()
                // RabbitTemplate foi removido intencionalmente
        );

        buyRequest  = new PlaceOrderRequest(WALLET_ID, OrderType.BUY,
                new BigDecimal("150.00"), new BigDecimal("2.00"));
        sellRequest = new PlaceOrderRequest(WALLET_ID, OrderType.SELL,
                new BigDecimal("200.00"), new BigDecimal("1.50"));
    }

    // =========================================================================
    // Teste 1 — Happy path: Order + 2 Outbox entries na mesma chamada transacional
    // =========================================================================

    @Test
    @DisplayName("placeOrder() deve persistir Order e exatamente duas mensagens no outbox " +
                 "(ReserveFundsCommand + OrderReceivedEvent)")
    void placeOrder_devePersistirOrdemEDuasEntradasNoOutbox() throws Exception {
        // Arrange
        given(userRegistryRepository.existsByKeycloakId(KEYCLOAK_ID)).willReturn(true);
        given(orderRepository.save(any(Order.class))).willAnswer(inv -> inv.getArgument(0));
        given(objectMapper.writeValueAsString(any())).willReturn("{\"mocked\":true}");
        given(outboxRepository.save(any(OrderOutboxMessage.class)))
                .willAnswer(inv -> inv.getArgument(0));

        // Act
        PlaceOrderResponse response = service.placeOrder(KEYCLOAK_ID, buyRequest);

        // Assert — resposta
        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(response.orderId()).isNotNull();
        assertThat(response.correlationId()).isNotNull();

        // Assert — Order salva exatamente uma vez
        then(orderRepository).should(times(1)).save(any(Order.class));

        // Assert — Outbox salvo exatamente DUAS vezes (ReserveFundsCommand + OrderReceivedEvent)
        then(outboxRepository).should(times(2)).save(outboxCaptor.capture());

        List<OrderOutboxMessage> savedMessages = outboxCaptor.getAllValues();
        assertThat(savedMessages).hasSize(2);

        // Primeira entrada: ReserveFundsCommand
        OrderOutboxMessage reserveCmd = savedMessages.get(0);
        assertThat(reserveCmd.getEventType()).isEqualTo("ReserveFundsCommand");
        assertThat(reserveCmd.getAggregateType()).isEqualTo("Order");
        assertThat(reserveCmd.getPayload()).isEqualTo("{\"mocked\":true}");

        // Segunda entrada: OrderReceivedEvent
        OrderOutboxMessage receivedEvt = savedMessages.get(1);
        assertThat(receivedEvt.getEventType()).isEqualTo("OrderReceivedEvent");
        assertThat(receivedEvt.getAggregateType()).isEqualTo("Order");
        assertThat(receivedEvt.getExchange()).contains("events"); // vibranium.events
        assertThat(receivedEvt.getPayload()).isEqualTo("{\"mocked\":true}");

        // Assert — ambas as mensagens pertencem ao mesmo aggregateId (orderId)
        assertThat(reserveCmd.getAggregateId()).isEqualTo(receivedEvt.getAggregateId());
    }

    @Test
    @DisplayName("placeOrder() com SELL deve calcular lock de VIBRANIUM e persistir duas entradas")
    void placeOrder_sell_devePersistirDuasEntradasNoOutbox() throws Exception {
        // Arrange
        given(userRegistryRepository.existsByKeycloakId(KEYCLOAK_ID)).willReturn(true);
        given(orderRepository.save(any(Order.class))).willAnswer(inv -> inv.getArgument(0));
        given(objectMapper.writeValueAsString(any())).willReturn("{}");
        given(outboxRepository.save(any(OrderOutboxMessage.class)))
                .willAnswer(inv -> inv.getArgument(0));

        // Act
        PlaceOrderResponse response = service.placeOrder(KEYCLOAK_ID, sellRequest);

        // Assert — dois saves no outbox mesmo para SELL
        assertThat(response.status()).isEqualTo("PENDING");
        then(outboxRepository).should(times(2)).save(any(OrderOutboxMessage.class));
    }

    // =========================================================================
    // Teste 2 — Falha de serialização: IllegalStateException, sem persistência parcial
    // =========================================================================

    @Test
    @DisplayName("placeOrder() deve lançar IllegalStateException quando ObjectMapper falhar " +
                 "na serialização do ReserveFundsCommand")
    void placeOrder_falhaDeSerializacaoDoComando_deveLancarIllegalStateException() throws Exception {
        // Arrange
        given(userRegistryRepository.existsByKeycloakId(KEYCLOAK_ID)).willReturn(true);
        given(orderRepository.save(any(Order.class))).willAnswer(inv -> inv.getArgument(0));

        // Primeiro writeValueAsString (ReserveFundsCommand) lança exceção
        given(objectMapper.writeValueAsString(any()))
                .willThrow(new JsonProcessingException("serialization failed") {});

        // Act + Assert
        assertThatThrownBy(() -> service.placeOrder(KEYCLOAK_ID, buyRequest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Falha ao serializar");

        // Assert — nenhuma mensagem gravada no outbox
        then(outboxRepository).should(never()).save(any(OrderOutboxMessage.class));
    }

    @Test
    @DisplayName("placeOrder() deve lançar IllegalStateException quando ObjectMapper falhar " +
                 "na serialização do OrderReceivedEvent")
    void placeOrder_falhaDeSerializacaoDoEvento_deveLancarIllegalStateException() throws Exception {
        // Arrange
        given(userRegistryRepository.existsByKeycloakId(KEYCLOAK_ID)).willReturn(true);
        given(orderRepository.save(any(Order.class))).willAnswer(inv -> inv.getArgument(0));
        given(outboxRepository.save(any(OrderOutboxMessage.class)))
                .willAnswer(inv -> inv.getArgument(0));

        // Primeira chamada OK (ReserveFundsCommand), segunda lança exceção (OrderReceivedEvent)
        when(objectMapper.writeValueAsString(any()))
                .thenReturn("{}")
                .thenThrow(new JsonProcessingException("event serialization failed") {});

        // Act + Assert
        assertThatThrownBy(() -> service.placeOrder(KEYCLOAK_ID, buyRequest))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Falha ao serializar");

        // Assert — apenas 1 save foi realizado antes da exceção (ReserveFundsCommand)
        // O rollback transacional fica a cargo do @Transactional do Spring
        then(outboxRepository).should(times(1)).save(any(OrderOutboxMessage.class));
    }

    // =========================================================================
    // Teste 3 — Sem RabbitTemplate: a construção do serviço é possível sem ele
    // =========================================================================

    @Test
    @DisplayName("OrderCommandService deve ser instanciável SEM RabbitTemplate " +
                 "(nenhuma publicação direta no broker)")
    void orderCommandService_naoDeveDependDeRabbitTemplate() throws Exception {
        // Arrange — já validado no @BeforeEach: service foi construído sem RabbitTemplate
        given(userRegistryRepository.existsByKeycloakId(KEYCLOAK_ID)).willReturn(true);
        given(orderRepository.save(any(Order.class))).willAnswer(inv -> inv.getArgument(0));
        given(objectMapper.writeValueAsString(any())).willReturn("{}");
        given(outboxRepository.save(any(OrderOutboxMessage.class)))
                .willAnswer(inv -> inv.getArgument(0));

        // Act — se chegou aqui sem NPE ou erro de construção, o serviço não precisa do broker
        PlaceOrderResponse response = service.placeOrder(KEYCLOAK_ID, buyRequest);

        // Assert — operação completa sem RabbitTemplate
        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo("PENDING");

        /*
         * Prova formal: o serviço possui 5 dependências (sem RabbitTemplate).
         * Verificado implicitamente no @BeforeEach onde new OrderCommandService(5 args) compila.
         * Reforçado aqui via reflexão para documentar a invariante.
         */
        var constructors = service.getClass().getDeclaredConstructors();
        assertThat(constructors).hasSize(1);
        assertThat(constructors[0].getParameterCount())
                .as("OrderCommandService deve ter exatamente 5 dependências (sem RabbitTemplate)")
                .isEqualTo(5);
    }

    // =========================================================================
    // Teste 4 — Usuário não registrado: exceção sem persistência
    // =========================================================================

    @Test
    @DisplayName("placeOrder() deve lançar UserNotRegisteredException sem gravar nada " +
                 "quando keycloakId não existir no registry")
    void placeOrder_usuarioNaoRegistrado_deveLancarExcecaoSemPersistir() {
        // Arrange
        given(userRegistryRepository.existsByKeycloakId(KEYCLOAK_ID)).willReturn(false);

        // Act + Assert
        assertThatThrownBy(() -> service.placeOrder(KEYCLOAK_ID, buyRequest))
                .isInstanceOf(UserNotRegisteredException.class);

        then(orderRepository).should(never()).save(any());
        then(outboxRepository).should(never()).save(any());
    }
}
