package com.vibranium.orderservice.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Configuração do ObjectMapper Jackson para o order-service.
 *
 * <p>Garante que {@code Instant} seja serializado como epoch-millis (long integer),
 * conforme exigido pelo contrato da interface {@link com.vibranium.contracts.events.DomainEvent}
 * ({@code @JsonFormat(shape = JsonFormat.Shape.NUMBER_INT)} nos records).</p>
 *
 * <p>Esta configuração é referenciada pelo {@link RabbitMQConfig} para o
 * {@code Jackson2JsonMessageConverter} e por toda a stack HTTP (Spring MVC).</p>
 */
@Configuration
public class JacksonConfig {

    /**
     * ObjectMapper com suporte a Java 8 Time API (Instant, LocalDate, etc.).
     *
     * <ul>
     *   <li>{@code WRITE_DATES_AS_TIMESTAMPS=true} — Instant → epoch-millis (long)</li>
     *   <li>{@code WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS=false} — usa ms, não ns</li>
     *   <li>{@code READ_DATE_TIMESTAMPS_AS_NANOSECONDS=false} — lê ms ao desserializar</li>
     *   <li>{@code FAIL_ON_UNKNOWN_PROPERTIES=false} — tolerante a campos extras (evolução)</li>
     * </ul>
     */
    @Bean
    @Primary
    ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .enable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(SerializationFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS)
                .disable(DeserializationFeature.READ_DATE_TIMESTAMPS_AS_NANOSECONDS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
