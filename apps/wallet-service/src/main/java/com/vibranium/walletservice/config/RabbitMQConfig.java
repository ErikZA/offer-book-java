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
 *     ├─ Binding: wallet.command.reserve-funds → Queue: wallet.commands.reserve-funds
 *     │    └─ DLX: vibranium.dlq → wallet.commands.reserve-funds.dlq
 *     ├─ Binding: wallet.command.release-funds → Queue: wallet.commands.release-funds
 *     │    └─ DLX: vibranium.dlq → wallet.commands.release-funds.dlq
 *     └─ Binding: wallet.command.settle-funds  → Queue: wallet.commands
 *
 *   Exchange: vibranium.dlq (direct) — Dead Letter Exchange
 *     ├─ Binding: wallet.commands.reserve-funds.dlq → Queue: wallet.commands.reserve-funds.dlq
 *     └─ Binding: wallet.commands.release-funds.dlq → Queue: wallet.commands.release-funds.dlq
 * </pre>
 *
 * <p>Todos os recursos são declarados com {@code durable=true} para sobreviverem
 * a reinicializações do broker. O {@code RabbitAdmin} criará automaticamente
 * exchanges e filas que ainda não existam no broker.</p>
 *
 * <p><b>DLQ (Dead Letter Queue):</b> As filas {@code wallet.commands.reserve-funds} e
 * {@code wallet.commands.release-funds} possuem {@code x-dead-letter-exchange=vibranium.dlq}
 * com routing keys DLQ individuais. Isso garante que qualquer mensagem rejeitada com
 * {@code requeue=false} — seja por NACK explícito do listener ou por TTL expirado — seja
 * encaminhada automaticamente ao exchange DLX, eliminando a perda silenciosa de comandos
 * financeiros, especialmente crítico para o caminho compensatório da Saga.</p>
 */
@Configuration
public class RabbitMQConfig {

    // -------------------------------------------------------------------------
    // Constantes de topologia
    // -------------------------------------------------------------------------

    /**
     * Exchange DLX (Dead Letter Exchange) centralizado para o wallet-service.
     * Mensagens rejeitadas definitivamente de qualquer fila que declare este
     * exchange como {@code x-dead-letter-exchange} serão roteadas aqui.
     *
     * <p>Usa um {@link DirectExchange} para roteamento determinístico via
     * {@code x-dead-letter-routing-key} declarado em cada fila produtora.</p>
     */
    public static final String DLQ_EXCHANGE = "vibranium.dlq";

    /**
     * Fila dedicada para {@link com.vibranium.contracts.commands.wallet.ReserveFundsCommand}.
     * Separada da fila genérica {@code wallet.commands} para permitir configurar
     * política de DLQ de forma cirúrgica, sem afetar outros comandos.
     */
    public static final String QUEUE_RESERVE_FUNDS = "wallet.commands.reserve-funds";

    /**
     * Fila dedicada para {@link com.vibranium.contracts.commands.wallet.ReleaseFundsCommand}.
     * Separada com DLQ própria — falhas no caminho compensatório indicam fundos
     * permanentemente bloqueados, exigindo análise manual imediata.
     */
    public static final String QUEUE_RELEASE_FUNDS = "wallet.commands.release-funds";

    /**
     * Dead Letter Queue para o comando de liberação de fundos.
     * Mensagens aqui representam fundos que não foram liberados — incidente crítico.
     */
    public static final String QUEUE_RELEASE_FUNDS_DLQ = "wallet.commands.release-funds.dlq";

    /**
     * Dead Letter Queue para o comando de reserva de fundos.
     * Recebe automaticamente qualquer mensagem da fila principal que seja:
     * <ul>
     *   <li>NACKed com {@code requeue=false} (ex: falha permanente no listener).</li>
     *   <li>Expirada por TTL (caso configurado no futuro).</li>
     *   <li>Rejeitada por fila cheia (overflow).</li>
     * </ul>
     */
    public static final String QUEUE_RESERVE_FUNDS_DLQ = "wallet.commands.reserve-funds.dlq";

