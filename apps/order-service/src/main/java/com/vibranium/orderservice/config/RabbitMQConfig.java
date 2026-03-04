package com.vibranium.orderservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.propagation.Propagator;
import io.micrometer.tracing.handler.PropagatingSenderTracingObservationHandler;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.RabbitAccessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.SmartInitializingSingleton;
import java.lang.reflect.Field;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declaração de toda a topologia do RabbitMQ do order-service.
 *
 * <p>Exchanges declaradas:</p>
 * <ul>
 *   <li>{@code vibranium.commands} — direct exchange para comandos entre serviços.</li>
 *   <li>{@code vibranium.events}   — topic exchange para eventos de domínio.</li>
 *   <li>{@code vibranium.dlq}      — direct exchange para dead-letter queue.</li>
 * </ul>
 *
 * <p>Todas as filas são {@code durable=true}: sobrevivem a restart do broker.</p>
 * <p>Filas principais têm DLX configurado para {@code vibranium.dlq} para
 * capturar mensagens rejeitadas após o número máximo de tentativas.</p>
 */
@Configuration
public class RabbitMQConfig {

    private static final Logger logger = LoggerFactory.getLogger(RabbitMQConfig.class);

    // -------------------------------------------------------------------------
    // Nomes das exchanges e filas (espelham application.yaml para consistência)
    // -------------------------------------------------------------------------

    /** Exchange de comandos (direct) — order-service → wallet-service. */
    public static final String COMMANDS_EXCHANGE     = "vibranium.commands";

    /** Exchange de eventos de domínio (topic) — compartilhada por todos os serviços. */
    public static final String EVENTS_EXCHANGE       = "vibranium.events";

    /** Exchange para mensagens na Dead Letter Queue. */
    public static final String DLQ_EXCHANGE          = "vibranium.dlq";

    /** Exchange built-in do RabbitMQ para eventos do Keycloak (plugin aznamier). */
    public static final String KEYCLOAK_EXCHANGE     = "amq.topic";

    // Filas consumidas pelo order-service
    public static final String QUEUE_FUNDS_RESERVED  = "order.events.funds-reserved";
    public static final String QUEUE_FUNDS_FAILED    = "order.events.funds-failed";
    public static final String QUEUE_KEYCLOAK_REG    = "order.keycloak.user-register";
    public static final String QUEUE_DEAD_LETTER     = "order.dead-letter";

    // Filas publicadas pelo order-service (declaradas para que o broker as crie)
    public static final String QUEUE_RESERVE_FUNDS   = "wallet.commands.reserve-funds";

    /**
     * Fila de {@code ReleaseFundsCommand} — compensação da Saga publicada pelo order-service
     * quando uma ordem com fundos já reservados é cancelada (timeout OPEN/PARTIAL ou
     * falha de liquidação). Consumida pelo wallet-service para desbloquear o saldo.
     */
    public static final String QUEUE_RELEASE_FUNDS   = "wallet.commands.release-funds";

    // Routing keys usadas pelos consumidores
    public static final String RK_FUNDS_RESERVED     = "wallet.events.funds-reserved";
    public static final String RK_FUNDS_FAILED       = "wallet.events.funds-reservation-failed";
    public static final String RK_KEYCLOAK_REGISTER  = "KK.EVENT.CLIENT.orderbook-realm.REGISTER";

    /**
     * Routing key do {@code FundsSettlementFailedEvent} publicado pelo wallet-service
     * quando a liquidação de um trade falha após um match bem-sucedido.
     * O order-service consome este evento para emitir {@code ReleaseFundsCommand}
     * compensatório para ambas as carteiras (buyer + seller) — AT-1.1.4.
     */
    public static final String RK_FUNDS_SETTLEMENT_FAILED = "wallet.events.funds-settlement-failed";

    /**
     * Fila do order-service para consumir {@code FundsSettlementFailedEvent}.
     * Bound à {@code EVENTS_EXCHANGE} com routing key {@code RK_FUNDS_SETTLEMENT_FAILED}.
     */
    public static final String QUEUE_FUNDS_SETTLEMENT_FAILED = "order.events.funds-settlement-failed";

    // -------------------------------------------------------------------------
    // Filas do Read Model (projeção MongoDB — Query Side US-003)
    // Fanout pattern: cada fila abaixo recebe uma cópia do evento via topic exchange.
    // Garante que o Read Model seja atualizado de forma assíncrona e independente
    // do fluxo de Command Side, sem acoplamento entre os consumers.
    // -------------------------------------------------------------------------

