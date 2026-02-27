package com.vibranium.orderservice.domain.repository;

import com.vibranium.orderservice.domain.model.UserRegistry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Repositório Spring Data JPA para {@link UserRegistry}.
 *
 * <p>Consultas críticas no caminho quente:</p>
 * <ul>
 *   <li>{@link #findByKeycloakId} — valida se o usuário pode operar antes de aceitar ordem.</li>
 *   <li>{@link #existsByKeycloakId} — idempotência: evita duplicar o registro.</li>
 * </ul>
 *
 * <p>O índice {@code idx_user_registry_keycloak_id} (criado em V1) garante
 * que estas consultas sejam O(log n) mesmo com milhões de usuários.</p>
 */
@Repository
public interface UserRegistryRepository extends JpaRepository<UserRegistry, UUID> {

    /**
     * Busca o registry pelo ID Keycloak (claim {@code sub} do JWT).
     *
     * @param keycloakId UUID do usuário no Keycloak.
     * @return Optional contendo o registro, ou empty se não encontrado.
     */
    Optional<UserRegistry> findByKeycloakId(String keycloakId);

    /**
     * Verifica existência sem carregar a entidade — mais eficiente para validação.
     *
     * @param keycloakId UUID do usuário no Keycloak.
     * @return {@code true} se existe, {@code false} caso contrário.
     */
    boolean existsByKeycloakId(String keycloakId);

    /**
     * Conta registros com o keycloakId informado (usado em testes de idempotência).
     *
     * @param keycloakId UUID do usuário no Keycloak.
     * @return número de registros (0 ou 1 dada a constraint UNIQUE).
     */
    long countByKeycloakId(String keycloakId);
}
