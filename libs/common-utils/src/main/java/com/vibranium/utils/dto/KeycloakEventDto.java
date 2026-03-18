package com.vibranium.utils.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/**
 * Shared DTO for Keycloak events published by the aznamier plugin.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KeycloakEventDto(
        String id,
        long time,
        String type,
        String realmId,
        String clientId,
        String error,
        UUID userId,
        String ipAddress,
        String operationType,
        String resourceType,
        String resourcePath
) {}