    /** Fila de projeção para {@code OrderReceivedEvent} → cria documento PENDING no Mongo. */
    public static final String QUEUE_ORDER_PROJECTION_RECEIVED  = "order.projection.received";

    /** Fila de projeção para {@code FundsReservedEvent} → appenda FUNDS_RESERVED; status → OPEN. */
    public static final String QUEUE_ORDER_PROJECTION_FUNDS     = "order.projection.funds-reserved";

    /** Fila de projeção para {@code MatchExecutedEvent} → appenda MATCH_EXECUTED; status → FILLED/PARTIAL. */
    public static final String QUEUE_ORDER_PROJECTION_MATCH     = "order.projection.match-executed";

    /** Fila de projeção para {@code OrderCancelledEvent} → appenda ORDER_CANCELLED; status → CANCELLED. */
    public static final String QUEUE_ORDER_PROJECTION_CANCELLED = "order.projection.cancelled";

    // Dead Letter Queues das filas de projeção (AT-2.2.1)
    // Mensagens rejeitadas após desserialização inválida ou erro fatal são roteadas para estas filas.

    /** DLQ para mensagens tóxicas da fila {@code order.projection.received}. */
    public static final String QUEUE_ORDER_PROJECTION_RECEIVED_DLQ  = "order.projection.received.dlq";

    /** DLQ para mensagens tóxicas da fila {@code order.projection.funds-reserved}. */
    public static final String QUEUE_ORDER_PROJECTION_FUNDS_DLQ     = "order.projection.funds-reserved.dlq";

    /** DLQ para mensagens tóxicas da fila {@code order.projection.match-executed}. */
    public static final String QUEUE_ORDER_PROJECTION_MATCH_DLQ     = "order.projection.match-executed.dlq";

    /** DLQ para mensagens tóxicas da fila {@code order.projection.cancelled}. */
    public static final String QUEUE_ORDER_PROJECTION_CANCELLED_DLQ = "order.projection.cancelled.dlq";

    // Routing keys dos eventos publicados pelo order-service (usadas nas projection bindings)
    /** Routing key do {@code OrderReceivedEvent} — publicado em {@code OrderCommandService.placeOrder()}. */
    public static final String RK_ORDER_RECEIVED    = "order.events.order-received";
    /** Routing key do {@code MatchExecutedEvent} — publicado em {@code FundsReservedEventConsumer}. */
    public static final String RK_MATCH_EXECUTED        = "order.events.match-executed";
    /** Routing key do {@code OrderAddedToBookEvent} — publicado em {@code FundsReservedEventConsumer}. */
    public static final String RK_ORDER_ADDED_TO_BOOK   = "order.events.order-added-to-book";
    /** Routing key do {@code OrderCancelledEvent} — publicado em {@code FundsReservedEventConsumer}. */
    public static final String RK_ORDER_CANCELLED           = "order.events.order-cancelled";
    /** Routing key do {@code OrderFilledEvent} — publicado em {@code FundsReservedEventConsumer} quando match total. */
    public static final String RK_ORDER_FILLED              = "order.events.order-filled";
    /** Routing key do {@code OrderPartiallyFilledEvent} — publicado em {@code FundsReservedEventConsumer} quando match parcial. */
    public static final String RK_ORDER_PARTIALLY_FILLED    = "order.events.order-partially-filled";

    // -------------------------------------------------------------------------
    // Exchanges
    // -------------------------------------------------------------------------

    @Bean
    DirectExchange commandsExchange() {
        return new DirectExchange(COMMANDS_EXCHANGE, true, false);
    }

    @Bean
    TopicExchange eventsExchange() {
        return new TopicExchange(EVENTS_EXCHANGE, true, false);
    }

    @Bean
    DirectExchange dlqExchange() {
        return new DirectExchange(DLQ_EXCHANGE, true, false);
    }

    // -------------------------------------------------------------------------
    // Dead Letter Queue
    // -------------------------------------------------------------------------

    @Bean
    Queue orderDeadLetterQueue() {
        // DLQ sem TTL — mensagens ficam aqui até intervenção manual
        return QueueBuilder.durable(QUEUE_DEAD_LETTER).build();
    }

