package com.vibranium.orderservice.web.exception;

/**
 * Exceção lançada quando o {@code userId} extraído do JWT não está
 * presente no registro local ({@code tb_user_registry}).
 *
 * <p>Resulta em HTTP 403 Forbidden: o usuário está autenticado no Keycloak
 * (token válido), mas ainda não completou o onboarding da plataforma
 * (evento REGISTER ainda não chegou via RabbitMQ).</p>
 *
 * <p>Este cenário pode ocorrer em corrida: usuário se registra e imediatamente
 * tenta colocar uma ordem antes do evento REGISTER do Keycloak ser processado.</p>
 */
public class UserNotRegisteredException extends RuntimeException {

    private final String keycloakId;

    public UserNotRegisteredException(String keycloakId) {
        super("Usuário não encontrado no registro local: " + keycloakId);
        this.keycloakId = keycloakId;
    }

    public String getKeycloakId() { return keycloakId; }
}