    // -------------------------------------------------------------------------
    // Exchanges
    // -------------------------------------------------------------------------

    /**
     * Dead Letter Exchange (DLX) centralizado do wallet-service.
     *
     * <p>Declarado como {@link DirectExchange} para garantir roteamento determinístico:
     * cada fila produtora declara um {@code x-dead-letter-routing-key} único,
     * e este exchange encaminha diretamente à fila DLQ correspondente.</p>
     *
     * <p>Ao usar um exchange dedicado (em vez de configuração por policy no broker),
     * a DLQ é <b>declarada via código</b>, ficando versionada no repositório, auditável,
     * replicável em qualquer ambiente e livre de drift de configuração manual.</p>
     */
    @Bean
    public DirectExchange dlqExchange() {
        // durable=true: sobrevive a restart do broker
        // autoDelete=false: não é removido quando não há consumers
        return new DirectExchange(DLQ_EXCHANGE, true, false);
    }

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

    /** Fila que recebe comandos de liquidação de fundos (SettleFundsCommand). */
    @Bean
    public Queue walletCommandsQueue() {
        return QueueBuilder.durable("wallet.commands").build();
    }

    /**
     * Fila dedicada para {@link com.vibranium.contracts.commands.wallet.ReserveFundsCommand}.
     *
     * <p>Os argumentos {@code x-dead-letter-exchange} e {@code x-dead-letter-routing-key}
     * são declarados <b>na fila produtora</b> (aqui), não no exchange, conforme
     * recomendação do RabbitMQ para DLQ por declaração explícita.</p>
     *
     * <p>Quando o listener executa {@code channel.basicNack(tag, false, false)},
     * o RabbitMQ ignora a fila normal e encaminha a mensagem automaticamente para
     * o exchange {@value DLQ_EXCHANGE} com a routing key
     * {@value QUEUE_RESERVE_FUNDS_DLQ}, sem qualquer ação extra do código.</p>
     *
     * <p><b>Por que declaração explícita em vez de policy?</b><br>
     * Policies são configuradas manualmente no broker (Management UI ou CLI),
     * criando drift entre ambientes. A declaração via código é:
     * <ul>
     *   <li>Versionada no Git — rastreável em todo PR.</li>
     *   <li>Reproduzível — qualquer ambiente novo (dev, test, staging) fica correto.</li>
     *   <li>Self-documenting — a intenção está no código, não numa wiki.</li>
     * </ul></p>
     */
    @Bean
    public Queue reserveFundsQueue() {
        return QueueBuilder.durable(QUEUE_RESERVE_FUNDS)
                // Encaminha mensagens rejeitadas (NACK requeue=false) para o exchange DLX
                .withArgument("x-dead-letter-exchange", DLQ_EXCHANGE)
                // Routing key determinística na DLQ — cada fila produtora tem sua DLQ
                .withArgument("x-dead-letter-routing-key", QUEUE_RESERVE_FUNDS_DLQ)
                .build();
    }

    /**
     * Dead Letter Queue para {@link com.vibranium.contracts.commands.wallet.ReserveFundsCommand}.
     *
     * <p>Declarada como fila simples durable — sem TTL nem requeue automático.
     * Mensagens aqui representam falhas permanentes que exigem análise humana ou
     * reprocessamento manual supervisionado.</p>
     *
     * <p>Não introduz lógica de retry para evitar loops infinitos ("retry storm")
     * em mensagens com estado de wallet genuinamente inválido (poison pill).</p>
     */
    @Bean
    public Queue reserveFundsDlQueue() {
        return QueueBuilder.durable(QUEUE_RESERVE_FUNDS_DLQ).build();
    }

