package com.vibranium.orderservice.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

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
// Ativo em todos os perfis exceto 'e2e' (usado apenas em testes — E2eSecurityConfig está em src/test)
@Profile("!e2e")
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

                        // AT-14: Endpoint de auditoria do Event Store — requer role ADMIN
                        .requestMatchers("/admin/**").hasAuthority("ROLE_ADMIN")

                        // Endpoint POST /api/v1/orders exige usuário autenticado
                        .requestMatchers(HttpMethod.POST, "/api/v1/orders").authenticated()

                        // Todas as demais requisições exigem autenticação por padrão
                        .anyRequest().authenticated())

                // Configuração do Resource Server JWT (valida via Keycloak JWKS)
                .oauth2ResourceServer(oauth2 ->
                        oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .build();
    }

    /**
     * Converte roles do Keycloak ({@code realm_access.roles}) em GrantedAuthority do Spring Security.
     *
     * <p>O Keycloak emite o JWT com a estrutura:</p>
     * <pre>{@code
     * { "realm_access": { "roles": ["ADMIN", "user"] } }
     * }</pre>
     *
     * <p>Este converter extrai as roles e as mapeia para {@code ROLE_ADMIN}, {@code ROLE_user}, etc.</p>
     */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
        return converter;
    }

    /**
     * Extrai roles de {@code realm_access.roles} do JWT Keycloak e converte para
     * {@link GrantedAuthority} com prefixo {@code ROLE_}.
     */
    static class KeycloakRealmRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {
        @Override
        @SuppressWarnings("unchecked")
        public Collection<GrantedAuthority> convert(Jwt jwt) {
            Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
            if (realmAccess == null || !realmAccess.containsKey("roles")) {
                return Collections.emptyList();
            }
            List<String> roles = (List<String>) realmAccess.get("roles");
            return roles.stream()
                    .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                    .toList();
        }
    }
}
