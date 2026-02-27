package com.vibranium.walletservice;

import com.vibranium.walletservice.application.dto.BalanceUpdateRequest;
import com.vibranium.walletservice.application.dto.WalletResponse;
import com.vibranium.walletservice.application.service.WalletService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Controller REST do wallet-service.
 *
 * <p>Expõe as rotas provisionadas no Kong:</p>
 * <ul>
 *   <li>{@code GET /api/v1/wallets/{userId}} — retorna a carteira do usuário com todos os saldos.</li>
 *   <li>{@code GET /api/v1/wallets} — lista todas as carteiras (admin/debug).</li>
 *   <li>{@code PATCH /api/v1/wallets/{walletId}/balance} — ajusta saldo (crédito ou débito).</li>
 * </ul>
 *
 * <p>O JWT do Keycloak é validado pelo Kong antes de chegar neste controller —
 * internamente não há autenticação adicional.</p>
 *
 * <p>O controller mantém-se thin: toda lógica de negócio está no {@link WalletService}.</p>
 */
@RestController
@RequestMapping("/api/v1/wallets")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    // -------------------------------------------------------------------------
    // Consultas
    // -------------------------------------------------------------------------

    /**
     * Retorna a carteira de um usuário específico com todos os saldos.
     *
     * @param userId UUID do usuário no Keycloak (path variable).
     * @return 200 com {@link WalletResponse} ou 404 se não encontrada.
     */
    @GetMapping("/{userId}")
    public ResponseEntity<WalletResponse> getByUserId(@PathVariable UUID userId) {
        return ResponseEntity.ok(walletService.findByUserId(userId));
    }

    /**
     * Lista todas as carteiras cadastradas.
     * Útil para fins administrativos e de debugging.
     *
     * @return 200 com lista de {@link WalletResponse} (pode ser vazia).
     */
    @GetMapping
    public ResponseEntity<List<WalletResponse>> listAll() {
        return ResponseEntity.ok(walletService.findAll());
    }

    // -------------------------------------------------------------------------
    // Mutações
    // -------------------------------------------------------------------------

    /**
     * Ajusta o saldo disponível de uma carteira (crédito ou débito).
     *
     * <p>Pelo menos um dos campos {@code brlAmount} ou {@code vibAmount} deve ser informado.
     * Valores positivos creditam; valores negativos debitam (desde que o saldo não fique negativo).
     * A operação é protegida por lock pessimista — segura para chamadas concorrentes.</p>
     *
     * @param walletId UUID da carteira (path variable — identifica a carteira, não o usuário).
     * @param request  Corpo JSON com os deltas de saldo.
     * @return 200 com {@link WalletResponse} atualizado, 422 se saldo ficaria negativo,
     *         404 se carteira não encontrada, 400 se request inválido.
     */
    @PatchMapping("/{walletId}/balance")
    public ResponseEntity<WalletResponse> updateBalance(
            @PathVariable UUID walletId,
            @Valid @RequestBody BalanceUpdateRequest request) {

        return ResponseEntity.ok(
                walletService.adjustBalance(walletId, request.brlAmount(), request.vibAmount())
        );
    }
}
