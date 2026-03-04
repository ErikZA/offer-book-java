package com.vibranium.walletservice.security;

import com.nimbusds.jwt.JWTParser;
import com.nimbusds.jwt.JWTClaimsSet;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Configuração de segurança exclusiva para o perfil {@code e2e}.
 *
 * <p>Substitui o {@link SecurityConfig} (que usa {@code @Profile("!e2e")}) quando
 * a suíte de testes E2E está em execução. Comportamento:</p>
 * <ul>
 *   <li>Usa um {@link JwtDecoder} que parseia JWTs sem verificar assinatura.</li>
 *   <li>Libera o endpoint {@code /e2e/**} para que o {@code E2eDataSeederController}
 *       possa criar wallets e depositar fundos sem autenticação.</li>
 *   <li>Demais endpoints requerem token (necessário para popular
 *       {@code @AuthenticationPrincipal Jwt jwt} nos controllers).</li>
 * </ul>
 *
 * <p><strong>NUNCA ative o perfil {@code e2e} em produção.</strong></p>
 */
@Configuration
@EnableWebSecurity
@Profile("e2e")
public class E2eSecurityConfig {

    /**
     * JwtDecoder que parseia qualquer JWT sem validar a assinatura.
     *
     * <p>Permite que o {@code SagaEndToEndIT} crie tokens simples (alg=none)
     * com qualquer {@code sub} claim sem precisar de Keycloak.</p>
     */
    @Bean
    public JwtDecoder e2eJwtDecoder() {
        return rawToken -> {
            try {
                com.nimbusds.jwt.JWT parsed = JWTParser.parse(rawToken);
                JWTClaimsSet claimsSet = parsed.getJWTClaimsSet();

                Map<String, Object> headers = new HashMap<>();
                headers.put("alg", parsed.getHeader().getAlgorithm().getName());
                headers.put("typ", "JWT");

                Map<String, Object> claims = new HashMap<>(claimsSet.getClaims());

                Instant issuedAt = claimsSet.getIssueTime() != null
                        ? claimsSet.getIssueTime().toInstant()
                        : Instant.now();
                Instant expiresAt = claimsSet.getExpirationTime() != null
                        ? claimsSet.getExpirationTime().toInstant()
                        : Instant.now().plusSeconds(3600);

                return new Jwt(rawToken, issuedAt, expiresAt, headers, claims);

            } catch (Exception e) {
                throw new BadJwtException(
                        "E2E JwtDecoder: falha ao parsear token: " + e.getMessage(), e);
            }
        };
    }

    /**
     * SecurityFilterChain do perfil e2e.
     */
    @Bean
    public SecurityFilterChain e2eSecurityFilterChain(HttpSecurity http, JwtDecoder e2eJwtDecoder)
            throws Exception {
        return http
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        // Endpoints de setup E2E — sem autenticação
                        .requestMatchers(HttpMethod.POST, "/e2e/**").permitAll()
                        // Actuator para health checks do docker-compose
                        .requestMatchers("/actuator/**").permitAll()
                        // Demais endpoints requerem token (parseado sem validação)
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.decoder(e2eJwtDecoder)))
                .build();
    }
}
