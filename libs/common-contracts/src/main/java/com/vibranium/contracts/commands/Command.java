package com.vibranium.contracts.commands;

/**
 * Marker interface para todos os Comandos da plataforma Vibranium.
 *
 * <p>Um Command representa uma <strong>intenção</strong> de alterar o estado
 * do sistema. Toda implementação deve ser um {@code record} Java imutável.</p>
 *
 * <p>Diferença semântica em relação a {@code DomainEvent}:</p>
 * <ul>
 *   <li>Command: pode ser <em>rejeitado</em> pelo sistema</li>
 *   <li>DomainEvent: é um fato que <em>já ocorreu</em></li>
 * </ul>
 *
 * <p>Comandos carregam um {@code correlationId} para rastrear a Saga
 * que os disparou.</p>
 */
public interface Command {

    /**
     * ID de correlação da Saga. Deve ser propagado para todos os eventos
     * gerados como consequência deste comando.
     */
    java.util.UUID correlationId();
}
