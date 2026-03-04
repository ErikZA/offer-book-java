package com.vibranium.walletservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.PropagatingSenderTracingObservationHandler;
import io.micrometer.tracing.propagation.Propagator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.RabbitAccessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Field;

/**
 * Configuração das exchanges, filas e bindings do RabbitMQ para o wallet-service.
 *
 * <p>Topologia:</p>
 * <pre>
 *   Exchange: keycloak.events (topic)
 *     └─ Binding: KK.EVENT.CLIENT.# → Queue: wallet.keycloak.events
 *          └─ DLX: vibranium.dlq → wallet.keycloak.events.dlq
 *
 *   Exchange: wallet.commands (topic)
 *     ├─ Binding: wallet.command.reserve-funds → Queue: wallet.commands.reserve-funds
 *     │    └─ DLX: vibranium.dlq → wallet.commands.reserve-funds.dlq
 *     ├─ Binding: wallet.command.release-funds → Queue: wallet.commands.release-funds
 *     │    └─ DLX: vibranium.dlq → wallet.commands.release-funds.dlq
 *     └─ Binding: wallet.command.settle-funds  → Queue: wallet.commands
 *
 *   Exchange: vibranium.dlq (direct) — Dead Letter Exchange
 *     ├─ Binding: wallet.keycloak.events.dlq       → Queue: wallet.keycloak.events.dlq
 *     ├─ Binding: wallet.commands.reserve-funds.dlq → Queue: wallet.commands.reserve-funds.dlq
 *     └─ Binding: wallet.commands.release-funds.dlq → Queue: wallet.commands.release-funds.dlq
 * </pre>
 *
 * <p>Todos os recursos são declarados com {@code durable=true} para sobreviverem
 * a reinicializações do broker. O {@code RabbitAdmin} criará automaticamente
 * exchanges e filas que ainda não existam no broker.</p>
 *
 * <p><b>DLQ (Dead Letter Queue):</b> As filas {@code wallet.keycloak.events},
 * {@code wallet.commands.reserve-funds} e {@code wallet.commands.release-funds} possuem
 * {@code x-dead-letter-exchange=vibranium.dlq} com routing keys DLQ individuais. Qualquer
 * mensagem rejeitada com {@code requeue=false} — seja por NACK explícito do listener ou
 * por TTL expirado — é encaminhada automaticamente ao exchange DLX, eliminando a perda
 * silenciosa de mensagens críticas (registro de usuário e comandos financeiros da Saga).</p>
 */
@Configuration
public class RabbitMQConfig {

    private static final Logger logger = LoggerFactory.getLogger(RabbitMQConfig.class);

    // -------------------------------------------------------------------------
    // Constantes de topologia
    // -------------------------------------------------------------------------

    /**
     * Fila que recebe eventos de criação de usuário publicados pelo plugin Keycloak.
     * Promovida a constante para que testes e bindings referenciem sem strings literais.
     */
    public static final String QUEUE_KEYCLOAK_EVENTS = "wallet.keycloak.events";

    /**
     * Dead Letter Queue para eventos Keycloak não processáveis.
     * Recebe mensagens NACKed com {@code requeue=false} da fila principal,
     * garantindo rastreabilidade de falhas de registro de usuário.
     */
    public static final String QUEUE_KEYCLOAK_EVENTS_DLQ = "wallet.keycloak.events.dlq";

    /**
     * Exchange DLX (Dead Letter Exchange) centralizado para o wallet-service.
     * Mensagens rejeitadas definitivamente de qualquer fila que declare este
     * exchange como {@code x-dead-letter-exchange} serão roteadas aqui.
     *
     * <p>Usa um {@link DirectExchange} para roteamento determinístico via
     * {@code x-dead-letter-routing-key} declarado em cada fila produtora.</p>
     */
    public static final String DLQ_EXCHANGE = "vibranium.dlq";

