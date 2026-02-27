package com.vibranium.walletservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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
    public Binding keycloakEventsBinding(Queue walletKeycloakEventsQueue,
                                         TopicExchange keycloakEventsExchange) {
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
    public Binding walletCommandsBinding(Queue walletCommandsQueue,
                                         TopicExchange walletCommandsExchange) {
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
     * <p>Registrar {@code JavaTimeModule} aqui garante que o conversor do broker
     * use a mesma configuração do ObjectMapper principal da aplicação.</p>
     */
    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return new Jackson2JsonMessageConverter(mapper);
    }

    /**
     * RabbitTemplate configurado com o conversor Jackson.
     * Utilizado nos testes para publicar mensagens tipadas.
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         Jackson2JsonMessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        return template;
    }

    /**
     * RabbitAdmin responsável por declarar exchanges, filas e bindings no broker
     * na inicialização da aplicação.
     */
    @Bean
    public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        return new RabbitAdmin(connectionFactory);
    }
}
