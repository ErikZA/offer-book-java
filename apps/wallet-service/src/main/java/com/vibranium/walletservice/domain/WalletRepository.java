package com.vibranium.walletservice.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WalletRepository extends JpaRepository<Wallet, Long> {

    Optional<Wallet> findByUserIdAndCurrency(String userId, String currency);

    List<Wallet> findByUserId(String userId);

    @Query("SELECT w FROM Wallet w WHERE w.userId = :userId AND w.currency = :currency FOR UPDATE")
    Optional<Wallet> findByUserIdAndCurrencyForUpdate(@Param("userId") String userId, @Param("currency") String currency);

}