    /**
     * Fila dedicada para {@link com.vibranium.contracts.commands.wallet.ReserveFundsCommand}.
     * Separada da fila genérica {@code wallet.commands} para permitir configurar
     * política de DLQ de forma cirúrgica, sem afetar outros comandos.
     */
    public static final String QUEUE_RESERVE_FUNDS = "wallet.commands.reserve-funds";

    /**
     * Fila dedicada para {@link com.vibranium.contracts.commands.wallet.ReleaseFundsCommand}.
     * Separada com DLQ própria — falhas no caminho compensatório indicam fundos
     * permanentemente bloqueados, exigindo análise manual imediata.
     */
    public static final String QUEUE_RELEASE_FUNDS = "wallet.commands.release-funds";

    /**
     * Dead Letter Queue para o comando de liberação de fundos.
     * Mensagens aqui representam fundos que não foram liberados — incidente crítico.
     */
    public static final String QUEUE_RELEASE_FUNDS_DLQ = "wallet.commands.release-funds.dlq";

    /**
     * Dead Letter Queue para o comando de reserva de fundos.
     * Recebe automaticamente qualquer mensagem da fila principal que seja:
     * <ul>
     *   <li>NACKed com {@code requeue=false} (ex: falha permanente no listener).</li>
     *   <li>Expirada por TTL (caso configurado no futuro).</li>
     *   <li>Rejeitada por fila cheia (overflow).</li>
     * </ul>
     */
    public static final String QUEUE_RESERVE_FUNDS_DLQ = "wallet.commands.reserve-funds.dlq";

    // -------------------------------------------------------------------------
    // Exchanges
    // -------------------------------------------------------------------------

    /**
     * Dead Letter Exchange (DLX) centralizado do wallet-service.
     *
     * <p>Declarado como {@link DirectExchange} para garantir roteamento determinístico:
     * cada fila produtora declara um {@code x-dead-letter-routing-key} único,
     * e este exchange encaminha diretamente à fila DLQ correspondente.</p>
     *
     * <p>Ao usar um exchange dedicado (em vez de configuração por policy no broker),
     * a DLQ é <b>declarada via código</b>, ficando versionada no repositório, auditável,
     * replicável em qualquer ambiente e livre de drift de configuração manual.</p>
     */
    @Bean
    public DirectExchange dlqExchange() {
        // durable=true: sobrevive a restart do broker
        // autoDelete=false: não é removido quando não há consumers
        return new DirectExchange(DLQ_EXCHANGE, true, false);
    }

    /**
     * Exchange topic do Keycloak. O plugin {@code aznamier/keycloak-event-listener-rabbitmq}
     * publica nesta exchange com routing keys no formato:
     * {@code KK.EVENT.CLIENT.<realm>.SUCCESS.CLIENT.<eventType>}.
     */
    @Bean
    public TopicExchange keycloakEventsExchange() {
        return new TopicExchange("keycloak.events", true, false);
    }

    /**
     * Exchange de comandos do sistema (direct) — usada pelo order-service para publicar
     * {@code ReserveFundsCommand} e {@code ReleaseFundsCommand}.
     *
     * <p>Re-declarada aqui idempotentemente (mesmos argumentos que o order-service declara)
     * para garantir que o broker a crie mesmo se o wallet-service inicializar antes do
     * order-service.</p>
     */
    @Bean
    public DirectExchange vibraniumCommandsExchange() {
        return new DirectExchange("vibranium.commands", true, false);
    }

    /**
     * Exchange topic para comandos direcionados ao wallet-service via exchange dedicada.
     * Mantida para compatibilidade, mas o roteamento principal usa {@code vibranium.commands}.
     */
    @Bean
    public TopicExchange walletCommandsExchange() {
        return new TopicExchange("wallet.commands", true, false);
    }

