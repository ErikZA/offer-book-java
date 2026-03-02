package com.vibranium.walletservice.security;

import com.vibranium.walletservice.AbstractIntegrationTest;
import com.vibranium.walletservice.domain.model.Wallet;
import com.vibranium.walletservice.domain.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AT-10.2 — FASE RED → GREEN: Verificação de propriedade de recurso (Resource Ownership).
 *
 * <h2>Problema testado: Autorização horizontal (IDOR)</h2>
 * <p>Sem este controle, o Usuário A com um JWT válido consegue acessar
 * e modificar a carteira do Usuário B. Isso configura uma violação de
 * autorização horizontal (Insecure Direct Object Reference — IDOR).</p>
 *
 * <h2>Ciclo TDD</h2>
 * <ul>
 *   <li><b>RED (antes de AT-10.2):</b> O controller não verifica ownership;
 *       qualquer usuário autenticado acessa qualquer carteira → testes falham (recebem 200/404 em vez de 403).</li>
 *   <li><b>GREEN (após AT-10.2):</b> O controller compara {@code jwt.sub} com
 *       {@code wallet.userId} e lança 403 se divergirem → testes passam.</li>
 * </ul>
 *
 * <h2>Estratégia de autenticação nos testes</h2>
 * <p>Usa {@link SecurityMockMvcRequestPostProcessors#jwt()} para injetar um
 * {@code JwtAuthenticationToken} real no {@code SecurityContext}, simulando um JWT
 * do Keycloak sem necessidade de OIDC Discovery. Isso permite que o controller
 * receba um {@code @AuthenticationPrincipal Jwt jwt} corretamente populado —
 * diferente do {@code @WithMockUser}, que injeta {@code UsernamePasswordAuthenticationToken}.</p>
 */
@AutoConfigureMockMvc
@DisplayName("[RED → GREEN] AT-10.2 — Resource Ownership: acesso cruzado de carteiras deve retornar 403")
class WalletOwnershipTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WalletRepository walletRepository;

    /** UUID do Usuário A (atacante) — possui JWT válido, mas não é dono da carteira B. */
    private UUID userAId;

    /** UUID do Usuário B (vítima) — dono legítimo da carteira. */
    private UUID userBId;

    /** Carteira pertencente ao Usuário B. */
    private Wallet walletB;

    @BeforeEach
    void setupWallets() {
        walletRepository.deleteAll();

        userAId = UUID.randomUUID();
        userBId = UUID.randomUUID();

        // Wallet B com saldo inicial — alvo do ataque de autorização horizontal
        walletB = walletRepository.save(
                Wallet.create(userBId, new BigDecimal("500.00"), new BigDecimal("10"))
        );
        // Wallet A para o owner test (GET /{userId})
        walletRepository.save(
                Wallet.create(userAId, new BigDecimal("100.00"), BigDecimal.ZERO)
        );
    }

    // =========================================================================
    // FASE RED: PATCH /{walletId}/balance — acesso cruzado (deve ser 403)
    // =========================================================================

    /**
     * Cenário crítico de IDOR: Usuário A usa JWT próprio para modificar carteira de B.
     *
     * <p><b>RED:</b> Atualmente o controller não verifica ownership e retorna 200.
     * Após AT-10.2 → deve retornar 403 Forbidden.</p>
     */
    @Test
    @DisplayName("PATCH /balance com JWT de outro usuário deve retornar 403 (IDOR bloqueado)")
    void adjustBalance_jwtDeOutroUsuario_deveRetornar403() throws Exception {
        // Usuário A (jwt.sub = userAId) tenta modificar carteira de Usuário B
        mockMvc.perform(
                patch("/api/v1/wallets/{walletId}/balance", walletB.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"brlAmount\": 9999.00}")
                        .with(jwt().jwt(builder -> builder.subject(userAId.toString())))
        )
                .andExpect(status().isForbidden()); // 403 — deve FALHAR antes de AT-10.2
    }

    // =========================================================================
    // FASE RED: GET /{userId} — acesso cruzado (deve ser 403)
    // =========================================================================

    /**
     * Usuário A usa JWT próprio para consultar carteira de Usuário B via userId.
     *
     * <p><b>RED:</b> Atualmente retorna 200. Após AT-10.2 → deve retornar 403.</p>
     */
    @Test
    @DisplayName("GET /{userId} com JWT de outro usuário deve retornar 403 (IDOR bloqueado)")
    void getByUserId_jwtDeOutroUsuario_deveRetornar403() throws Exception {
        // Usuário A (jwt.sub = userAId) tenta consultar carteira de Usuário B (userBId)
        mockMvc.perform(
                get("/api/v1/wallets/{userId}", userBId)
                        .with(jwt().jwt(builder -> builder.subject(userAId.toString())))
        )
                .andExpect(status().isForbidden()); // 403 — deve FALHAR antes de AT-10.2
    }

    // =========================================================================
    // FASE RED → GREEN: owner acessa sua própria carteira (deve ser 200)
    // =========================================================================

    /**
     * Usuário B (owner) acessa sua própria carteira via PATCH.
     * Deve retornar 200 — sem regressão funcional.
     */
    @Test
    @DisplayName("PATCH /balance com JWT do próprio owner deve retornar 200 (sem regressão)")
    void adjustBalance_jwtDoOwner_deveRetornar200() throws Exception {
        // Usuário B (jwt.sub = userBId) acessa sua própria carteira — dono legítimo
        mockMvc.perform(
                patch("/api/v1/wallets/{walletId}/balance", walletB.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"brlAmount\": 50.00}")
                        .with(jwt().jwt(builder -> builder.subject(userBId.toString())))
        )
                .andExpect(status().isOk());
    }

    /**
     * Usuário B consulta sua própria carteira via GET.
     * Deve retornar 200 — sem regressão funcional.
     */
    @Test
    @DisplayName("GET /{userId} com JWT do próprio owner deve retornar 200 (sem regressão)")
    void getByUserId_jwtDoOwner_deveRetornar200() throws Exception {
        mockMvc.perform(
                get("/api/v1/wallets/{userId}", userBId)
                        .with(jwt().jwt(builder -> builder.subject(userBId.toString())))
        )
                .andExpect(status().isOk());
    }

    // =========================================================================
    // FASE RED → GREEN: admin acessa qualquer carteira (deve ser 200)
    // =========================================================================

    /**
     * Admin pode acessar a carteira de qualquer usuário.
     *
     * <p>O claim {@code roles} do JWT deve conter {@code ROLE_ADMIN}.</p>
     */
    @Test
    @DisplayName("PATCH /balance com JWT admin deve retornar 200 (acesso privilegiado)")
    void adjustBalance_jwtAdmin_deveRetornar200() throws Exception {
        // Admin (jwt.sub = userAId, mas com role ROLE_ADMIN) acessa carteira de B
        mockMvc.perform(
                patch("/api/v1/wallets/{walletId}/balance", walletB.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"brlAmount\": 10.00}")
                        .with(jwt().jwt(builder ->
                                builder.subject(userAId.toString())
                                       .claim("roles", java.util.List.of("ROLE_ADMIN"))))
        )
                .andExpect(status().isOk());
    }

    /**
     * Admin pode consultar a carteira de qualquer usuário.
     */
    @Test
    @DisplayName("GET /{userId} com JWT admin deve retornar 200 (acesso privilegiado)")
    void getByUserId_jwtAdmin_deveRetornar200() throws Exception {
        mockMvc.perform(
                get("/api/v1/wallets/{userId}", userBId)
                        .with(jwt().jwt(builder ->
                                builder.subject(userAId.toString())
                                       .claim("roles", java.util.List.of("ROLE_ADMIN"))))
        )
                .andExpect(status().isOk());
    }
}