    /**
     * Fila dedicada para {@link com.vibranium.contracts.commands.wallet.ReleaseFundsCommand}.
     *
     * <p>Configurada com DLQ para garantir que nenhuma mensagem de liberação seja
     * perdida silenciosamente — fundos bloqueados indevidamente exigem rastreabilidade.</p>
     */
    @Bean
    public Queue releaseFundsQueue() {
        return QueueBuilder.durable(QUEUE_RELEASE_FUNDS)
                .withArgument("x-dead-letter-exchange", DLQ_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", QUEUE_RELEASE_FUNDS_DLQ)
                .build();
    }

    /**
     * Dead Letter Queue para {@link com.vibranium.contracts.commands.wallet.ReleaseFundsCommand}.
     * Mensagens aqui indicam compensação incompleta — requer intervenção operacional.
     */
    @Bean
    public Queue releaseFundsDlQueue() {
        return QueueBuilder.durable(QUEUE_RELEASE_FUNDS_DLQ).build();
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
     * Liga a exchange de comandos à fila genérica de comandos para
     * routing key exata {@code wallet.command.settle-funds}.
     *
     * <p>A routing key é agora específica (não mais {@code wallet.command.#}) para
     * evitar que mensagens de {@code reserve-funds} sejam entregues em <b>ambas</b>
     * as filas ({@code wallet.commands} e {@code wallet.commands.reserve-funds}),
     * o que causaria duplicidade de processamento.</p>
     */
    @Bean
    public Binding walletCommandsBinding(
            @Qualifier("walletCommandsQueue")    Queue walletCommandsQueue,
            @Qualifier("walletCommandsExchange") TopicExchange walletCommandsExchange) {
        return BindingBuilder
                .bind(walletCommandsQueue)
                .to(walletCommandsExchange)
                .with("wallet.command.settle-funds");
    }

    /**
     * Liga a exchange de comandos à fila dedicada de reserva de fundos.
     *
     * <p>Routing key exata {@code wallet.command.reserve-funds} garante que
     * apenas comandos de reserva — que possuem DLQ configurada — fluam
     * para esta fila específica.</p>
     */
    @Bean
    public Binding reserveFundsQueueBinding(
            @Qualifier("reserveFundsQueue")      Queue reserveFundsQueue,
            @Qualifier("walletCommandsExchange") TopicExchange walletCommandsExchange) {
        return BindingBuilder
                .bind(reserveFundsQueue)
                .to(walletCommandsExchange)
                .with("wallet.command.reserve-funds");
    }

    /**
     * Liga a exchange de comandos à fila dedicada de liberação de fundos.
     * Routing key exata {@code wallet.command.release-funds}.
     */
    @Bean
    public Binding releaseFundsQueueBinding(
            @Qualifier("releaseFundsQueue")      Queue releaseFundsQueue,
            @Qualifier("walletCommandsExchange") TopicExchange walletCommandsExchange) {
        return BindingBuilder
                .bind(releaseFundsQueue)
                .to(walletCommandsExchange)
                .with("wallet.command.release-funds");
    }

    /**
     * Liga o exchange DLX à Dead Letter Queue de liberação de fundos.
     */
    @Bean
    public Binding releaseFundsDlqBinding(
            @Qualifier("releaseFundsDlQueue") Queue releaseFundsDlQueue,
            @Qualifier("dlqExchange")         DirectExchange dlqExchange) {
        return BindingBuilder
                .bind(releaseFundsDlQueue)
                .to(dlqExchange)
                .with(QUEUE_RELEASE_FUNDS_DLQ);
    }

    /**
     * Liga o exchange DLX à Dead Letter Queue de reserva de fundos.
     *
     * <p>O {@link DirectExchange} {@value DLQ_EXCHANGE} roteia via routing key exata.
     * A routing key {@value QUEUE_RESERVE_FUNDS_DLQ} corresponde ao valor declarado
     * em {@code x-dead-letter-routing-key} da fila {@value QUEUE_RESERVE_FUNDS},
     * garantindo que apenas mensagens mortas desta fila específica cheguem aqui.</p>
     */
    @Bean
    public Binding reserveFundsDlqBinding(
            @Qualifier("reserveFundsDlQueue") Queue reserveFundsDlQueue,
            @Qualifier("dlqExchange")         DirectExchange dlqExchange) {
        return BindingBuilder
                .bind(reserveFundsDlQueue)
                .to(dlqExchange)
                .with(QUEUE_RESERVE_FUNDS_DLQ);
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
