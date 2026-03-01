package com.vibranium.orderservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibranium.utils.jackson.VibraniumJacksonConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Configuração do ObjectMapper Jackson para o order-service.
 *
 * <p>Delega para {@link VibraniumJacksonConfig} — configuração unificada da plataforma
 * (US-007, Subtask 7.6). Isso resolve a divergência anterior onde o order-service
 * usava epoch-millis e o wallet-service usava ISO-8601.</p>
 *
 * <p>O padrão agora é ISO-8601 para ambos os serviços. Campos específicos que precisam
 * de epoch-millis (ex.: campos em {@code DomainEvent} Records) utilizam
 * {@code @JsonFormat(shape = JsonFormat.Shape.NUMBER_INT)} individualmente.</p>
 *
 * <p>Este bean é referenciado pelo {@link RabbitMQConfig} via
 * {@code Jackson2JsonMessageConverter} e por toda a stack HTTP (Spring MVC).</p>
 */
@Configuration
public class JacksonConfig {

    /**
     * ObjectMapper principal do order-service, configurado via utilitário centralizado.
 *
     * @return ObjectMapper configurado com JavaTimeModule, ISO-8601 e tolerância a campos extras
     */
    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        return VibraniumJacksonConfig.configure(new ObjectMapper());
    }
}
