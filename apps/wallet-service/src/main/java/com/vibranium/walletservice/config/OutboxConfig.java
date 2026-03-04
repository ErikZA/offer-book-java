package com.vibranium.walletservice.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Configuração do módulo Outbox Publisher.
 *
 * <ul>
 *   <li>{@link EnableRetry}: habilita proxy AOP para {@code @Retryable}/{@code @Recover}.</li>
 *   <li>{@link EnableScheduling}: habilita o scheduler para o polling do Outbox.</li>
 *   <li>{@link EnableConfigurationProperties}: registra {@link OutboxProperties} para
 *       binding do prefixo {@code app.outbox} no YAML.</li>
 * </ul>
 */
@Configuration
@EnableRetry
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxConfig {
    // Classe de configuração declarativa — sem beans adicionais.
}
