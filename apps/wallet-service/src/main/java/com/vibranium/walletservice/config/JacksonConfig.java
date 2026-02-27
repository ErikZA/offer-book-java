package com.vibranium.walletservice.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Configuração do ObjectMapper Jackson da aplicação.
 *
 * <p>Expõe um {@code @Bean @Primary ObjectMapper} explícito para garantir que o bean
 * esteja disponível independentemente da ordem de carregamento da auto-configuração
 * do Spring Boot ({@code JacksonAutoConfiguration}).</p>
 *
 * <p>Customizações aplicadas:</p>
 * <ul>
 *   <li>{@code JavaTimeModule} — suporte a {@code Instant}, {@code LocalDate}, etc.</li>
 *   <li>{@code WRITE_DATES_AS_TIMESTAMPS = false} — serializa datas como ISO-8601 string
 *       (ex: {@code "2025-01-01T12:00:00Z"}) em vez de epoch numérico.</li>
 *   <li>{@code FAIL_ON_UNKNOWN_PROPERTIES = false} — tolerante a campos extras (evolução de contratos).</li>
 * </ul>
 */
@Configuration
public class JacksonConfig {

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
