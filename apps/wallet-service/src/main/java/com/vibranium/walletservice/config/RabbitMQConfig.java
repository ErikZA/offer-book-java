package com.vibranium.walletservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuração das exchanges, filas e bindings do RabbitMQ para o wallet-service.
 *
 * <p>Topologia:</p>
 * <pre>
 *   Exchange: keycloak.events (topic)
 *     └─ Binding: KK.EVENT.CLIENT.# → Queue: wallet.keycloak.events
 *
 *   Exchange: wallet.commands (topic)
 *     └─ Binding: wallet.command.# → Queue: wallet.commands
 * </pre>
 *
 * <p>Todos os recursos são declarados com {@code durable=true} para sobreviverem
 * a reinicializações do broker. O {@code RabbitAdmin} criará automaticamente
 * exchanges e filas que ainda não existam no broker.</p>
 */
@Configuration
public class RabbitMQConfig {

    // -------------------------------------------------------------------------
    // Exchanges
    // -------------------------------------------------------------------------

    /**
     * Exchange topic do Keycloak. O plugin {@code aznamier/keycloak-event-listener-rabbitmq}
     * publica nesta exchange com routing keys no formato:
     * {@code KK.EVENT.CLIENT.<realm>.SUCCESS.CLIENT.<eventType>}.
     */
    @Bean
    public TopicExchange keycloakEventsExchange() {
        return new TopicExchange("keycloak.events", true, false);
    }

    /**
     * Exchange topic para comandos direcionados ao wallet-service.
     * Produtores (order-service) publicam com routing keys:
     * {@code wallet.command.reserve-funds} e {@code wallet.command.settle-funds}.
     */
    @Bean
    public TopicExchange walletCommandsExchange() {
        return new TopicExchange("wallet.commands", true, false);
    }

    /**
     * Exchange topic principal de eventos de domínio do wallet-service.
     *
     * <p>O {@link com.vibranium.walletservice.infrastructure.outbox.OutboxPublisherService}
     * publica todos os eventos aqui com routing-keys definidas em
     * {@link com.vibranium.walletservice.infrastructure.outbox.EventRoute}.</p>
     *
     * <p>Consumers (order-service, etc.) se ligam a esta exchange com suas
     * próprias filas e routing-key patterns.</p>
     */
    @Bean
    public TopicExchange vibraniumEventsExchange() {
        return new TopicExchange("vibranium.events", true, false);
    }

    // -------------------------------------------------------------------------
    // Queues
    // -------------------------------------------------------------------------

    /** Fila que recebe eventos de criação de usuário do Keycloak. */
    @Bean
    public Queue walletKeycloakEventsQueue() {
        return QueueBuilder.durable("wallet.keycloak.events").build();
    }

    /** Fila que recebe comandos de reserva e liquidação de fundos. */
    @Bean
    public Queue walletCommandsQueue() {
        return QueueBuilder.durable("wallet.commands").build();
    }

    // -------------------------------------------------------------------------
    // Bindings
    // -------------------------------------------------------------------------

    /**
     * Liga a exchange do Keycloak à fila de eventos.
     * O wildcard {@code KK.EVENT.CLIENT.#} captura todos os eventos de todos os realms.
     */
    @Bean
    public Binding keycloakEventsBinding(
            @Qualifier("walletKeycloakEventsQueue") Queue walletKeycloakEventsQueue,
            @Qualifier("keycloakEventsExchange")    TopicExchange keycloakEventsExchange) {
        return BindingBuilder
                .bind(walletKeycloakEventsQueue)
                .to(keycloakEventsExchange)
                .with("KK.EVENT.CLIENT.#");
    }

    /**
     * Liga a exchange de comandos à fila de comandos.
     * O wildcard {@code wallet.command.#} captura reserve-funds e settle-funds.
     */
    @Bean
    public Binding walletCommandsBinding(
            @Qualifier("walletCommandsQueue")    Queue walletCommandsQueue,
            @Qualifier("walletCommandsExchange") TopicExchange walletCommandsExchange) {
        return BindingBuilder
                .bind(walletCommandsQueue)
                .to(walletCommandsExchange)
                .with("wallet.command.#");
    }

    // -------------------------------------------------------------------------
    // Infraestrutura de mensagens
    // -------------------------------------------------------------------------

    /**
     * Conversor de mensagens Jackson. Serializa/deserializa payloads como JSON
     * com suporte a Java Time API (Instant, LocalDate, etc.).
     *
     * <p>O Spring Boot {@code RabbitAutoConfiguration} detecta automaticamente
     * este bean via {@code ObjectProvider<MessageConverter>} e o injeta no
     * {@code RabbitTemplate} auto-configurado — sem necessidade de redefinir
     * {@code RabbitTemplate} manualmente.</p>
     */
    @Bean
    public Jackson2JsonMessageConverter messageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
