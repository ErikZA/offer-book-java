package com.vibranium.utils.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.PropagatingSenderTracingObservationHandler;
import io.micrometer.tracing.propagation.Propagator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.RabbitAccessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;

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
}
