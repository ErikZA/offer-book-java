package com.vibranium.walletservice.security;

import com.vibranium.walletservice.AbstractIntegrationTest;
import com.vibranium.walletservice.domain.model.Wallet;
import com.vibranium.walletservice.domain.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AT-10.3 — Testes de segurança do {@code SecurityFilterChain} do wallet-service.
 *
 * <h2>Por que segurança deve ser testada?</h2>
 * <p>Falhas de segurança raramente surgem de alterações intencionais; elas emergem de
 * <em>regressões silenciosas</em>: um desenvolvedor renomeia um método, muda uma
 * dependência ou adiciona um novo filtro — e a proteção desaparece sem que nenhum
 * teste de negócio falhe. Sem cobertura explícita do {@code SecurityFilterChain}, a
 * próxima breaking change pode ir ao ar sem aviso.</p>
 *
 * <h2>Riscos de regressão silenciosa</h2>
 * <ul>
 *   <li><b>Remoção acidental de {@code .authenticated()}:</b> todos os endpoints passam
 *       a aceitar requisições sem token — não detectado por testes funcionais.</li>
 *   <li><b>Troca de {@code oauth2ResourceServer} por {@code formLogin}:</b> a validação JWT
 *       é desativada silenciosamente.</li>
 *   <li><b>Override de configuração em perfil de teste:</b> {@code permitAll()} inserido
 *       em teste mascarando falha real de segurança.</li>
 * </ul>
 *
 * <h2>Papel do Spring Security Test</h2>
 * <p>O módulo {@code spring-security-test} oferece dois mecanismos complementares:</p>
 * <ol>
 *   <li>{@link org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors#jwt() jwt()}
 *       — injeta um {@code JwtAuthenticationToken} real no {@code SecurityContext} sem passar
 *       pelo {@code JwtDecoder}. Permite testar <em>autorização</em> (regras de ownership)
 *       sem depender de um Keycloak ativo.</li>
 *   <li>{@link MockBean} {@link JwtDecoder} — substitui o decoder auto-configurado por um
 *       mock Mockito. Permite simular erros de <em>autenticação</em> (token expirado, assinatura
 *       inválida) controlando o que o {@code BearerTokenAuthenticationFilter} vê.</li>
 * </ol>
 *
 * <h2>Cenários cobertos (TDD — FASE RED → GREEN)</h2>
 * <ol>
 *   <li><b>Sem token → 401:</b> A regra {@code .anyRequest().authenticated()} bloqueia
 *       requisições anônimas. RED: sem {@code SecurityConfig} retornaria 200.</li>
 *   <li><b>Token expirado → 401:</b> O {@code BearerTokenAuthenticationFilter} extrai o Bearer,
 *       chama {@code JwtDecoder.decode()}, que lança {@code JwtValidationException}; o filtro
 *       delega ao {@code BearerTokenAuthenticationEntryPoint} → 401.
 *       RED: sem validação JWT retornaria 200.</li>
 *   <li><b>Token de outro usuário → 403:</b> Autenticado mas sem ownership → IDOR bloqueado.
 *       RED: sem verificação de {@code jwt.sub} retornaria 200.</li>
 *   <li><b>Token do owner → 200:</b> Acesso legítimo liberado sem regressão funcional.</li>
 * </ol>
 *
 * <h2>Estratégia {@code @MockBean JwtDecoder}</h2>
 * <p>O {@link MockBean} substitui o bean auto-configurado pelo Spring, fazendo com que o
 * {@code BearerTokenAuthenticationFilter} use o mock ao receber um Bearer header real.
 * O {@code jwt()} post-processor <em>bypassa</em> este decoder completamente — portanto,
 * os dois mecanismos não interferem entre si nos cenários 3 e 4.</p>
 *
 * <p>O {@code @MockBean} força a criação de um {@code ApplicationContext} dedicado a esta
 * classe, isolando o mock dos demais testes de integração.</p>
 *
 * @see com.vibranium.walletservice.security.SecurityConfig
 * @see SecurityUnauthorizedTest AT-10.1 — requisições sem token
 * @see WalletOwnershipTest AT-10.2 — verificação de ownership (IDOR)
 */
@AutoConfigureMockMvc
@DisplayName("[AT-10.3] WalletSecurityIntegrationTest — cobertura de segurança do SecurityFilterChain")
class WalletSecurityIntegrationTest extends AbstractIntegrationTest {

    /**
     * MockMvc com o {@link com.vibranium.walletservice.security.SecurityConfig} real ativo.
     * Todas as requisições percorrem a cadeia completa de filtros de segurança.
     */
    @Autowired
    private MockMvc mockMvc;

    /**
     * Mock do {@link JwtDecoder} auto-configurado pelo Spring.
     *
     * <p>Permite simular rejeições de token (expirado, assinatura inválida) sem necessidade
     * de um JWT real assinado pelo Keycloak. O {@code BearerTokenAuthenticationFilter} usa
     * este bean ao processar requisições com cabeçalho {@code Authorization: Bearer <token>}.</p>
     *
     * <p>O {@code jwt()} post-processor dos cenários 3 e 4 <b>não</b> invoca este decoder —
     * ele injeta o {@code JwtAuthenticationToken} diretamente no {@code SecurityContext}.</p>
     */
    @MockBean
    private JwtDecoder jwtDecoder;

    @Autowired
    private WalletRepository walletRepository;

    /** UUID do dono legítimo da carteira em cada cenário. */
    private UUID ownerId;

    /** UUID de outro usuário (sem acesso à carteira do owner). */
    private UUID otherUserId;

    /** Carteira pertencente ao {@code ownerId} — alvo dos testes de segurança. */
    private Wallet ownerWallet;

    @BeforeEach
    void criarCarteiraDeTest() {
        walletRepository.deleteAll();
        ownerId = UUID.randomUUID();
        otherUserId = UUID.randomUUID();

        // Carteira do owner com saldo inicial suficiente para os testes de crédito/débito
        ownerWallet = walletRepository.save(
                Wallet.create(ownerId, new BigDecimal("500.00"), BigDecimal.ZERO)
        );
    }

    // =========================================================================
    // Cenário 1 — Autenticação: sem token → 401 Unauthorized
    // =========================================================================

    /**
     * Valida que a regra {@code .anyRequest().authenticated()} bloqueia requisições anônimas.
     *
     * <p><b>TDD — FASE RED:</b> Antes de {@code AT-10.1}, sem {@code SecurityConfig} ativo,
     * o endpoint retornaria {@code 200 OK}. A ausência de um Bearer token nunca seria detectada.</p>
     *
     * <p>{@code @WithAnonymousUser} sobrepõe o {@code @WithMockUser} herdado de
     * {@link AbstractIntegrationTest}, garantindo que nenhuma autenticação pré-populada
     * interfira na simulação de requisição totalmente anônima.</p>
     */
    @Test
    @WithAnonymousUser
    @DisplayName("1. Sem token → 401 Unauthorized (SecurityFilterChain bloqueia acesso anônimo)")
    void shouldReturn401WhenNoToken() throws Exception {
        mockMvc.perform(
                patch("/api/v1/wallets/{id}/balance", ownerWallet.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"brlAmount\": 10.00}")
        )
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // Cenário 2 — Autenticação: token expirado → 401 Unauthorized
    // =========================================================================

    /**
     * Valida que um Bearer token expirado é rejeitado com 401.
     *
     * <p><b>Fluxo de execução:</b></p>
     * <ol>
     *   <li>A requisição chega com {@code Authorization: Bearer <token-expirado>}.</li>
     *   <li>O {@code BearerTokenAuthenticationFilter} extrai o token do header.</li>
     *   <li>Chama {@code jwtDecoder.decode(token)} — que o mock configura para lançar
     *       {@link JwtValidationException} (equivalente ao comportamento do
     *       {@code NimbusJwtDecoder} ao encontrar {@code exp} no passado).</li>
     *   <li>O filtro captura a {@code JwtValidationException}, limpa o
     *       {@code SecurityContext} e delega ao {@code BearerTokenAuthenticationEntryPoint}
     *       → {@code 401 Unauthorized} com {@code WWW-Authenticate: Bearer error="invalid_token"}.</li>
     * </ol>
     *
     * <p><b>TDD — FASE RED:</b> Sem validação de expiração no decoder, o token seria aceito
     * e a requisição processada normalmente. A regressão seria completamente invisível
     * para testes funcionais que usam {@code jwt()} post-processor.</p>
     *
     * <p>Nota: o valor do Bearer header ("expired-…") é irrelevante — o mock responde
     * com {@code JwtValidationException} para qualquer string via {@code anyString()}.</p>
     */
    @Test
    @DisplayName("2. Token expirado → 401 Unauthorized (BearerTokenAuthenticationFilter rejeita JWT inválido)")
    void shouldReturn401WhenTokenIsExpired() throws Exception {
        // Simula o NimbusJwtDecoder quando encontra um JWT com claim `exp` no passado.
        // JwtValidationException extends JwtException — capturada pelo BearerTokenAuthenticationFilter.
        when(jwtDecoder.decode(anyString()))
                .thenThrow(new JwtValidationException(
                        "JWT expirado: o token não é mais válido (exp claim no passado)",
                        List.of(new OAuth2Error(
                                "invalid_token",
                                "The JWT expired at 2020-01-01T00:00:00Z",
                                null))
                ));

        mockMvc.perform(
                patch("/api/v1/wallets/{id}/balance", ownerWallet.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"brlAmount\": 10.00}")
                        // Envia um Bearer header real — força o BearerTokenAuthenticationFilter
                        // a invocar jwtDecoder.decode(), diferente do jwt() post-processor
                        .header("Authorization", "Bearer expired-and-invalid-jwt-token")
        )
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // Cenário 3 — Autorização: token válido de outro usuário → 403 Forbidden
    // =========================================================================

    /**
     * Valida que um usuário autenticado não consegue acessar a carteira de outro usuário (IDOR).
     *
     * <p><b>Fluxo de execução:</b></p>
     * <ol>
     *   <li>{@code jwt()} injeta um {@code JwtAuthenticationToken} com {@code sub = otherUserId}.</li>
     *   <li>O {@code SecurityFilterChain} autentica o usuário — a requisição avança ao controller.</li>
     *   <li>O controller compara {@code jwt.sub} com {@code wallet.userId}: {@code otherUserId ≠ ownerId}.</li>
     *   <li>Nenhum claim {@code ROLE_ADMIN} presente → lança {@code ResponseStatusException(403)}.</li>
     * </ol>
     *
     * <p><b>TDD — FASE RED:</b> Antes de {@code AT-10.2}, o controller não verificava ownership.
     * Qualquer usuário autenticado acessava qualquer carteira → IDOR crítico sem detecção.</p>
     *
     * <p>O {@code jwt()} post-processor <b>não</b> aciona o {@code @MockBean JwtDecoder} —
     * ele injeta o token diretamente no {@link org.springframework.security.core.context.SecurityContext},
     * garantindo isolamento entre os cenários 2 e 3.</p>
     */
    @Test
    @DisplayName("3. Token de outro usuário → 403 Forbidden (ownership check bloqueia acesso cruzado)")
    void shouldReturn403WhenAccessingOtherUsersWallet() throws Exception {
        mockMvc.perform(
                patch("/api/v1/wallets/{id}/balance", ownerWallet.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"brlAmount\": 10.00}")
                        // jwt.sub = otherUserId ≠ ownerWallet.userId → 403
                        .with(jwt().jwt(builder -> builder.subject(otherUserId.toString())))
        )
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // Cenário 4 — Acesso legítimo: token do próprio owner → 200 OK
    // =========================================================================

    /**
     * Valida que o dono legítimo da carteira consegue modificar seu próprio saldo.
     *
     * <p>Este cenário garante que as validações de segurança dos cenários 1–3 não
     * quebraram o fluxo funcional principal. Sem este teste, um over-blocking
     * (403 para todos) passaria despercebido.</p>
     *
     * <p><b>Fluxo de execução:</b></p>
     * <ol>
     *   <li>{@code jwt()} injeta um {@code JwtAuthenticationToken} com {@code sub = ownerId}.</li>
     *   <li>O controller compara {@code jwt.sub} com {@code wallet.userId}: {@code ownerId == ownerId}.</li>
     *   <li>Autorização concedida → {@code walletService.adjustBalance()} é invocado normalmente.</li>
     *   <li>Retorna {@code 200 OK} com o {@code WalletResponse} atualizado.</li>
     * </ol>
     */
    @Test
    @DisplayName("4. Token do próprio owner → 200 OK (acesso legítimo liberado sem regressão)")
    void shouldReturn200WhenOwnerAccessesOwnWallet() throws Exception {
        mockMvc.perform(
                patch("/api/v1/wallets/{id}/balance", ownerWallet.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"brlAmount\": 50.00}")
                        // jwt.sub = ownerId == ownerWallet.userId → acesso liberado
                        .with(jwt().jwt(builder -> builder.subject(ownerId.toString())))
        )
                .andExpect(status().isOk());
    }
}
