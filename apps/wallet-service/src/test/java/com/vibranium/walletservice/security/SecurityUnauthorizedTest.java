package com.vibranium.walletservice.security;

import com.vibranium.walletservice.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithAnonymousUser;
// AT-4.2.1: import para testar listAll com ROLE_ADMIN
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AT-10.1 — FASE RED → GREEN: Validação de segurança do wallet-service.
 *
 * <p>Princípio testado: <b>Defense-in-depth</b>. O wallet-service valida JWTs
 * independentemente do Kong — bypass via rede interna não pode acessar endpoints
 * sensíveis sem autenticação.</p>
 *
 * <h2>Ciclo TDD</h2>
 * <ul>
 *   <li><b>RED (antes de AT-10.1):</b> Sem {@code spring-boot-starter-security},
 *       endpoints retornam {@code 200} sem autenticação → testes falham.</li>
 *   <li><b>GREEN (após AT-10.1):</b> {@link com.vibranium.walletservice.config.SecurityConfig}
 *       ativo → sem token → {@code 401 Unauthorized} → testes passam.</li>
 * </ul>
 *
 * <h2>Estratégia de autenticação nos testes</h2>
 * <p>Esta classe estende {@link AbstractIntegrationTest} que declara {@code @WithMockUser}
 * a nível de classe (garante que os demais testes de integração continuem passando
 * com a segurança ativa).</p>
 *
 * <p>Para testar o comportamento SEM autenticação, cada método usa
 * {@code @WithAnonymousUser}, que <em>sobrepõe</em> o {@code @WithMockUser} herdado.
 * De acordo com a documentação do Spring Security Test, anotações de método
 * têm precedência sobre anotações de classe.</p>
 *
 * <h2>Por que {@code @SpringBootTest} e não {@code @WebMvcTest}?</h2>
 * <p>O wallet-service tem componentes de infraestrutura (AMQP listeners, Debezium, JPA)
 * que requerem um contexto completo. {@code @WebMvcTest} falha ao tentar carregar
 * beans de infraestrutura sem os datasources/brokers necessários. O contexto completo
 * com Testcontainers (via {@link AbstractIntegrationTest}) é mais robusto e evita
 * fragilidade de mocks de beans de infraestrutura.</p>
 */
@AutoConfigureMockMvc
@DisplayName("[RED → GREEN] AT-10.1 — Defense-in-depth: requisições sem token devem retornar 401")
class SecurityUnauthorizedTest extends AbstractIntegrationTest {

    /**
     * MockMvc configurado com o {@link com.vibranium.walletservice.config.SecurityConfig}
     * real. Todas as requisições passam pela cadeia de filtros de segurança.
     */
    @Autowired
    private MockMvc mockMvc;

    // =========================================================================
    // FASE RED → GREEN: endpoints de negócio sem token devem retornar 401
    // =========================================================================

    /**
     * Valida que {@code GET /api/v1/wallets} exige autenticação.
     *
     * <p>{@code @WithAnonymousUser} sobrepõe o {@code @WithMockUser} da superclasse,
     * simulando uma requisição sem token — exatamente o cenário de bypass do Kong.</p>
     */
    @Test
    @WithAnonymousUser
    @DisplayName("GET /api/v1/wallets sem token deve retornar 401 (endpoint sensível)")
    void listAll_semToken_deveRetornar401() throws Exception {
        mockMvc.perform(get("/api/v1/wallets"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET /api/v1/wallets/{userId} sem token deve retornar 401 (endpoint sensível)")
    void getByUserId_semToken_deveRetornar401() throws Exception {
        mockMvc.perform(get("/api/v1/wallets/{userId}",
                        "00000000-0000-0000-0000-000000000001"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("PATCH /api/v1/wallets/{walletId}/balance sem token deve retornar 401")
    void updateBalance_semToken_deveRetornar401() throws Exception {
        mockMvc.perform(
                        patch("/api/v1/wallets/{walletId}/balance",
                                "00000000-0000-0000-0000-000000000001")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"brlAmount\": 100.00}")
                )
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // Endpoints autenticados devem funcionar com token válido (@WithMockUser)
    // =========================================================================

    /**
     * Valida que com ROLE_ADMIN (simulado via {@code @WithMockUser(roles = "ADMIN")}),
     * o endpoint retorna {@code 200 OK} — segurança não bloqueia admins autenticados.
     *
     * <p>AT-4.2.1: {@code @PreAuthorize("hasRole('ADMIN')")} foi adicionado ao endpoint.
     * Usuários sem ROLE_ADMIN recebem {@code 403 Forbidden} (testado em
     * {@code WalletControllerIntegrationTest.testListAll_withoutAdminRole_returns403}).
     * Este teste verifica que ROLE_ADMIN continua sendo aceito sem bloqueio indevido.</p>
     */
    @Test
    // AT-4.2.1: endpoint agora exige ROLE_ADMIN — @WithMockUser da superclasse (ROLE_USER)
    // retornaria 403. Override com roles = "ADMIN" para manter a semântica do teste.
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /api/v1/wallets com ROLE_ADMIN deve retornar 200 (AT-4.2.1)")
    void listAll_comTokenValido_deveRetornar200() throws Exception {
        // @WithMockUser(roles = "ADMIN") garante que ROLE_ADMIN está no SecurityContext
        mockMvc.perform(get("/api/v1/wallets"))
                .andExpect(status().isOk());
    }

    // =========================================================================
    // Actuator — endpoints públicos mesmo sem token
    // =========================================================================

    /**
     * Valida que {@code /actuator/health} é público após ativação da segurança.
     * Requerido para Kong health check, Kubernetes liveness/readiness e Prometheus.
     */
    @Test
    @WithAnonymousUser
    @DisplayName("GET /actuator/health sem token deve retornar 200 (endpoint público)")
    void actuatorHealth_semToken_deveRetornar200() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    @WithAnonymousUser
    @DisplayName("GET /actuator/info sem token deve retornar 200 (endpoint público)")
    void actuatorInfo_semToken_deveRetornar200() throws Exception {
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isOk());
    }
}
