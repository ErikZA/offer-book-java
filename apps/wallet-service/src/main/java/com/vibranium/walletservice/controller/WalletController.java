package com.vibranium.walletservice.controller;

import com.vibranium.walletservice.domain.Wallet;
import com.vibranium.walletservice.service.WalletService;
import com.vibranium.utils.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Controller REST para Wallet Service
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/wallets")
public class WalletController {

    private final WalletService walletService;

    @Autowired
    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    /**
     * POST /api/v1/wallets - Criar nova carteira
     */
    @PostMapping
    public ResponseEntity<ApiResponse<Wallet>> createWallet(
            @RequestParam String userId,
            @RequestParam String currency,
            @RequestParam(defaultValue = "0") BigDecimal initialBalance) {
        log.info("Criando carteira para usuário: {} em {}", userId, currency);
        Wallet wallet = walletService.createWallet(userId, currency, initialBalance);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(wallet, "Carteira criada com sucesso"));
    }

    /**
     * GET /api/v1/wallets/{userId}/{currency} - Obter saldo
     */
    @GetMapping("/{userId}/{currency}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getWallet(
            @PathVariable String userId,
            @PathVariable String currency) {
        log.info("Recuperando saldo da carteira do usuário: {}", userId);
        Wallet wallet = walletService.getWallet(userId, currency);

        Map<String, Object> response = new HashMap<>();
        response.put("userId", wallet.getUserId());
        response.put("currency", wallet.getCurrency());
        response.put("balance", wallet.getBalance());
        response.put("availableBalance", wallet.getAvailableBalance());
        response.put("reservedBalance", wallet.getReservedBalance());

        return ResponseEntity.ok(ApiResponse.success(response, "Carteira recuperada"));
    }

    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Wallet Service OK");
    }

}
