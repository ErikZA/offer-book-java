package com.vibranium.walletservice.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Configuração de segurança do wallet-service como OAuth2 Resource Server.
 *
 * <h2>Defense-in-depth (AT-10.1)</h2>
 * <p>O wallet-service valida JWTs <em>independentemente</em> do Kong.
 * Sem esta configuração, um atacante com acesso à rede interna consegue
 * chamar qualquer endpoint sem autenticação (bypass total do gateway).</p>
 *
 * <h2>Validação JWT via Keycloak</h2>
 * <p>O {@code issuer-uri} em {@code application.yaml} aciona OIDC Discovery
 * automático ao iniciar a aplicação:</p>
 * <ol>
 *   <li>Spring Security chama {@code GET ${issuer-uri}/.well-known/openid-configuration}</li>
 *   <li>Extrai o {@code jwks_uri} do documento de descoberta</li>
 *   <li>Cria um {@link org.springframework.security.oauth2.jwt.JwtDecoder} com o JWKS endpoint</li>
 *   <li>Cada requisição com Bearer Token tem sua assinatura RS256 validada on-the-fly</li>
 * </ol>
 *
 * <h2>Por que stateless + CSRF desabilitado?</h2>
 * <p>REST APIs com JWT são stateless por natureza: o estado de autenticação está
 * no token, não em cookies de sessão HTTP. CSRF explora cookies de sessão para
 * forjar requisições — sem cookies de sessão, não há superfície de ataque CSRF.
 * Portanto, desabilitar CSRF é correto e seguro para este tipo de API.</p>
 *
 * <h2>Diferença entre gateway auth e service auth</h2>
 * <ul>
 *   <li><b>Kong (gateway):</b> valida JWT na borda da rede; bloqueia tráfego externo não autenticado.</li>
 *   <li><b>Resource Server (este config):</b> valida JWT no próprio microsserviço;
 *       garante autenticação mesmo para chamadas que chegam via rede interna, sem passar pelo Kong.</li>
 * </ul>
 *
 * <p>Configuração idêntica ao {@code order-service} — consistência arquitetural entre microsserviços.</p>
 */
@Configuration
@EnableWebSecurity
// AT-4.2.1: habilita anotações @PreAuthorize / @PostAuthorize nos controllers e services.
// Sem esta anotação, @PreAuthorize é ignorada silenciosamente pelo Spring Security.
@EnableMethodSecurity
// Ativo em todos os perfis exceto 'e2e', onde o E2eSecurityConfig assume o controle
@Profile("!e2e")
public class SecurityConfig {

    /**
     * Define as regras de autorização e o modo de validação de tokens para o wallet-service.
     *
     * <p>Política de endpoints:</p>
     * <ul>
     *   <li>{@code /actuator/health} e {@code /actuator/info} — públicos (Kong, Kubernetes, Prometheus).</li>
     *   <li>Todas as demais requisições — exigem Bearer Token JWT válido.</li>
     * </ul>
     *
     * @param http builder da cadeia de filtros de segurança.
     * @return cadeia de filtros configurada.
     * @throws Exception se a configuração do HttpSecurity falhar.
     */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                // Stateless: sem sessão HTTP (estado de autenticação está no JWT)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // CSRF desabilitado: API stateless com JWT — sem cookies de sessão,
                // não há superfície de ataque CSRF (ver Javadoc da classe)
                .csrf(AbstractHttpConfigurer::disable)

                // Regras de autorização por endpoint
                .authorizeHttpRequests(auth -> auth

                        // Actuator health/info: sem autenticação
                        // (Kong health check, Kubernetes liveness/readiness, Prometheus scrape)
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()

                        // Todas as demais rotas exigem usuário autenticado via JWT
                        .anyRequest().authenticated())

                // Resource Server JWT: valida Bearer Token usando o JWKS do Keycloak
                // O issuer-uri dispara OIDC Discovery automático (ver Javadoc da classe)
                .oauth2ResourceServer(oauth2 ->
                        oauth2.jwt(jwt -> { /* issuer-uri/jwk-set-uri vêm de application.yaml */ }))
                .build();
    }
}
