package com.vibranium.orderservice.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Entidade que representa um usuário registrado localmente no order-service.
 *
 * <p>Populada pelo {@code KeycloakEventConsumer} ao consumir o evento
 * {@code REGISTER} publicado pelo plugin {@code aznamier/keycloak-to-rabbitmq}
 * na exchange {@code amq.topic}.</p>
 *
 * <p>Propósito: validar, antes de aceitar uma ordem, se o {@code sub} claim
 * do JWT corresponde a um usuário já registrado — sem chamada HTTP ao Keycloak
 * no caminho quente (latência zero extra na aceitação da ordem).</p>
 */
@Entity
@Table(name = "tb_user_registry")
public class UserRegistry {

    /**
     * PK interna gerada pelo banco via {@code gen_random_uuid()}.
     * Não exposta na API REST — o identificador externo é {@code keycloakId}.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /**
     * UUID do usuário no Keycloak (campo {@code sub} do JWT).
     * Único e imutável após o evento REGISTER.
     */
    @Column(name = "keycloak_id", unique = true, nullable = false, length = 36)
    private String keycloakId;

    /** Timestamp UTC do evento REGISTER recebido via RabbitMQ. */
    @Column(name = "registered_at", nullable = false, updatable = false)
    private Instant registeredAt;

    /** Construtor exigido pelo JPA. */
    protected UserRegistry() {}

    /**
     * Cria um novo registro de usuário a partir do seu ID Keycloak.
     *
     * @param keycloakId UUID do usuário conforme o claim {@code sub} do JWT.
     */
    public UserRegistry(String keycloakId) {
        this.keycloakId    = keycloakId;
        this.registeredAt  = Instant.now();
    }

    public UUID    getId()           { return id; }
    public String  getKeycloakId()   { return keycloakId; }
    public Instant getRegisteredAt() { return registeredAt; }
}
