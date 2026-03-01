package com.vibranium.walletservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibranium.utils.jackson.VibraniumJacksonConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Configuração do ObjectMapper Jackson do wallet-service.
 *
 * <p>Delega para {@link VibraniumJacksonConfig} — configuração unificada da plataforma
 * (US-007, Subtask 7.6). Garante consistência de serialização entre order-service
 * e wallet-service: ISO-8601 como padrão, com {@code FAIL_ON_UNKNOWN_PROPERTIES=false}
 * para tolerância à evolução de contratos de eventos.</p>
 *
 * <p>O bean é declarado explicitamente (não via {@code JacksonAutoConfiguration})
 * para garantir disponibilidade antes do {@link RabbitMQConfig} ser processado.</p>
 */
@Configuration
public class JacksonConfig {

    /**
     * ObjectMapper principal do wallet-service, configurado via utilitário centralizado.
     *
     * @return ObjectMapper configurado com JavaTimeModule, ISO-8601 e tolerância a campos extras
     */
    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        return VibraniumJacksonConfig.configure(new ObjectMapper());
    }
}
