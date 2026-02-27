package com.vibranium.contracts.commands.wallet;

import com.vibranium.contracts.commands.Command;
import com.vibranium.contracts.enums.AssetType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Comando para criar uma carteira zerada para um usuário recém-registrado.
 *
 * <p>Disparado pelo {@code wallet-service} ao consumir o evento de cadastro
 * traduzido pela ACL (Anti-Corruption Layer) do Keycloak.
 * Representa a relação 1:1 entre usuário (Keycloak UUID) e carteira.</p>
 *
 * @param correlationId ID de correlação da Saga de onboarding.
 * @param userId        UUID do usuário no Keycloak — chave da relação 1:1.
 */
public record CreateWalletCommand(

        @NotNull UUID correlationId,
        @NotNull UUID userId

) implements Command {}