    /**
     * Exchange topic principal de eventos de domínio do wallet-service.
     *
     * <p>O {@link com.vibranium.walletservice.infrastructure.outbox.OutboxPublisherService}
     * publica todos os eventos aqui com routing-keys definidas em
     * {@link com.vibranium.walletservice.infrastructure.outbox.EventRoute}.</p>
     *
     * <p>Consumers (order-service, etc.) se ligam a esta exchange com suas
     * próprias filas e routing-key patterns.</p>
     */
    @Bean
    public TopicExchange vibraniumEventsExchange() {
        return new TopicExchange("vibranium.events", true, false);
    }

    // -------------------------------------------------------------------------
    // Queues
    // -------------------------------------------------------------------------

    /**
     * Fila que recebe eventos de criação de usuário publicados pelo plugin
     * {@code aznamier/keycloak-event-listener-rabbitmq}.
     *
     * <p>Configurada com DLX para garantir que eventos de registro com falha permanente
     * (ex: falha de banco, estado de wallet inválido) não sejam silenciosamente descartados.
     * O {@link com.vibranium.walletservice.infrastructure.messaging.KeycloakRabbitListener}
     * executa {@code basicNack(tag, false, false)} nesses casos; sem DLX, a mensagem
     * seria perdida, impedindo criação de wallet e auditoria posterior.</p>
     *
     * <p>Usa a mesma estratégia de DLQ por declaração explícita (em vez de policy)
     * adotada nas filas de comandos — versionada em código, reproduzível em qualquer
     * ambiente sem configuração manual no broker.</p>
     */
    @Bean
    public Queue walletKeycloakEventsQueue() {
        return QueueBuilder.durable(QUEUE_KEYCLOAK_EVENTS)
                // Encaminha NACKs definitivos (requeue=false) para o exchange DLX
                .withArgument("x-dead-letter-exchange", DLQ_EXCHANGE)
                // Routing key determinística na DLQ — identifica a fila de origem
                .withArgument("x-dead-letter-routing-key", QUEUE_KEYCLOAK_EVENTS_DLQ)
                .build();
    }

    /**
     * Dead Letter Queue para eventos Keycloak não processáveis.
     *
     * <p>Mensagens aqui representam usuários cujas wallets não puderam ser criadas.
     * Requerem análise operacional e possível reprocessamento manual supervisionado.</p>
     *
     * <p>Declarada como fila simples durable — sem TTL nem retry automático,
     * para evitar loops infinitos em mensagens genuinamente inválidas (poison pill).</p>
     */
    @Bean
    public Queue walletKeycloakEventsDlQueue() {
        return QueueBuilder.durable(QUEUE_KEYCLOAK_EVENTS_DLQ).build();
    }

    /** Fila que recebe comandos de liquidação de fundos (SettleFundsCommand). */
    @Bean
    public Queue walletCommandsQueue() {
        return QueueBuilder.durable("wallet.commands").build();
    }

    /**
     * Fila dedicada para {@link com.vibranium.contracts.commands.wallet.ReserveFundsCommand}.
     *
     * <p>Os argumentos {@code x-dead-letter-exchange} e {@code x-dead-letter-routing-key}
     * são declarados <b>na fila produtora</b> (aqui), não no exchange, conforme
     * recomendação do RabbitMQ para DLQ por declaração explícita.</p>
     *
     * <p>Quando o listener executa {@code channel.basicNack(tag, false, false)},
     * o RabbitMQ ignora a fila normal e encaminha a mensagem automaticamente para
     * o exchange {@value DLQ_EXCHANGE} com a routing key
     * {@value QUEUE_RESERVE_FUNDS_DLQ}, sem qualquer ação extra do código.</p>
     *
     * <p><b>Por que declaração explícita em vez de policy?</b><br>
     * Policies são configuradas manualmente no broker (Management UI ou CLI),
     * criando drift entre ambientes. A declaração via código é:
     * <ul>
     *   <li>Versionada no Git — rastreável em todo PR.</li>
     *   <li>Reproduzível — qualquer ambiente novo (dev, test, staging) fica correto.</li>
     *   <li>Self-documenting — a intenção está no código, não numa wiki.</li>
     * </ul></p>
     */
    @Bean
    public Queue reserveFundsQueue() {
        return QueueBuilder.durable(QUEUE_RESERVE_FUNDS)
                // Encaminha mensagens rejeitadas (NACK requeue=false) para o exchange DLX
                .withArgument("x-dead-letter-exchange", DLQ_EXCHANGE)
                // Routing key determinística na DLQ — cada fila produtora tem sua DLQ
                .withArgument("x-dead-letter-routing-key", QUEUE_RESERVE_FUNDS_DLQ)
                .build();
    }

