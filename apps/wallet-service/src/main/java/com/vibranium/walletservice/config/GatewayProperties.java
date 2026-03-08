package com.vibranium.walletservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Propriedades de configuração para controle de dependência do API Gateway.
 *
 * <p>Quando {@code app.gateway.enabled=false}, o serviço aceita chamadas diretas
 * sem headers específicos do Gateway (Kong). Usado em testes de performance
 * para eliminar o Gateway como gargalo.</p>
 *
 * @param enabled {@code true} se o tráfego passa pelo Gateway; {@code false} para chamadas diretas.
 */
@ConfigurationProperties(prefix = "app.gateway")
public record GatewayProperties(boolean enabled) {

    /**
     * Default: gateway habilitado (comportamento de produção).
     */
    public GatewayProperties() {
        this(true);
    }
}