    /**
     * Binding obrigatório: {@code vibranium.dlq} exchange → {@code order.dead-letter} queue.
     *
     * <p>Sem este binding, as filas configuradas com
     * {@code x-dead-letter-exchange=vibranium.dlq} e
     * {@code x-dead-letter-routing-key=order.dead-letter} descartariam as
     * mensagens silenciosamente (unroutable), pois a exchange não teria
     * destino declarado para essa routing key.</p>
     */
    @Bean
    Binding deadLetterBinding(
            @Qualifier("orderDeadLetterQueue") Queue orderDeadLetterQueue,
            @Qualifier("dlqExchange")          DirectExchange dlqExchange) {
        return BindingBuilder
                .bind(orderDeadLetterQueue)
                .to(dlqExchange)
                .with(QUEUE_DEAD_LETTER);
    }

    // -------------------------------------------------------------------------
    // Fila de FundsReservedEvent (wallet → order: fundos bloqueados)
    // -------------------------------------------------------------------------

    @Bean
    Queue fundsReservedQueue() {
        return QueueBuilder.durable(QUEUE_FUNDS_RESERVED)
                // Mensagens rejeitadas vão para a DLQ
                .withArgument("x-dead-letter-exchange", DLQ_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", QUEUE_DEAD_LETTER)
                .build();
    }

    @Bean
    Binding fundsReservedBinding(
            @Qualifier("fundsReservedQueue") Queue fundsReservedQueue,
            @Qualifier("eventsExchange")     TopicExchange eventsExchange) {
        return BindingBuilder
                .bind(fundsReservedQueue)
                .to(eventsExchange)
                .with(RK_FUNDS_RESERVED);
    }

    // -------------------------------------------------------------------------
    // Fila de FundsReservationFailedEvent (wallet → order: reserva falhou)
    // -------------------------------------------------------------------------

    @Bean
    Queue fundsFailedQueue() {
        return QueueBuilder.durable(QUEUE_FUNDS_FAILED)
                .withArgument("x-dead-letter-exchange", DLQ_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", QUEUE_DEAD_LETTER)
                .build();
    }

    @Bean
    Binding fundsFailedBinding(
            @Qualifier("fundsFailedQueue") Queue fundsFailedQueue,
            @Qualifier("eventsExchange")   TopicExchange eventsExchange) {
        return BindingBuilder
                .bind(fundsFailedQueue)
                .to(eventsExchange)
                .with(RK_FUNDS_FAILED);
    }

    // -------------------------------------------------------------------------
    // Fila de eventos Keycloak (plugin aznamier → amq.topic)
    // -------------------------------------------------------------------------

    @Bean
    Queue keycloakRegisterQueue() {
        return QueueBuilder.durable(QUEUE_KEYCLOAK_REG)
                .withArgument("x-dead-letter-exchange", DLQ_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", QUEUE_DEAD_LETTER)
                .build();
    }

    /**
     * Bind para o amq.topic built-in do RabbitMQ.
     * O roteamento usa os routing keys do plugin aznamier:
     * {@code KK.EVENT.CLIENT.orderbook-realm.REGISTER}.
     */
    @Bean
    Binding keycloakRegisterBinding(
            @Qualifier("keycloakRegisterQueue") Queue keycloakRegisterQueue) {
        return BindingBuilder
                .bind(keycloakRegisterQueue)
                .to(new TopicExchange(KEYCLOAK_EXCHANGE))
                .with(RK_KEYCLOAK_REGISTER);
    }

    // -------------------------------------------------------------------------
    // Nota de topologia: as filas wallet.commands.reserve-funds e
    // wallet.commands.release-funds são DECLARADAS pelo wallet-service (consumidor),
    // que as configura com DLX. O order-service apenas publica nessas filas via
    // COMMANDS_EXCHANGE (vibranium.commands) — não as declara aqui para evitar
    // conflito de argumentos (PRECONDITION_FAILED) quando o wallet-service inicia.
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // Fila de FundsSettlementFailedEvent (wallet → order: liquidação falhou — AT-1.1.4)
    // -------------------------------------------------------------------------

    /**
     * Fila durable para {@code FundsSettlementFailedEvent} com DLX configurada.
     * Falhas de processamento vão para a DLQ para intervenção manual, visto ser
     * um evento crítico de incidente financeiro.
     */
    @Bean
    Queue fundsSettlementFailedQueue() {
        return QueueBuilder.durable(QUEUE_FUNDS_SETTLEMENT_FAILED)
                .withArgument("x-dead-letter-exchange", DLQ_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", QUEUE_DEAD_LETTER)
                .build();
    }

    @Bean
    Binding fundsSettlementFailedBinding(
            @Qualifier("fundsSettlementFailedQueue") Queue fundsSettlementFailedQueue,
            @Qualifier("eventsExchange")             TopicExchange eventsExchange) {
        return BindingBuilder
                .bind(fundsSettlementFailedQueue)
                .to(eventsExchange)
                .with(RK_FUNDS_SETTLEMENT_FAILED);
    }

    // -------------------------------------------------------------------------
    // Filas de Projeção do Read Model (MongoDB — Query Side US-003)
    //
    // Cada fila abaixo recebe uma cópia independente do evento via topic exchange
    // (fanout pattern). O consumer de projeção constrói e mantém o OrderDocument.
    //
    // AT-2.2.1: DLX configurada em todas as filas de projeção para capturar mensagens
    // tóxicas (payload inválido / NullPointerException no listener) após esgotamento
    // das tentativas. Sem DLX, mensagens não-desserializáveis causavam loop infinito
    // quando defaultRequeueRejected=true (padrão do Spring AMQP), ou se perdiam
    // silenciosamente com requeue=false. As DLQs correspondentes permitem inspeção
    // e re-processamento manual via rabbitmqadmin ou Management UI.
    // -------------------------------------------------------------------------

    @Bean
    Queue orderProjectionReceivedQueue() {
        // AT-2.2.1: DLX configurada para capturar mensagens tóxicas (payload inválido).
        // Routing key individual por fila permite diferenciar a origem na DLQ.
        return QueueBuilder.durable(QUEUE_ORDER_PROJECTION_RECEIVED)
                .withArgument("x-dead-letter-exchange", DLQ_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", QUEUE_ORDER_PROJECTION_RECEIVED_DLQ)
                .build();
    }

    @Bean
    Binding orderProjectionReceivedBinding(
            @Qualifier("orderProjectionReceivedQueue") Queue orderProjectionReceivedQueue,
            @Qualifier("eventsExchange")               TopicExchange eventsExchange) {
        return BindingBuilder
                .bind(orderProjectionReceivedQueue)
                .to(eventsExchange)
                .with(RK_ORDER_RECEIVED);
    }

    @Bean
    Queue orderProjectionFundsQueue() {
        // AT-2.2.1: DLX para roteamento de mensagens tóxicas (FundsReservedEvent inválido).
        return QueueBuilder.durable(QUEUE_ORDER_PROJECTION_FUNDS)
                .withArgument("x-dead-letter-exchange", DLQ_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", QUEUE_ORDER_PROJECTION_FUNDS_DLQ)
                .build();
    }

    @Bean
    Binding orderProjectionFundsBinding(
            @Qualifier("orderProjectionFundsQueue") Queue orderProjectionFundsQueue,
            @Qualifier("eventsExchange")            TopicExchange eventsExchange) {
        // Fanout: FundsReservedEvent já vai para order.events.funds-reserved (Command Side)
        // E agora também para order.projection.funds-reserved (Read Model).
        return BindingBuilder
                .bind(orderProjectionFundsQueue)
                .to(eventsExchange)
                .with(RK_FUNDS_RESERVED);
    }

    @Bean
    Queue orderProjectionMatchQueue() {
        // AT-2.2.1: DLX para roteamento de mensagens tóxicas (MatchExecutedEvent inválido).
        return QueueBuilder.durable(QUEUE_ORDER_PROJECTION_MATCH)
                .withArgument("x-dead-letter-exchange", DLQ_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", QUEUE_ORDER_PROJECTION_MATCH_DLQ)
                .build();
    }

    @Bean
    Binding orderProjectionMatchBinding(
            @Qualifier("orderProjectionMatchQueue") Queue orderProjectionMatchQueue,
            @Qualifier("eventsExchange")            TopicExchange eventsExchange) {
        return BindingBuilder
                .bind(orderProjectionMatchQueue)
                .to(eventsExchange)
                .with(RK_MATCH_EXECUTED);
    }

    @Bean
    Queue orderProjectionCancelledQueue() {
        // AT-2.2.1: DLX para roteamento de mensagens tóxicas (OrderCancelledEvent inválido).
        return QueueBuilder.durable(QUEUE_ORDER_PROJECTION_CANCELLED)
                .withArgument("x-dead-letter-exchange", DLQ_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", QUEUE_ORDER_PROJECTION_CANCELLED_DLQ)
                .build();
    }

    @Bean
    Binding orderProjectionCancelledBinding(
            @Qualifier("orderProjectionCancelledQueue") Queue orderProjectionCancelledQueue,
            @Qualifier("eventsExchange")                TopicExchange eventsExchange) {
        return BindingBuilder
                .bind(orderProjectionCancelledQueue)
                .to(eventsExchange)
                .with(RK_ORDER_CANCELLED);
    }

    // -------------------------------------------------------------------------
    // Dead Letter Queues das filas de projeção (AT-2.2.1)
    //
    // Cada DLQ captura as mensagens rejeitadas da fila correspondente.
    // Binding: vibranium.dlq exchange → DLQ queue com routing key = nome da DLQ.
    // Mensagens ficam nestas filas até intervenção manual (inspeção / re-processamento).
    // -------------------------------------------------------------------------

    /**
     * DLQ para mensagens tóxicas de {@code order.projection.received}.
     *
     * <p>Captura payloads inválidos que causam {@code MessageConversionException}
     * ou {@code NullPointerException} no listener {@code onOrderReceived}.
     * O Spring AMQP emite {@code basicNack(requeue=false)} após o {@code autoAckContainerFactory}
     * com {@code defaultRequeueRejected=false} — a mensagem é então encaminhada
     * pela DLX {@code vibranium.dlq} com routing key {@code order.projection.received.dlq}.</p>
     */
    @Bean
    Queue orderProjectionReceivedDlq() {
        return QueueBuilder.durable(QUEUE_ORDER_PROJECTION_RECEIVED_DLQ).build();
    }

    @Bean
    Binding orderProjectionReceivedDlqBinding(
            @Qualifier("orderProjectionReceivedDlq") Queue orderProjectionReceivedDlq,
            @Qualifier("dlqExchange")                DirectExchange dlqExchange) {
        return BindingBuilder
                .bind(orderProjectionReceivedDlq)
                .to(dlqExchange)
                .with(QUEUE_ORDER_PROJECTION_RECEIVED_DLQ);
    }

    /** DLQ para mensagens tóxicas de {@code order.projection.funds-reserved}. */
    @Bean
    Queue orderProjectionFundsDlq() {
        return QueueBuilder.durable(QUEUE_ORDER_PROJECTION_FUNDS_DLQ).build();
    }

    @Bean
    Binding orderProjectionFundsDlqBinding(
            @Qualifier("orderProjectionFundsDlq") Queue orderProjectionFundsDlq,
            @Qualifier("dlqExchange")             DirectExchange dlqExchange) {
        return BindingBuilder
                .bind(orderProjectionFundsDlq)
                .to(dlqExchange)
                .with(QUEUE_ORDER_PROJECTION_FUNDS_DLQ);
    }

    /** DLQ para mensagens tóxicas de {@code order.projection.match-executed}. */
    @Bean
    Queue orderProjectionMatchDlq() {
        return QueueBuilder.durable(QUEUE_ORDER_PROJECTION_MATCH_DLQ).build();
    }

    @Bean
    Binding orderProjectionMatchDlqBinding(
            @Qualifier("orderProjectionMatchDlq") Queue orderProjectionMatchDlq,
            @Qualifier("dlqExchange")             DirectExchange dlqExchange) {
        return BindingBuilder
                .bind(orderProjectionMatchDlq)
                .to(dlqExchange)
                .with(QUEUE_ORDER_PROJECTION_MATCH_DLQ);
    }

    /** DLQ para mensagens tóxicas de {@code order.projection.cancelled}. */
    @Bean
    Queue orderProjectionCancelledDlq() {
        return QueueBuilder.durable(QUEUE_ORDER_PROJECTION_CANCELLED_DLQ).build();
    }

    @Bean
    Binding orderProjectionCancelledDlqBinding(
            @Qualifier("orderProjectionCancelledDlq") Queue orderProjectionCancelledDlq,
            @Qualifier("dlqExchange")                 DirectExchange dlqExchange) {
        return BindingBuilder
                .bind(orderProjectionCancelledDlq)
                .to(dlqExchange)
                .with(QUEUE_ORDER_PROJECTION_CANCELLED_DLQ);
    }

    // -------------------------------------------------------------------------
    // MessageConverter — JSON via Jackson (suporta records e DomainEvent)
    // -------------------------------------------------------------------------

    /**
     * Converte mensagens AMQP para/de JSON usando o ObjectMapper da aplicação.
     *
     * <p>Injeta o {@code @Primary ObjectMapper} configurado em {@link JacksonConfig},
     * garantindo que {@code Instant} seja serializado como epoch-millis (long)
     * conforme o contrato de {@link com.vibranium.contracts.events.DomainEvent}.</p>
     *
     * <p>O Spring Boot {@code RabbitAutoConfiguration} detecta automaticamente
     * este bean via {@code ObjectProvider<MessageConverter>} e o injeta no
     * {@code RabbitTemplate} auto-configurado — sem necessidade de redefinir
     * {@code RabbitTemplate} manualmente.</p>
     */
    @Bean
    MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    /**
     * AT-14.1 — Injeta o {@link ObservationRegistry} real no {@link RabbitTemplate} via reflexão.
     *
     * <p>O {@link RabbitAccessor} (superclasse do {@code RabbitTemplate}) inicializa
     * {@code observationRegistry = ObservationRegistry.NOOP} e <strong>não expõe setter
     * público</strong> para este campo. O mecanismo de descoberta tardia
     * ({@code obtainObservationRegistry}) usa {@code ObjectProvider.getIfUnique()}, que
     * retorna o fallback {@code NOOP} quando dois ou mais beans {@code ObservationRegistry}
     * estão presentes no contexto — situação comum com test auto-configurations do Spring
     * Boot ({@code TestObservabilityAutoConfiguration}) que adicionam instâncias auxiliares.</p>
     *
     * <p>Este {@link SmartInitializingSingleton} é invocado diretamente pelo
     * {@code DefaultListableBeanFactory.preInstantiateSingletons()} <strong>após</strong>
     * todos os singletons estarem completamente inicializados, garantindo que:</p>
     * <ol>
     *   <li>O {@code ObservationRegistry} da {@code ObservationAutoConfiguration} está
     *       totalmente configurado com os {@code PropagatingTracingObservationHandler}
     *       registrados (via {@code ObservationRegistryCustomizer}).</li>
     *   <li>O {@code RabbitTemplate} já recebeu o callback
     *       {@code ApplicationContextAware.setApplicationContext()}.</li>
     *   <li>A injeção direta substitui {@code NOOP} pelo registry real antes do primeiro
     *       {@code convertAndSend()}, habilitando a criação de spans OTel e a injeção
     *       do header W3C {@code traceparent} em toda mensagem AMQP publicada.</li>
     * </ol>
     *
     * @param rabbitTemplate      template auto-configurado pelo Spring Boot
     * @param observationRegistry registry com {@code PropagatingTracingObservationHandler}
     *                            registrado pelo {@code MicrometerTracingAutoConfiguration}
     * @return singleton initializer que injeta o registry após todos os beans estarem prontos
     */
    /**
     * Garante que o {@link RabbitTemplate} utiliza o {@link ObservationRegistry} correto
     * (non-NOOP) e que o {@link PropagatingTracingObservationHandler} está registrado para
     * propagar o header W3C {@code traceparent} nas mensagens AMQP.
     *
     * <p>Por que {@code SmartInitializingSingleton}: executado após todos os beans do contexto
     * estarem criados, garantindo que {@code Tracer} e {@code Propagator} já existem.</p>
     *
     * <p>Por que reflexão no {@code RabbitTemplate}: o Spring Boot 3.4.x não expõe setter
     * público para {@code observationRegistry} no {@link RabbitAccessor}; o
     * {@code RabbitAutoConfiguration} não injeta o registry automaticamente.</p>
     *
     * <p><strong>Causa raiz do ContextPropagators vazio:</strong> em alguns contextos de boot
     * o {@code OtelTracingBridgeAutoConfiguration.contextPropagators()} é instanciado com a
     * lista de {@code TextMapPropagator} ainda vazia, resultando em um propagator sem campos
     * ({@code fields()=[]}). O bean {@link #w3cContextPropagators()} abaixo corrige isso
     * fornecendo um {@code ContextPropagators} explícito com {@code W3CTraceContextPropagator}.
     * Adicionalmente, o {@code MessagePostProcessor} registrado aqui constrói o header
     * {@code traceparent} diretamente do {@link TraceContext}, contornando o propagador
     * potencialmente mal-configurado.</p>
     *
     * @param rabbitTemplate      instância gerenciada pelo Spring AMQP
     * @param observationRegistry registry com handlers de tracing configurados pelo Actuator
     * @param tracer              tracer OTel fornecido por {@code micrometer-tracing-bridge-otel}
     * @param propagator          propagator W3C fornecido por {@code micrometer-tracing-bridge-otel}
     * @return singleton initializer que injeta o registry e o header de propagação
     */
    @Bean
    SmartInitializingSingleton rabbitTemplateObservationSetup(
            RabbitTemplate rabbitTemplate,
            ObservationRegistry observationRegistry,
            Tracer tracer,
            Propagator propagator) {
        return () -> {
            // ── 1. Pipeline de Observation: injeta ObservationRegistry real via reflexão ──
            // Necessário porque RabbitAutoConfiguration não injeta ObservationRegistry no
            // RabbitTemplate automaticamente no spring-rabbit 3.2.x.
            try {
                observationRegistry.observationConfig()
                        .observationHandler(new PropagatingSenderTracingObservationHandler<>(tracer, propagator));

                Field registryField = RabbitAccessor.class.getDeclaredField("observationRegistry");
                registryField.setAccessible(true);
                registryField.set(rabbitTemplate, observationRegistry);

                // Bloqueia re-descoberta do registry via obtainObservationRegistry(), que
                // poderia sobrescrever com NOOP (ObjectProvider.getIfUnique() falha quando
                // há múltiplos beans ObservationRegistry no contexto de teste).
                Field obtainedField = RabbitTemplate.class.getDeclaredField("observationRegistryObtained");
                obtainedField.setAccessible(true);
                obtainedField.set(rabbitTemplate, true);

                logger.info("AT-14.1: ObservationRegistry + PropagatingTracingObservationHandler configurados no RabbitTemplate");
            } catch (NoSuchFieldException | IllegalAccessException e) {
                logger.warn("AT-14.1: Falha ao configurar observação via reflexão no RabbitTemplate", e);
            }

            // ── 2. MessagePostProcessor: garante traceparent W3C independente do pipeline ──
            // Constrói o header traceparent diretamente do TraceContext, contornando o
            // OtelPropagator cujo ContextPropagators pode estar vazio (sem W3CTraceContextPropagator)
            // quando a auto-configuração do OTel Bridge é instanciada antes dos beans
            // TextMapPropagator serem registrados.
            //
            // Formato W3C: "00-{traceId:32hex}-{spanId:16hex}-{01=sampled|00=not-sampled}"
            // Spec: https://www.w3.org/TR/trace-context/#traceparent-header
            rabbitTemplate.addBeforePublishPostProcessors(message -> {
                // Não sobrescreve traceparent já presente: preserva contexto upstream
                // (e.g., forwarding de mensagens entre serviços no mesmo trace distribuído)
                if (message.getMessageProperties().getHeaders().containsKey("traceparent")) {
                    return message;
                }
                io.micrometer.tracing.Span span = tracer.nextSpan()
                        .name("rabbit.publish")
                        .start();
                if (!span.isNoop()) {
                    TraceContext ctx = span.context();
                    // Constrói traceparent conforme W3C TraceContext Level 1 spec
                    String flags = Boolean.TRUE.equals(ctx.sampled()) ? "01" : "00";
                    String traceparent = "00-" + ctx.traceId() + "-" + ctx.spanId() + "-" + flags;
                    message.getMessageProperties().setHeader("traceparent", traceparent);
                    logger.debug("AT-14.1: traceparent={}", traceparent);
                } else {
                    logger.warn("AT-14.1: OtelTracer retornou span NOOP — traceparent não injetado");
                }
                span.end();
                return message;
            });
            logger.info("AT-14.1: MessagePostProcessor W3C traceparent registrado no RabbitTemplate");
        };
    }

    // -------------------------------------------------------------------------
    // Listener Container Factory — ACK Manual (idempotencia por tabela)
    // -------------------------------------------------------------------------

    /**
     * Container factory com {@link AcknowledgeMode#MANUAL} para os consumers que
     * utilizam idempotencia por tabela ({@link com.vibranium.orderservice.adapter.messaging.FundsReservedEventConsumer}
     * e {@link com.vibranium.orderservice.adapter.messaging.FundsReservationFailedEventConsumer}).
     *
     * <p>Com ACK manual, o broker somente remove a mensagem da fila apos o consumer
     * chamar {@code channel.basicAck(deliveryTag, false)} explicitamente.
     * Isso garante que o ACK ocorre <strong>apos</strong> o commit JPA,
     * eliminando a janela de duplicacao presente no ACK automatico.</p>
     *
     * <p>Referenciado nos listeners via {@code @RabbitListener(containerFactory = "manualAckContainerFactory")}.</p>
     *
     * @param connectionFactory  Conexao AMQP gerenciada pelo Spring Boot auto-configure.
     * @param jsonMessageConverter Conversor Jackson2JSON compartilhado.
     */
    @Bean
    SimpleRabbitListenerContainerFactory manualAckContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter jsonMessageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter);
        // ACK manual: o consumer e responsavel por chamar basicAck/basicNack explicitamente
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        return factory;
    }

    // -------------------------------------------------------------------------
    // Listener Container Factory — ACK Automático (projeção MongoDB — Query Side)
    // -------------------------------------------------------------------------

    /**
     * Container factory com {@link AcknowledgeMode#AUTO} para os consumers de projeção
     * ({@link com.vibranium.orderservice.query.consumer.OrderEventProjectionConsumer}).
     *
     * <h3>Por que AUTO e não MANUAL para projeção?</h3>
     * <p>Os listeners de projeção não possuem parâmetro {@code Channel} e não chamam
     * {@code basicAck()} explicitamente. Com o {@code acknowledge-mode: manual} global
     * configurado em {@code application.yaml}, eles herdariam MANUAL pelo factory padrão
     * e todas as mensagens ficariam em estado {@code unacknowledged} indefinidamente —
     * acumulando no broker durante o ciclo de vida do serviço.</p>
     *
     * <h3>Justificativa de segurança</h3>
     * <p>Filas de projeção são idempotentes: o filtro {@code $ne} no MongoDB
     * ({@link com.vibranium.orderservice.query.service.OrderAtomicHistoryWriter})
     * garante que eventos duplicados não corrompem o Read Model. O re-processamento
     * acidental é inofensivo; a perda de evento degrada o Read Model mas não afeta
     * o Command Side (PostgreSQL permanece íntegro).</p>
     *
     * <p>Com AUTO, o Spring AMQP chama {@code basicAck()} automaticamente após o método
     * listener retornar sem exceção. Erros de processamento disparam o retry policy
     * configurado em {@code application.yaml} (max-attempts=3) e, após esgotamento,
     * a mensagem é descartada (sem DLX nas filas de projeção — comportamento intencional).</p>
     *
     * <p>Referenciado nos listeners via
     * {@code @RabbitListener(containerFactory = "autoAckContainerFactory")}.</p>
     *
     * @param connectionFactory    Conexao AMQP gerenciada pelo Spring Boot auto-configure.
     * @param jsonMessageConverter Conversor Jackson2JSON compartilhado com os demais factories.
     */
    @Bean
    SimpleRabbitListenerContainerFactory autoAckContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter jsonMessageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter);
        // AUTO: Spring AMQP confirma a mensagem automaticamente após o listener retornar.
        // Adequado para projeções idempotentes onde mensagem perdida é preferível
        // a mensagem acumulada indefinidamente no broker sem confirmação.
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        // AT-2.2.1: requeue=false em caso de exceção qualquer (não somente MessageConversionException).
        // Sem este flag, NPEs e outros erros de runtime causariam loop infinito ao recolocar a
        // mensagem na fila indefinidamente. Com false, a mensagem é rejeitada e roteada para
        // a DLX configurada em cada fila de projeção.
        factory.setDefaultRequeueRejected(false);
        return factory;
    }

    // -------------------------------------------------------------------------
    // TransactionTemplate — Saga TCC (AT-2.1.1)
    // -------------------------------------------------------------------------

    /**
     * {@link TransactionTemplate} utilizado pelo
     * {@link com.vibranium.orderservice.adapter.messaging.FundsReservedEventConsumer}
     * para separar as fases da Saga em transações JPA independentes.
     *
     * <p><strong>Motivação (AT-2.1.1):</strong> Redis não participa da transação JPA.
     * Misturar operações Redis e JPA no mesmo {@code @Transactional} cria a ilusão
     * de atomicidade distribuída que não existe — violação do padrão TCC
     * (Try-Confirm-Cancel). O {@link TransactionTemplate} delimita explicitamente
     * cada fase JPA, mantendo {@code tryMatch()} completamente fora de qualquer
     * escopo transacional.</p>
     *
     * <p>Propagação {@code REQUIRED} (padrão): cria nova TX se nenhuma estiver ativa,
     * que é justamente o caso no método listener (sem {@code @Transactional} externo).</p>
     *
     * @param transactionManager {@link PlatformTransactionManager} auto-configurado
     *                           pelo Spring Boot JPA.
     */
    @Bean
    TransactionTemplate sagaTransactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }
}
