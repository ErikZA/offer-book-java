package com.vibranium.walletservice.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;

/**
 * Configuração do módulo Outbox Publisher.
 *
 * <p>Responsabilidades desta classe de configuração:</p>
 * <ul>
 *   <li>{@link EnableRetry}: habilita o proxy AOP do Spring Retry para interceptar
 *       métodos anotados com {@link org.springframework.retry.annotation.Retryable}
 *       e {@link org.springframework.retry.annotation.Recover} no
 *       {@link com.vibranium.walletservice.infrastructure.outbox.OutboxPublisherService}.</li>
 *   <li>{@link EnableConfigurationProperties}: registra {@link OutboxProperties} para
 *       binding automático a partir do prefixo {@code app.outbox} no YAML.</li>
 * </ul>
 *
 * <p>Separada da classe principal ({@code WalletServiceApplication}) para seguir
 * o princípio de responsabilidade única: cada módulo declara suas próprias
 * necessidades de configuração.</p>
 */
@Configuration
@EnableRetry
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxConfig {
    // Classe de configuração declarativa — sem beans adicionais.
    // O DebeziumOutboxEngine é registrado como @Component com @ConditionalOnProperty.
}
