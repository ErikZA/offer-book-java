package com.vibranium.walletservice.domain.repository;

import com.vibranium.walletservice.domain.model.Wallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Repositório Spring Data JPA para a entidade {@link Wallet}.
 *
 * <p>O método {@link #findByIdForUpdate} aplica {@code SELECT ... FOR UPDATE},
 * garantindo que apenas uma transação por vez possa modificar um determinado
 * registro (Lock Pessimista). Isso é essencial para evitar race conditions
 * em operações concorrentes de reserva e liquidação de fundos.</p>
 */
public interface WalletRepository extends JpaRepository<Wallet, UUID> {

    /**
     * Busca a carteira pelo userId do Keycloak.
     * Utilizado pelos endpoints REST de consulta.
     */
    Optional<Wallet> findByUserId(UUID userId);

    /**
     * Conta carteiras para um determinado userId.
     * Utilizado para validar idempotência na criação (máx. 1 carteira por usuário).
     */
    long countByUserId(UUID userId);

    /**
     * Busca a carteira pelo ID com lock pessimista exclusivo ({@code SELECT ... FOR UPDATE}).
     *
     * <p>Esta é a porta de entrada para QUALQUER operação de escrita no saldo.
     * Garante que operações concorrentes sejam serializadas no banco,
     * evitando double-spend e race conditions.</p>
     *
     * @param id UUID da carteira a ser bloqueada.
     * @return A carteira bloqueada, ou empty se não encontrada.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM Wallet w WHERE w.id = :id")
    Optional<Wallet> findByIdForUpdate(@Param("id") UUID id);
}
