package com.vibranium.walletservice.config;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuração do ObjectMapper Jackson da aplicação.
 *
 * <p>Customizações aplicadas:</p>
 * <ul>
 *   <li>{@code JavaTimeModule} — suporte a {@code Instant}, {@code LocalDate}, etc.</li>
 *   <li>{@code WRITE_DATES_AS_TIMESTAMPS = false} — serializa datas como ISO-8601 string
 *       (ex: {@code "2025-01-01T12:00:00Z"}) em vez de epoch numérico.</li>
 * </ul>
 *
 * <p>Usa {@link Jackson2ObjectMapperBuilderCustomizer} para integrar com a
 * auto-configuração do Spring Boot sem substituir o bean principal.</p>
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        return builder -> builder
                .modules(new JavaTimeModule())
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
