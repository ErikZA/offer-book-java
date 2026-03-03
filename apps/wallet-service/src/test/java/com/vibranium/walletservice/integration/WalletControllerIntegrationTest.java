package com.vibranium.walletservice.integration;

import com.vibranium.walletservice.AbstractIntegrationTest;
import com.vibranium.walletservice.application.dto.WalletResponse;
import com.vibranium.walletservice.domain.model.Wallet;
import com.vibranium.walletservice.domain.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// AT-4.2.1: import para testes de controle de acesso via @PreAuthorize
import org.springframework.security.test.context.support.WithMockUser;

/**
 * FASE RED — Testa os endpoints REST do {@code WalletController}.
 *
 * <p>Cobre as rotas provisionadas no Kong:</p>
 * <ul>
 *   <li>{@code GET /api/v1/wallets/{userId}} — retorna a carteira com saldos de ambos os assets.</li>
 *   <li>{@code GET /api/v1/wallets} — retorna listagem com id do cliente e saldos.</li>
 * </ul>
 *
 * <p>Estes testes operam sobre o contexto Spring completo com Testcontainers
 * (PostgreSQL + RabbitMQ). O Kong não está presente nos testes — o JWT é bypassado
 * pois os testes testam a lógica do microsserviço, não do gateway.</p>
 *
 * <p><b>RED:</b> Falharão até {@code WalletController} e {@code WalletResponse}
 * serem implementados (Fase Green).</p>
 */
