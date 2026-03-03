package com.vibranium.walletservice.integration;

import com.vibranium.walletservice.AbstractIntegrationTest;
import com.vibranium.walletservice.application.dto.BalanceUpdateRequest;
import com.vibranium.walletservice.domain.model.Wallet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * FASE RED — Testa o endpoint de atualização de saldo da carteira.
 *
 * <p>Rota no Kong: {@code PATCH /api/v1/wallets/{walletId}/balance}</p>
 *
 * <p>Regras de negócio validadas:</p>
 * <ul>
 *   <li>Pode atualizar apenas BRL, apenas VIB ou ambos na mesma requisição.</li>
 *   <li>O saldo disponível não pode ficar negativo (rejeita com 422 Unprocessable Entity).</li>
 *   <li>Carteira inexistente retorna 404.</li>
 *   <li>Requisição sem nenhum campo retorna 400 Bad Request.</li>
 *   <li>Operações concorrentes de depósito são seguras (não há double-credit).</li>
 * </ul>
 *
 * <p><b>RED:</b> Falharão até {@code WalletController}, {@code WalletService}
 * e {@code BalanceUpdateRequest} serem implementados.</p>
 */
@AutoConfigureMockMvc
@DisplayName("[RED] WalletBalanceUpdate - Atualização de saldo via REST (ambos os assets)")
class WalletBalanceUpdateIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private Wallet testWallet;

    @BeforeEach
    void setup() {
        testWallet = walletRepository.save(
                Wallet.create(UUID.randomUUID(), new BigDecimal("100.00"), new BigDecimal("10"))
        );
    }

    // -------------------------------------------------------------------------
    // Happy Path — atualização de BRL
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("PATCH /balance deve adicionar BRL ao saldo disponível (apenas BRL informado)")
    void updateBalance_brlOnly_shouldIncreaseBrlAvailable() throws Exception {
        String requestBody = """
                {
                  "brlAmount": 250.00
                }
                """;

        mockMvc.perform(patch("/api/v1/wallets/{walletId}/balance", testWallet.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brlAvailable").value(350.00))
                .andExpect(jsonPath("$.vibAvailable").value(10));

        // Verifica persistência diretamente no banco
        Wallet updated = walletRepository.findById(testWallet.getId()).orElseThrow();
        assertThat(updated.getBrlAvailable()).isEqualByComparingTo("350.00");
        assertThat(updated.getVibAvailable()).isEqualByComparingTo("10");
    }

    // -------------------------------------------------------------------------
    // Happy Path — atualização de VIB
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("PATCH /balance deve adicionar VIB ao saldo disponível (apenas VIB informado)")
    void updateBalance_vibOnly_shouldIncreaseVibAvailable() throws Exception {
        String requestBody = """
                {
                  "vibAmount": 5
                }
                """;

        mockMvc.perform(patch("/api/v1/wallets/{walletId}/balance", testWallet.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brlAvailable").value(100.00))
                .andExpect(jsonPath("$.vibAvailable").value(15));

        Wallet updated = walletRepository.findById(testWallet.getId()).orElseThrow();
        assertThat(updated.getVibAvailable()).isEqualByComparingTo("15");
        assertThat(updated.getBrlAvailable()).isEqualByComparingTo("100.00");
    }

    // -------------------------------------------------------------------------
    // Happy Path — atualização de ambos os assets
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("PATCH /balance deve atualizar BRL e VIB simultaneamente quando ambos informados")
    void updateBalance_bothAssets_shouldUpdateBrlAndVibSimultaneously() throws Exception {
        String requestBody = """
                {
                  "brlAmount": 500.00,
                  "vibAmount": 20
                }
                """;

        mockMvc.perform(patch("/api/v1/wallets/{walletId}/balance", testWallet.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brlAvailable").value(600.00))
                .andExpect(jsonPath("$.vibAvailable").value(30));

        Wallet updated = walletRepository.findById(testWallet.getId()).orElseThrow();
        assertThat(updated.getBrlAvailable()).isEqualByComparingTo("600.00");
        assertThat(updated.getVibAvailable()).isEqualByComparingTo("30");
    }

    // -------------------------------------------------------------------------
    // Remoção / Débito de saldo (valores negativos são aceitos, desde que o
    // saldo final não fique negativo — permite operação de débito)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("PATCH /balance com valor negativo deve debitar saldo (saldo final >= 0)")
    void updateBalance_withNegativeAmount_shouldDebitBalance() throws Exception {
        String requestBody = """
                {
                  "brlAmount": -50.00
                }
                """;

        mockMvc.perform(patch("/api/v1/wallets/{walletId}/balance", testWallet.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brlAvailable").value(50.00));

        Wallet updated = walletRepository.findById(testWallet.getId()).orElseThrow();
        assertThat(updated.getBrlAvailable()).isEqualByComparingTo("50.00");
    }

    // -------------------------------------------------------------------------
    // Regra de negócio: saldo não pode ficar negativo
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("PATCH /balance deve retornar 422 quando BRL ficaria negativo")
    void updateBalance_shouldReturn422WhenBrlWouldGoNegative() throws Exception {
        String requestBody = """
                {
                  "brlAmount": -200.00
                }
                """;

        mockMvc.perform(patch("/api/v1/wallets/{walletId}/balance", testWallet.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", containsString("saldo")));

        // Banco não deve ter sido alterado (transação revertida)
        Wallet wallet = walletRepository.findById(testWallet.getId()).orElseThrow();
        assertThat(wallet.getBrlAvailable()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("PATCH /balance deve retornar 422 quando VIB ficaria negativo")
    void updateBalance_shouldReturn422WhenVibWouldGoNegative() throws Exception {
        String requestBody = """
                {
                  "vibAmount": -100
                }
                """;

        mockMvc.perform(patch("/api/v1/wallets/{walletId}/balance", testWallet.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isUnprocessableEntity());

        Wallet wallet = walletRepository.findById(testWallet.getId()).orElseThrow();
        assertThat(wallet.getVibAvailable()).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("PATCH /balance com BRL negativo e VIB positivo deve ser transação atômica: se BRL falhar, VIB não é creditado")
    void updateBalance_atomicity_ifBrlFailsVibMustNotBeUpdated() throws Exception {
        // BRL vai a -50 (falha), VIB iria a 15 — mas a transação deve reverter tudo
        String requestBody = """
                {
                  "brlAmount": -150.00,
                  "vibAmount": 5
                }
                """;

        mockMvc.perform(patch("/api/v1/wallets/{walletId}/balance", testWallet.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isUnprocessableEntity());

        // Ambos os saldos devem permanecer inalterados
        Wallet wallet = walletRepository.findById(testWallet.getId()).orElseThrow();
        assertThat(wallet.getBrlAvailable()).isEqualByComparingTo("100.00");
        assertThat(wallet.getVibAvailable()).isEqualByComparingTo("10");
    }

    // -------------------------------------------------------------------------
    // Falha — carteira inexistente
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("PATCH /balance deve retornar 404 quando carteira não existe")
    void updateBalance_shouldReturn404WhenWalletNotFound() throws Exception {
        String requestBody = """
                { "brlAmount": 100.00 }
                """;

        mockMvc.perform(patch("/api/v1/wallets/{walletId}/balance", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // Validação de entrada
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("PATCH /balance sem nenhum campo deve retornar 400 Bad Request")
    void updateBalance_withEmptyBody_shouldReturn400() throws Exception {
        mockMvc.perform(patch("/api/v1/wallets/{walletId}/balance", testWallet.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH /balance com walletId mal formatado deve retornar 400")
    void updateBalance_withInvalidWalletId_shouldReturn400() throws Exception {
        mockMvc.perform(patch("/api/v1/wallets/invalid-uuid/balance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"brlAmount\": 10.00 }"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH /balance com body inválido (string no campo numérico) deve retornar 400")
    void updateBalance_withInvalidJsonType_shouldReturn400() throws Exception {
        mockMvc.perform(patch("/api/v1/wallets/{walletId}/balance", testWallet.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"brlAmount\": \"nao-e-numero\" }"))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------------
    // Concorrência: múltiplos depósitos simultâneos não devem gerar double-credit
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("[Concorrência] 10 depósitos simultâneos de R$10 devem resultar em exatamente R$200 (inicial R$100 + 10×R$10)")
    void updateBalance_concurrentDeposits_shouldNotDoubleCreditBalance() throws Exception {
        int threadCount = 10;
        BigDecimal depositPerThread = new BigDecimal("10.00");
        BigDecimal initialBalance = new BigDecimal("100.00");
        BigDecimal expectedFinal = initialBalance.add(depositPerThread.multiply(BigDecimal.valueOf(threadCount)));

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        List<Exception> errors = new ArrayList<>();

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    String body = "{ \"brlAmount\": 10.00 }";
                    mockMvc.perform(patch("/api/v1/wallets/{walletId}/balance", testWallet.getId())
                                    .with(user("test-user"))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                            .andExpect(status().isOk());
                    successCount.incrementAndGet();
                } catch (Throwable t) {
                    synchronized (errors) { errors.add(new RuntimeException(t)); }
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startGate.countDown();
        doneLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        // Todos os depósitos devem ter sido processados com sucesso
        assertThat(errors).isEmpty();
        assertThat(successCount.get()).isEqualTo(threadCount);

        // Saldo final deve ser exatamente R$200 (sem double-credit)
        Wallet updated = walletRepository.findById(testWallet.getId()).orElseThrow();
        assertThat(updated.getBrlAvailable())
                .as("Saldo final deve ser exatamente R$%s após %d depósitos de R$%s",
                        expectedFinal, threadCount, depositPerThread)
                .isEqualByComparingTo(expectedFinal);
    }

    // -------------------------------------------------------------------------
    // Teste de performance (não funcional)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("[Performance] PATCH /balance deve responder em menos de 300ms")
    void updateBalance_shouldRespondWithinLatencySLA() throws Exception {
        String requestBody = "{ \"brlAmount\": 50.00 }";

        long start = System.currentTimeMillis();
        mockMvc.perform(patch("/api/v1/wallets/{walletId}/balance", testWallet.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk());

        long elapsed = System.currentTimeMillis() - start;
        assertThat(elapsed)
                .as("PATCH /balance deve responder em < 300ms, mas levou %dms", elapsed)
                .isLessThan(300L);
    }
}
