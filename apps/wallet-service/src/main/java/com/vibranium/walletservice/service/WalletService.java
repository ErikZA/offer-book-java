package com.vibranium.walletservice.service;

import com.vibranium.walletservice.domain.Wallet;
import com.vibranium.walletservice.domain.WalletRepository;
import com.vibranium.walletservice.domain.WalletTransaction;
import com.vibranium.walletservice.domain.WalletTransactionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Service de Wallet com transações ACID e idempotência
 * Usa OptimisticLocking e registro de transações para garantir consistência
 */
@Slf4j
@Service
public class WalletService {

    private final WalletRepository walletRepository;
    private final WalletTransactionRepository transactionRepository;

    @Autowired
    public WalletService(WalletRepository walletRepository, WalletTransactionRepository transactionRepository) {
        this.walletRepository = walletRepository;
        this.transactionRepository = transactionRepository;
    }

    /**
     * Cria uma nova carteira para um usuário
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public Wallet createWallet(String userId, String currency, BigDecimal initialBalance) {
        log.info("Criando carteira para usuário: {} em {}", userId, currency);

        Wallet wallet = Wallet.builder()
                .userId(userId)
                .currency(currency)
                .balance(initialBalance)
                .availableBalance(initialBalance)
                .reservedBalance(BigDecimal.ZERO)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        return walletRepository.save(wallet);
    }

    /**
     * Débito de carteira - Idempotente via event_id
     * Se o mesmo event_id for processado 2x, não duplica o débito
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void debitWallet(String eventId, String userId, String currency, BigDecimal amount, String reason, String referenceId) {
        log.info("Debitando {} de {}", amount, userId);

        // Verificar idempotência: se já processou esse evento, retorna
        if (transactionRepository.findByEventId(eventId).isPresent()) {
            log.warn("Transação {} já foi processada. Ignorando.", eventId);
            return;
        }

        // Buscar carteira com lock pessimista
        Wallet wallet = walletRepository.findByUserIdAndCurrencyForUpdate(userId, currency)
                .orElseThrow(() -> new IllegalArgumentException("Carteira não encontrada"));

        if (wallet.getAvailableBalance().compareTo(amount) < 0) {
            throw new IllegalArgumentException("Saldo insuficiente");
        }

        // Registrar transação (antes de executar, para garantir atomicidade)
        BigDecimal balanceBefore = wallet.getAvailableBalance();
        wallet.setAvailableBalance(wallet.getAvailableBalance().subtract(amount));
        wallet.setUpdatedAt(Instant.now());

        Wallet updatedWallet = walletRepository.save(wallet);

        // Registrar na tabela de transações (garante idempotência)
        WalletTransaction transaction = WalletTransaction.builder()
                .eventId(eventId)
                .walletId(wallet.getId())
                .userId(userId)
                .currency(currency)
                .amount(amount)
                .type("DEBIT")
                .reason(reason)
                .referenceId(referenceId)
                .status("COMPLETED")
                .createdAt(Instant.now())
                .balanceBefore(balanceBefore)
                .balanceAfter(updatedWallet.getAvailableBalance())
                .build();

        transactionRepository.save(transaction);
        log.info("Débito completado. Novo saldo: {}", updatedWallet.getAvailableBalance());
    }

    /**
     * Crédito de carteira - Idempotente via event_id
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void creditWallet(String eventId, String userId, String currency, BigDecimal amount, String reason, String referenceId) {
        log.info("Creditando {} para {}", amount, userId);

        // Verificar idempotência
        if (transactionRepository.findByEventId(eventId).isPresent()) {
            log.warn("Transação {} já foi processada. Ignorando.", eventId);
            return;
        }

        // Buscar carteira
        Wallet wallet = walletRepository.findByUserIdAndCurrencyForUpdate(userId, currency)
                .orElseThrow(() -> new IllegalArgumentException("Carteira não encontrada"));

        BigDecimal balanceBefore = wallet.getAvailableBalance();
        wallet.setAvailableBalance(wallet.getAvailableBalance().add(amount));
        wallet.setUpdatedAt(Instant.now());

        Wallet updatedWallet = walletRepository.save(wallet);

        WalletTransaction transaction = WalletTransaction.builder()
                .eventId(eventId)
                .walletId(wallet.getId())
                .userId(userId)
                .currency(currency)
                .amount(amount)
                .type("CREDIT")
                .reason(reason)
                .referenceId(referenceId)
                .status("COMPLETED")
                .createdAt(Instant.now())
                .balanceBefore(balanceBefore)
                .balanceAfter(updatedWallet.getAvailableBalance())
                .build();

        transactionRepository.save(transaction);
        log.info("Crédito completado. Novo saldo: {}", updatedWallet.getAvailableBalance());
    }

    /**
     * Busca saldo de uma carteira
     */
    public Wallet getWallet(String userId, String currency) {
        return walletRepository.findByUserIdAndCurrency(userId, currency)
                .orElseThrow(() -> new IllegalArgumentException("Carteira não encontrada"));
    }

}
