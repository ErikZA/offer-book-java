package com.vibranium.utils.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.PropagatingSenderTracingObservationHandler;
import io.micrometer.tracing.propagation.Propagator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.RabbitAccessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.amqp.support.converter.SimpleMessageConverter;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Field;

/**
 * Classe abstrata base para configuração do RabbitMQ.
 * Centraliza comportamentos comuns como conversão JSON e propagação de tracing (W3C).
 */
public abstract class BaseRabbitMQConfig {

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    // -------------------------------------------------------------------------
    // Constantes de Exchanges Compartilhadas
    // -------------------------------------------------------------------------

    public static final String VIBRANIUM_COMMANDS_EXCHANGE = "vibranium.commands";
    public static final String VIBRANIUM_EVENTS_EXCHANGE   = "vibranium.events";
    public static final String VIBRANIUM_DLQ_EXCHANGE      = "vibranium.dlq";
    public static final String AMQ_TOPIC_EXCHANGE          = "amq.topic";
    public static final String WALLET_COMMANDS_EXCHANGE    = "wallet.commands";

    /**
     * Conversor de mensagens Jackson (JSON).
     * Suporta records e Java Time API (Instant, etc.).
     */
    @Bean
    public Jackson2JsonMessageConverter messageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    /**
     * Configuração de Observabilidade (AT-14.1).
     * Garante a propagação do header W3C 'traceparent' em todas as mensagens publicadas.
     */
    @Bean
    public SmartInitializingSingleton rabbitTemplateObservationSetup(
            RabbitTemplate rabbitTemplate,
            ObservationRegistry observationRegistry,
            Tracer tracer,
            Propagator propagator) {
        return () -> {
            // 1. Pipeline de Observation: injeta ObservationRegistry via reflexão
            try {
                observationRegistry.observationConfig()
                        .observationHandler(new PropagatingSenderTracingObservationHandler<>(tracer, propagator));

                Field registryField = RabbitAccessor.class.getDeclaredField("observationRegistry");
                registryField.setAccessible(true);
                registryField.set(rabbitTemplate, observationRegistry);

                Field obtainedField = RabbitTemplate.class.getDeclaredField("observationRegistryObtained");
                obtainedField.setAccessible(true);
                obtainedField.set(rabbitTemplate, true);

                logger.info("BaseConfig: ObservationRegistry configurado no RabbitTemplate");
            } catch (NoSuchFieldException | IllegalAccessException e) {
                logger.warn("BaseConfig: Falha ao configurar observação via reflexão", e);
            }

            // 2. MessagePostProcessor: garante traceparent W3C
            rabbitTemplate.addBeforePublishPostProcessors(message -> {
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
                }
                span.end();
                return message;
            });
            logger.info("BaseConfig: MessagePostProcessor W3C registrado");
        };
    }

    // -------------------------------------------------------------------------
    // Listener Container Factory — ACK Manual (idempotencia por tabela)
    // -------------------------------------------------------------------------

    /**
     * Container factory com {@link AcknowledgeMode#MANUAL} para os consumers que
     * utilizam idempotencia por tabela ({@link com.vibranium.orderservice.infrastructure.messaging.FundsReservedEventConsumer}
     * e {@link com.vibranium.orderservice.infrastructure.messaging.FundsReservationFailedEventConsumer}).
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
        // N ultrapassar 10 para settlement (ordering financeiro).
        factory.setPrefetchCount(10);
        // Listeners individuais podem sobrescrever via @RabbitListener(concurrency="N").
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(5);
        return factory;
    }

    // -------------------------------------------------------------------------
    // Listener Container Factory — ACK Automático (projeção MongoDB — Query Side)
    // -------------------------------------------------------------------------

    /**
     * Container factory com {@link AcknowledgeMode#AUTO} para os consumers de projeção
     * ({@link com.vibranium.orderservice.application.query.consumer.OrderEventProjectionConsumer}).
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
     * ({@link com.vibranium.orderservice.application.query.service.OrderAtomicHistoryWriter})
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
        // AT-09: prefetch=50 para filas de projeção — leitura idempotente permite
        // maior throughput sem risco de inconsistência.
        factory.setPrefetchCount(50);
        // AT-09: auto-escala de 1 a 5 threads para projeção.
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(5);
        // AT-2.2.1: requeue=false em caso de exceção qualquer (não somente MessageConversionException).
        // Sem este flag, NPEs e outros erros de runtime causariam loop infinito ao recolocar a
        // mensagem na fila indefinidamente. Com false, a mensagem é rejeitada e roteada para
        // a DLX configurada em cada fila de projeção.
        factory.setDefaultRequeueRejected(false);
        return factory;
    }

    // -------------------------------------------------------------------------
    // Container Factory — Raw Message (sem conversão Jackson para Keycloak)
    // -------------------------------------------------------------------------

    /**
     * Container factory que utiliza {@link SimpleMessageConverter} em vez de
     * {@link Jackson2JsonMessageConverter}, entregando a mensagem AMQP crua
     * ao listener sem tentar desserializar o payload.
     *
     * <p><b>Motivação:</b> o plugin {@code aznamier/keycloak-event-listener-rabbitmq}
     * seta o header {@code __TypeId__} com a classe
     * {@code com.github.aznamier.keycloak.event.provider.EventClientNotificationMqMsg},
     * que não existe no classpath do order-service. O {@link Jackson2JsonMessageConverter}
     * global tenta resolver essa classe e lança {@code MessageConversionException}
     * ("Class not found") <b>antes</b> do handler method ser invocado.</p>
     *
     * <p>O {@link SimpleMessageConverter} ignora completamente o {@code __TypeId__}
     * e entrega os bytes brutos, permitindo que o
     * {@link com.vibranium.orderservice.infrastructure.messaging.KeycloakEventConsumer}
     * faça o parse manual com {@code ObjectMapper}.</p>
     *
     * @param connectionFactory Conexão AMQP gerenciada pelo Spring Boot.
     */
    @Bean
    SimpleRabbitListenerContainerFactory rawMessageContainerFactory(
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        // SimpleMessageConverter: não tenta resolver __TypeId__ nem desserializar JSON
        factory.setMessageConverter(new SimpleMessageConverter());
        // MANUAL: alinhado com ACK/NACK explícito no KeycloakEventConsumer
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setPrefetchCount(10);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(5);
        return factory;
    }

    // -------------------------------------------------------------------------
    // TransactionTemplate — Saga TCC (AT-2.1.1)
    // -------------------------------------------------------------------------

    /**
     * {@link TransactionTemplate} utilizado pelo
     * {@link com.vibranium.orderservice.infrastructure.messaging.FundsReservedEventConsumer}
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
