package com.vibranium.orderservice.security;

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
 * a suíte de testes E2E está em execução. Principais diferenças:</p>
 * <ul>
 *   <li>Permite todas as requisições sem validação de assinatura JWT.</li>
 *   <li>Usa um {@link JwtDecoder} que parseia o token sem verificar a assinatura —
 *       necessário para que {@code @AuthenticationPrincipal Jwt jwt} seja populado
 *       com o {@code sub} claim nos controllers.</li>
 *   <li>Também libera o endpoint {@code /e2e/setup} usado pelo {@code E2eDataSeederController}
 *       para pré-configurar os dados de teste.</li>
 * </ul>
 *
 * <p><strong>NUNCA ative o perfil {@code e2e} em ambientes não-test.</strong>
 * Este bean é um vetor de segurança intencional para testes E2E e não deve ser
 * incluído em builds de produção.</p>
 */
@Configuration
@EnableWebSecurity
@Profile("e2e")
public class E2eSecurityConfig {

    /**
     * JwtDecoder que parseia qualquer JWT sem validar a assinatura.
     *
     * <p>Aceita tokens com algoritmo {@code none} (unsigned) gerados pelo
     * {@code SagaEndToEndIT} para simular usuários B e S sem Keycloak.</p>
     *
     * <p>O decoder delega o parse para o {@link JWTParser} do Nimbus JWT
     * (já presente no classpath via {@code spring-security-oauth2-jose}) e
     * constrói um {@link Jwt} Spring a partir dos claims sem verificar MAC/RSA.</p>
     */
    @Bean
    public JwtDecoder e2eJwtDecoder() {
        return rawToken -> {
            try {
                com.nimbusds.jwt.JWT parsed = JWTParser.parse(rawToken);
                JWTClaimsSet claimsSet = parsed.getJWTClaimsSet();

                // Headers mínimos exigidos pelo construtor de Jwt do Spring
                Map<String, Object> headers = new HashMap<>();
                headers.put("alg", parsed.getHeader().getAlgorithm().getName());
                headers.put("typ", "JWT");

                // Converte todos os claims para o mapa do Spring Jwt
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
                        "E2E JwtDecoder: falha ao parsear token (sem verificação): " + e.getMessage(), e);
            }
        };
    }

    /**
     * SecurityFilterChain do perfil e2e: permite tudo, mas mantém o Resource Server
     * JWT ativo para que o {@code @AuthenticationPrincipal Jwt} seja injetado.
     */
    @Bean
    public SecurityFilterChain e2eSecurityFilterChain(HttpSecurity http, JwtDecoder e2eJwtDecoder)
            throws Exception {
        return http
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        // Endpoints de setup E2E — usados pelo SagaEndToEndIT em @BeforeAll
                        .requestMatchers(HttpMethod.POST, "/e2e/**").permitAll()
                        // Actuator acessível sem auth (health checks do compose)
                        .requestMatchers("/actuator/**").permitAll()
                        // Todos os demais endpoints requerem token (parseado sem validação)
                        .anyRequest().authenticated())
                // OAuth2 Resource Server com decoder E2e (sem verificação de assinatura)
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.decoder(e2eJwtDecoder)))
                .build();
    }
}
