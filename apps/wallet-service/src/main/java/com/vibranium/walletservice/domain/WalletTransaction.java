package com.vibranium.walletservice.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Entidade para rastrear transações e garantir idempotência
 * Cada evento RabbitMQ correlaciona com uma transação por event_id
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "wallet_transactions", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"event_id"})
})
public class WalletTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true)
    private String eventId; // UUID do evento RabbitMQ (idempotência)

    @Column(name = "wallet_id", nullable = false)
    private Long walletId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "amount", nullable = false, precision = 19, scale = 8)
    private BigDecimal amount;

    @Column(name = "type", nullable = false) // DEBIT, CREDIT
    private String type;

    @Column(name = "reason", nullable = false)
    private String reason; // ORDER_PLACEMENT, SELL_MATCH, REFUND

    @Column(name = "reference_id")
    private String referenceId; // ID da ordem/trade

    @Column(name = "status", nullable = false)
    private String status; // PENDING, COMPLETED, FAILED

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "balance_before", precision = 19, scale = 8)
    private BigDecimal balanceBefore;

    @Column(name = "balance_after", precision = 19, scale = 8)
    private BigDecimal balanceAfter;

}
