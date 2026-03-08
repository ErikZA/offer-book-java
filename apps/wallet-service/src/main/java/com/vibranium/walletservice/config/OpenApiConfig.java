package com.vibranium.walletservice.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuração OpenAPI 3 para o wallet-service.
 *
 * <p>Registra o schema de segurança Bearer JWT para que o Swagger UI
 * permita enviar tokens de autenticação nas requisições de teste.</p>
 */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI walletServiceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Wallet Service API")
                        .description("API REST do microsserviço de carteiras — Vibranium Order Book Platform")
                        .version("1.0.0"))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("JWT RS256 emitido pelo Keycloak")));
    }
}