@AutoConfigureMockMvc
@DisplayName("[RED] WalletController - Endpoints REST de consulta de carteira")
class WalletControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WalletRepository walletRepository;

    private Wallet walletWithFunds;
    private UUID userIdWithFunds;
    private UUID userIdEmpty;

    @BeforeEach
    void setupWallets() {
        walletRepository.deleteAll();

        userIdWithFunds = UUID.randomUUID();
        // Carteira com saldo pré-definido: R$500.00 e 25 VIB disponíveis
        walletWithFunds = walletRepository.save(
                Wallet.create(userIdWithFunds, new BigDecimal("500.00"), new BigDecimal("25"))
        );

        // Carteira vazia (saldos zerados)
        userIdEmpty = UUID.randomUUID();
        walletRepository.save(Wallet.create(userIdEmpty, BigDecimal.ZERO, BigDecimal.ZERO));
    }

    // -------------------------------------------------------------------------
    // GET /api/v1/wallets/{userId}
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /wallets/{userId} deve retornar 200 com carteira completa (ambos os assets)")
    void getByUserId_shouldReturn200WithFullWalletResponse() throws Exception {
        mockMvc.perform(get("/api/v1/wallets/{userId}", userIdWithFunds)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                // IDs
                .andExpect(jsonPath("$.walletId").value(walletWithFunds.getId().toString()))
                .andExpect(jsonPath("$.userId").value(userIdWithFunds.toString()))
                // BRL
                .andExpect(jsonPath("$.brlAvailable").value(500.00))
                .andExpect(jsonPath("$.brlLocked").value(0.00))
                // VIB
                .andExpect(jsonPath("$.vibAvailable").value(25))
                .andExpect(jsonPath("$.vibLocked").value(0));
    }

    @Test
    @DisplayName("GET /wallets/{userId} deve retornar 404 quando carteira não existe")
    void getByUserId_shouldReturn404WhenWalletDoesNotExist() throws Exception {
        UUID unknownUserId = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/wallets/{userId}", unknownUserId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /wallets/{userId} deve retornar carteira com todos os saldos zerados")
    void getByUserId_shouldReturnWalletWithAllZeroBalances() throws Exception {
        mockMvc.perform(get("/api/v1/wallets/{userId}", userIdEmpty)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brlAvailable").value(0))
                .andExpect(jsonPath("$.brlLocked").value(0))
                .andExpect(jsonPath("$.vibAvailable").value(0))
                .andExpect(jsonPath("$.vibLocked").value(0));
    }

    @Test
    @DisplayName("GET /wallets/{userId} com UUID mal formatado deve retornar 400")
    void getByUserId_withInvalidUUID_shouldReturn400() throws Exception {
        mockMvc.perform(get("/api/v1/wallets/{userId}", "not-a-valid-uuid")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // GET /api/v1/wallets (listagem — retorna todas as carteiras)
    // -------------------------------------------------------------------------

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /wallets deve retornar 200 com Page de carteiras (id do cliente, id da carteira, saldos)")
    void listWallets_shouldReturn200WithWalletList() throws Exception {
        mockMvc.perform(get("/api/v1/wallets")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                // AT-4.2.1: resposta é Page<WalletResponse> — conteúdo em $.content
                .andExpect(jsonPath("$.content", hasSize(greaterThanOrEqualTo(2))))
                // Cada item deve ter os campos mapeados no contrato
                .andExpect(jsonPath("$.content[*].walletId").exists())
                .andExpect(jsonPath("$.content[*].userId").exists())
                .andExpect(jsonPath("$.content[*].brlAvailable").exists())
                .andExpect(jsonPath("$.content[*].vibAvailable").exists());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /wallets deve retornar Page vazia quando não há carteiras")
    void listWallets_shouldReturnEmptyListWhenNoWalletsExist() throws Exception {
        walletRepository.deleteAll();

        mockMvc.perform(get("/api/v1/wallets")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                // AT-4.2.1: Page com content vazio, totalElements = 0
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("GET /wallets deve incluir carteira com saldo correto de ambos os assets")
    void listWallets_shouldContainWalletWithCorrectBalancesForBothAssets() throws Exception {
        mockMvc.perform(get("/api/v1/wallets")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                // AT-4.2.1: conteúdo está em $.content (Page<WalletResponse>)
                .andExpect(jsonPath("$.content[?(@.userId == '" + userIdWithFunds + "')].brlAvailable",
                        contains(500.0)))
                .andExpect(jsonPath("$.content[?(@.userId == '" + userIdWithFunds + "')].vibAvailable",
                        contains(25.0)));
    }

    // -------------------------------------------------------------------------
    // Validação de estrutura do response DTO
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("WalletResponse deve conter createdAt como campo de auditoria")
    void getByUserId_shouldReturnCreatedAtAuditField() throws Exception {
        mockMvc.perform(get("/api/v1/wallets/{userId}", userIdWithFunds)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    // -------------------------------------------------------------------------
    // Teste de latência (não funcional — garante SLA < 200ms para consultas)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("[Performance] GET /wallets/{userId} deve responder em menos de 200ms")
    void getByUserId_shouldRespondWithinLatencySLA() throws Exception {
        long start = System.currentTimeMillis();

        mockMvc.perform(get("/api/v1/wallets/{userId}", userIdWithFunds)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        long elapsed = System.currentTimeMillis() - start;
        assertThat(elapsed)
                .as("GET /wallets/{userId} deve responder em < 200ms, mas levou %dms", elapsed)
                .isLessThan(200L);
    }

    // -------------------------------------------------------------------------
    // AT-4.2.1 — @PreAuthorize("hasRole('ADMIN')") + paginação em GET /wallets
    // -------------------------------------------------------------------------

    /**
     * TC-LA-1: usuário sem ROLE_ADMIN deve receber 403 ao listar carteiras.
     *
     * <p>A classe base {@link com.vibranium.walletservice.AbstractIntegrationTest}
     * aplica {@code @WithMockUser} com ROLE_USER. Após adicionar
     * {@code @PreAuthorize("hasRole('ADMIN')")}, qualquer usuário
     * sem ROLE_ADMIN deve ser barrado.</p>
     */
    @Test
    @DisplayName("[AT-4.2.1] GET /wallets sem ROLE_ADMIN deve retornar 403 Forbidden")
    void testListAll_withoutAdminRole_returns403() throws Exception {
        // @WithMockUser da classe base concede apenas ROLE_USER — sem ROLE_ADMIN.
        // Espera 403 após a adição de @PreAuthorize("hasRole('ADMIN')").
        mockMvc.perform(get("/api/v1/wallets")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    /**
     * TC-LA-2: usuário com ROLE_ADMIN deve receber Page com campos de paginação.
     *
     * <p>Valida que a resposta é um {@code Page<WalletResponse>} com os campos
     * obrigatórios: {@code content}, {@code totalPages}, {@code totalElements}.</p>
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("[AT-4.2.1] GET /wallets com ROLE_ADMIN deve retornar resultados paginados (content + totalPages + totalElements)")
    void testListAll_withAdminRole_returnsPaginatedResults() throws Exception {
        mockMvc.perform(get("/api/v1/wallets")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                // Page<WalletResponse>: fields obrigatórios
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").isNumber())
                .andExpect(jsonPath("$.totalPages").isNumber())
                // Conteúdo mínimo — 2 carteiras criadas no @BeforeEach
                .andExpect(jsonPath("$.content", hasSize(greaterThanOrEqualTo(2))))
                .andExpect(jsonPath("$.content[*].walletId").exists())
                .andExpect(jsonPath("$.content[*].userId").exists());
    }

    /**
     * TC-LA-3: tamanho de página padrão deve ser 20; page=0 deve ser retornada por padrão.
     *
     * <p>Sem parâmetros de paginação, o Spring Data usa {@code PageRequest.of(0, 20)}.
     * Verifica que {@code $.size} é 20 e {@code $.number} é 0.</p>
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("[AT-4.2.1] GET /wallets sem parâmetros deve retornar page=0 com size=20 (default Spring Data)")
    void testListAll_pagination_defaultSize20() throws Exception {
        mockMvc.perform(get("/api/v1/wallets")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                // Tamanho de página default do Spring Data é 20
                .andExpect(jsonPath("$.size").value(20))
                // Primeira página (0-based)
                .andExpect(jsonPath("$.number").value(0))
                // totalElements deve refletir as 2 carteiras do @BeforeEach
                .andExpect(jsonPath("$.totalElements").value(greaterThanOrEqualTo(2)));
    }
}
