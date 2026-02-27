package com.vibranium.walletservice.application.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/**
 * DTO de deserialização do payload publicado pelo plugin
 * {@code aznamier/keycloak-event-listener-rabbitmq}.
 *
 * <p>O plugin publica um JSON para cada evento do Keycloak (LOGIN, REGISTER,
 * LOGOUT, etc.). O campo {@code type} é usado para filtrar apenas os eventos
 * {@code REGISTER}, que disparam a criação de carteira.</p>
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} garante que campos
 * adicionais publicados pelo plugin (ex: {@code details}) não causem
 * erros de deserialização.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KeycloakEventDto(

        String id,

        /** Timestamp epoch-milliseconds do evento no Keycloak. */
        long time,

        /**
         * Tipo do evento Keycloak. Valores relevantes: {@code REGISTER}, {@code LOGIN},
         * {@code LOGOUT}. Apenas {@code REGISTER} cria carteira.
         */
        String type,

        String realmId,

        String clientId,

        /** UUID do usuário no Keycloak — torna-se o {@code userId} da carteira. */
        UUID userId,

        String ipAddress
) {}
