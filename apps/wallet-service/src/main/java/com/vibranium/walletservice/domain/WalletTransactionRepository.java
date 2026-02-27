package com.vibranium.walletservice.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, Long> {

    /**
     * Busca transação pelo event_id para garantir idempotência
     */
    Optional<WalletTransaction> findByEventId(String eventId);

}