    /**
     * Dead Letter Queue para {@link com.vibranium.contracts.commands.wallet.ReserveFundsCommand}.
     *
     * <p>Declarada como fila simples durable — sem TTL nem requeue automático.
     * Mensagens aqui representam falhas permanentes que exigem análise humana ou
     * reprocessamento manual supervisionado.</p>
     *
     * <p>Não introduz lógica de retry para evitar loops infinitos ("retry storm")
     * em mensagens com estado de wallet genuinamente inválido (poison pill).</p>
     */
    @Bean
    public Queue reserveFundsDlQueue() {
        return QueueBuilder.durable(QUEUE_RESERVE_FUNDS_DLQ).build();
    }

    /**
     * Fila dedicada para {@link com.vibranium.contracts.commands.wallet.ReleaseFundsCommand}.
     *
     * <p>Configurada com DLQ para garantir que nenhuma mensagem de liberação seja
     * perdida silenciosamente — fundos bloqueados indevidamente exigem rastreabilidade.</p>
     */
    @Bean
    public Queue releaseFundsQueue() {
        return QueueBuilder.durable(QUEUE_RELEASE_FUNDS)
                .withArgument("x-dead-letter-exchange", DLQ_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", QUEUE_RELEASE_FUNDS_DLQ)
                .build();
    }

    /**
     * Dead Letter Queue para {@link com.vibranium.contracts.commands.wallet.ReleaseFundsCommand}.
     * Mensagens aqui indicam compensação incompleta — requer intervenção operacional.
     */
    @Bean
    public Queue releaseFundsDlQueue() {
        return QueueBuilder.durable(QUEUE_RELEASE_FUNDS_DLQ).build();
    }

    // -------------------------------------------------------------------------
    // Bindings
    // -------------------------------------------------------------------------

    /**
     * Liga a exchange do Keycloak à fila de eventos.
     * O wildcard {@code KK.EVENT.CLIENT.#} captura todos os eventos de todos os realms.
     */
    @Bean
    public Binding keycloakEventsBinding(
            @Qualifier("walletKeycloakEventsQueue") Queue walletKeycloakEventsQueue,
            @Qualifier("keycloakEventsExchange")    TopicExchange keycloakEventsExchange) {
        return BindingBuilder
                .bind(walletKeycloakEventsQueue)
                .to(keycloakEventsExchange)
                .with("KK.EVENT.CLIENT.#");
    }

    /**
     * Liga o exchange DLX à Dead Letter Queue de eventos Keycloak.
     *
     * <p>O {@link DirectExchange} {@value DLQ_EXCHANGE} roteia via routing key exata.
     * A routing key {@value QUEUE_KEYCLOAK_EVENTS_DLQ} corresponde ao valor declarado
     * em {@code x-dead-letter-routing-key} da fila {@value QUEUE_KEYCLOAK_EVENTS},
     * garantindo que apenas mensagens mortas desta fila específica cheguem aqui.</p>
     */
    @Bean
    public Binding walletKeycloakEventsDlqBinding(
            @Qualifier("walletKeycloakEventsDlQueue") Queue walletKeycloakEventsDlQueue,
            @Qualifier("dlqExchange")                 DirectExchange dlqExchange) {
        return BindingBuilder
                .bind(walletKeycloakEventsDlQueue)
                .to(dlqExchange)
                .with(QUEUE_KEYCLOAK_EVENTS_DLQ);
    }

    /**
     * Liga a exchange de comandos à fila genérica de comandos para
     * routing key exata {@code wallet.command.settle-funds}.
     *
     * <p>A routing key é agora específica (não mais {@code wallet.command.#}) para
     * evitar que mensagens de {@code reserve-funds} sejam entregues em <b>ambas</b>
     * as filas ({@code wallet.commands} e {@code wallet.commands.reserve-funds}),
     * o que causaria duplicidade de processamento.</p>
     */
    @Bean
    public Binding walletCommandsBinding(
            @Qualifier("walletCommandsQueue")    Queue walletCommandsQueue,
            @Qualifier("walletCommandsExchange") TopicExchange walletCommandsExchange) {
        return BindingBuilder
                .bind(walletCommandsQueue)
                .to(walletCommandsExchange)
                .with("wallet.command.settle-funds");
    }

    /**
     * Liga {@code vibranium.commands} (exchange usada pelo order-service) à fila de
     * reserva de fundos com routing key {@code wallet.commands.reserve-funds}.
     *
     * <p>Alinhado com o publicador: {@code OrderCommandService} usa
     * {@code RabbitMQConfig.COMMANDS_EXCHANGE} ("vibranium.commands") e
     * routing key {@code RabbitMQConfig.QUEUE_RESERVE_FUNDS} ("wallet.commands.reserve-funds").</p>
     */
    @Bean
    public Binding reserveFundsQueueBinding(
            @Qualifier("reserveFundsQueue")         Queue reserveFundsQueue,
            @Qualifier("vibraniumCommandsExchange") DirectExchange vibraniumCommandsExchange) {
        return BindingBuilder
                .bind(reserveFundsQueue)
                .to(vibraniumCommandsExchange)
                .with("wallet.commands.reserve-funds");
    }

    /**
     * Liga {@code vibranium.commands} à fila de liberação de fundos com routing key
     * {@code wallet.commands.release-funds}.
     *
     * <p>Alinhado com o publicador: {@code SagaTimeoutCleanupJob} e
     * {@code FundsSettlementFailedEventConsumer} usam routing key
     * {@code RabbitMQConfig.QUEUE_RELEASE_FUNDS} ("wallet.commands.release-funds").</p>
     */
    @Bean
    public Binding releaseFundsQueueBinding(
            @Qualifier("releaseFundsQueue")         Queue releaseFundsQueue,
            @Qualifier("vibraniumCommandsExchange") DirectExchange vibraniumCommandsExchange) {
        return BindingBuilder
                .bind(releaseFundsQueue)
                .to(vibraniumCommandsExchange)
                .with("wallet.commands.release-funds");
    }

    /**
     * Liga o exchange DLX à Dead Letter Queue de liberação de fundos.
     */
    @Bean
    public Binding releaseFundsDlqBinding(
            @Qualifier("releaseFundsDlQueue") Queue releaseFundsDlQueue,
            @Qualifier("dlqExchange")         DirectExchange dlqExchange) {
        return BindingBuilder
                .bind(releaseFundsDlQueue)
                .to(dlqExchange)
                .with(QUEUE_RELEASE_FUNDS_DLQ);
    }

    /**
     * Liga o exchange DLX à Dead Letter Queue de reserva de fundos.
     *
     * <p>O {@link DirectExchange} {@value DLQ_EXCHANGE} roteia via routing key exata.
     * A routing key {@value QUEUE_RESERVE_FUNDS_DLQ} corresponde ao valor declarado
     * em {@code x-dead-letter-routing-key} da fila {@value QUEUE_RESERVE_FUNDS},
     * garantindo que apenas mensagens mortas desta fila específica cheguem aqui.</p>
     */
    @Bean
    public Binding reserveFundsDlqBinding(
            @Qualifier("reserveFundsDlQueue") Queue reserveFundsDlQueue,
            @Qualifier("dlqExchange")         DirectExchange dlqExchange) {
        return BindingBuilder
                .bind(reserveFundsDlQueue)
                .to(dlqExchange)
                .with(QUEUE_RESERVE_FUNDS_DLQ);
    }

    // -------------------------------------------------------------------------
    // Infraestrutura de mensagens
    // -------------------------------------------------------------------------

    /**
     * Conversor de mensagens Jackson. Serializa/deserializa payloads como JSON
     * com suporte a Java Time API (Instant, LocalDate, etc.).
     *
     * <p>O Spring Boot {@code RabbitAutoConfiguration} detecta automaticamente
     * este bean via {@code ObjectProvider<MessageConverter>} e o injeta no
     * {@code RabbitTemplate} auto-configurado — sem necessidade de redefinir
     * {@code RabbitTemplate} manualmente.</p>
     */
    @Bean
    public Jackson2JsonMessageConverter messageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    // -------------------------------------------------------------------------
    // AT-14.1 — Propagação W3C traceparent em mensagens AMQP
    // -------------------------------------------------------------------------

    /**
     * AT-14.1 — Injeta {@link ObservationRegistry} real no {@link RabbitTemplate} via reflexão
     * e registra um {@link io.springframework.amqp.core.MessagePostProcessor} que constrói o
     * header W3C {@code traceparent} diretamente do {@link TraceContext}.
     *
     * <p><strong>Problema:</strong> {@code RabbitAutoConfiguration} não injeta
     * {@code ObservationRegistry} no {@code RabbitTemplate} automaticamente.
     * Adicionalmente, o {@code OtelPropagator} pode estar configurado com um
     * {@code ContextPropagators} vazio ({@code fields()=[]}), tornando
     * {@code propagator.inject()} uma operação sem efeito.</p>
     *
     * <p><strong>Solução:</strong> o {@link SmartInitializingSingleton} é executado após todos
     * os beans do contexto estarem prontos. Ele:</p>
     * <ol>
     *   <li>Registra {@link PropagatingSenderTracingObservationHandler} no registry.</li>
     *   <li>Injeta o registry non-NOOP no {@code RabbitTemplate} via reflexão.</li>
     *   <li>Registra um {@code MessagePostProcessor} que constrói {@code traceparent}
     *       manualmente a partir do {@link TraceContext} — contornando o {@code OtelPropagator}
     *       potencialmente mal-configurado.</li>
     * </ol>
     */
    @Bean
    SmartInitializingSingleton rabbitTemplateObservationSetup(
            RabbitTemplate rabbitTemplate,
            ObservationRegistry observationRegistry,
            Tracer tracer,
            Propagator propagator) {
        return () -> {
            // ── 1. Pipeline de Observation: injeta ObservationRegistry real via reflexão ──
            try {
                observationRegistry.observationConfig()
                        .observationHandler(new PropagatingSenderTracingObservationHandler<>(tracer, propagator));

                Field registryField = RabbitAccessor.class.getDeclaredField("observationRegistry");
                registryField.setAccessible(true);
                registryField.set(rabbitTemplate, observationRegistry);

                Field obtainedField = RabbitTemplate.class.getDeclaredField("observationRegistryObtained");
                obtainedField.setAccessible(true);
                obtainedField.set(rabbitTemplate, true);

                logger.info("AT-14.1: ObservationRegistry + PropagatingTracingObservationHandler configurados no RabbitTemplate");
            } catch (NoSuchFieldException | IllegalAccessException e) {
                logger.warn("AT-14.1: Falha ao configurar observação via reflexão no RabbitTemplate", e);
            }

            // ── 2. MessagePostProcessor: garante traceparent W3C independente do pipeline ──
            // Constrói o header traceparent diretamente do TraceContext, contornando o
            // OtelPropagator cujo ContextPropagators pode estar vazio.
            // Formato W3C: "00-{traceId:32hex}-{spanId:16hex}-{01=sampled|00=not-sampled}"
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

}
