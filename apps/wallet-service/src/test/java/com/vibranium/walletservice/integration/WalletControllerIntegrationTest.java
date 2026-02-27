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
    @DisplayName("GET /wallets deve retornar 200 com lista de carteiras (id do cliente, id da carteira, saldos)")
    void listWallets_shouldReturn200WithWalletList() throws Exception {
        mockMvc.perform(get("/api/v1/wallets")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                // Deve haver pelo menos 2 carteiras (criadas no @BeforeEach)
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(2))))
                // Cada item deve ter os campos mapeados no contrato
                .andExpect(jsonPath("$[*].walletId").exists())
                .andExpect(jsonPath("$[*].userId").exists())
                .andExpect(jsonPath("$[*].brlAvailable").exists())
                .andExpect(jsonPath("$[*].vibAvailable").exists());
    }

    @Test
    @DisplayName("GET /wallets deve retornar lista vazia quando não há carteiras")
    void listWallets_shouldReturnEmptyListWhenNoWalletsExist() throws Exception {
        walletRepository.deleteAll();

        mockMvc.perform(get("/api/v1/wallets")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("GET /wallets deve incluir carteira com saldo correto de ambos os assets")
    void listWallets_shouldContainWalletWithCorrectBalancesForBothAssets() throws Exception {
        mockMvc.perform(get("/api/v1/wallets")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.userId == '" + userIdWithFunds + "')].brlAvailable",
                        contains(500.0)))
                .andExpect(jsonPath("$[?(@.userId == '" + userIdWithFunds + "')].vibAvailable",
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
}
