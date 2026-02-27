package com.vibranium.orderservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Configuração de segurança do order-service como OAuth2 Resource Server.
 *
 * <p>Valida tokens JWT RS256 emitidos pelo Keycloak.
 * O {@code issuer-uri} é configurado em {@code application.yaml} e usado
 * pelo Spring Security para descobrir automaticamente o JWKS endpoint
 * ({@code /realms/orderbook-realm/protocol/openid-connect/certs}).</p>
 *
 * <p>Política de sessão stateless: o estado fica no JWT; sem CSRF necessário.</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                // Stateless REST API: sem sessão HTTP (token no header)
                .sessionManagement(s ->
                        s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // CSRF desabilitado: API stateless + JWT; não há cookies de sessão para proteger
                .csrf(AbstractHttpConfigurer::disable)

                // Regras de autorização
                .authorizeHttpRequests(auth -> auth

                        // Endpoints de saúde/métricas acessíveis sem autenticação
                        // (Kong, Kubernetes health checks, Prometheus)
                        .requestMatchers("/actuator/**").permitAll()

                        // Endpoint POST /api/v1/orders exige usuário autenticado
                        .requestMatchers(HttpMethod.POST, "/api/v1/orders").authenticated()

                        // Todas as demais requisições exigem autenticação por padrão
                        .anyRequest().authenticated())

                // Configuração do Resource Server JWT (valida via Keycloak JWKS)
                .oauth2ResourceServer(oauth2 ->
                        oauth2.jwt(jwt -> { /* issuer-uri vem de application.yaml */ }))
                .build();
    }
}
