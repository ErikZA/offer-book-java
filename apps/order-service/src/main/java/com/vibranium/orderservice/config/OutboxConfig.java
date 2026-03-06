package com.vibranium.orderservice.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;

/**
 * Configuração do módulo Outbox Publisher.
 *
 * <ul>
 *   <li>{@link EnableRetry}: habilita proxy AOP para {@code @Retryable}/{@code @Recover}
 *       no {@link com.vibranium.orderservice.application.service.OrderOutboxPublisherService}.</li>
 *   <li>{@link EnableConfigurationProperties}: registra {@link OutboxProperties} para
 *       binding do prefixo {@code app.outbox} no YAML.</li>
 * </ul>
 *
 * <p>{@code @EnableScheduling} já está declarado em
 * {@link com.vibranium.orderservice.OrderServiceApplication}.</p>
 */
@Configuration
@EnableRetry
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxConfig {
    // Classe de configuração declarativa — sem beans adicionais.
}
