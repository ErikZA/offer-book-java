package com.vibranium.utils.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.List;

/**
 * Classe base abstrata para o relay do Transactional Outbox Pattern.
 *
 * <p>Implementa o <em>Template Method Pattern</em>: o algoritmo de polling
 * (buscar pendentes → iterar → publicar → marcar) é fixo no método
 * {@link #pollAndPublish()}, enquanto os pontos de variação são delegados
 * a métodos abstratos que cada serviço implementa de acordo com seu modelo
 * de domínio e estratégia de persistência.</p>
 *
 * <h2>Fluxo do Template Method</h2>
 * <ol>
 *   <li>{@link #pollAndPublish()} — orquestra o ciclo de polling (chamado pelo {@code @Scheduled} da subclasse).</li>
 *   <li>{@link #findPendingMessages(int)} — busca mensagens pendentes (SKIP LOCKED).</li>
 *   <li>{@link #dispatchMessage(Object)} — hook de indireção (self-proxy, TransactionTemplate, etc.).</li>
 *   <li>{@link #doPublish(Object)} — lógica core: beforePublish → build → send → afterPublish → log.</li>
 *   <li>{@link #doRecover(Exception, Object)} — log de falha permanente após retries esgotados.</li>
 * </ol>
 *
 * <h2>Pontos de extensão</h2>
 * <table>
 *   <tr><th>Método</th><th>Propósito</th></tr>
 *   <tr><td>{@link #findPendingMessages(int)}</td><td>Query de busca (repositório específico)</td></tr>
 *   <tr><td>{@link #dispatchMessage(Object)}</td><td>Indireção para retry (self-proxy, etc.)</td></tr>
 *   <tr><td>{@link #beforePublish(Object)}</td><td>Claim atômico pré-publicação</td></tr>
 *   <tr><td>{@link #buildAmqpMessage(Object)}</td><td>Construção da mensagem AMQP</td></tr>
 *   <tr><td>{@link #resolveExchange(Object)}</td><td>Exchange de destino</td></tr>
 *   <tr><td>{@link #resolveRoutingKey(Object)}</td><td>Routing key de destino</td></tr>
 *   <tr><td>{@link #afterPublish(Object)}</td><td>Marcar como publicado/processado</td></tr>
 *   <tr><td>{@link #getMessageId(Object)}</td><td>ID para logging</td></tr>
 *   <tr><td>{@link #getEventType(Object)}</td><td>Tipo do evento para logging</td></tr>
 * </table>
 *
 * <h2>Dependências</h2>
 * <p>Não depende de Spring Data JPA — cada serviço fornece sua implementação
 * de repositório via os métodos abstratos.</p>
 *
 * @param <T> Tipo da entidade de outbox do serviço concreto.
 * @see OutboxConfigProperties
 */
public abstract class AbstractOutboxPublisher<T> {

    /** Logger com nome da subclasse concreta para rastreabilidade. */
    protected final Logger logger = LoggerFactory.getLogger(getClass());

    private final RabbitTemplate rabbitTemplate;
    private final int batchSize;

    /**
     * @param rabbitTemplate Template para publicação no RabbitMQ.
     * @param batchSize      Tamanho máximo do lote por ciclo de polling.
     */
    protected AbstractOutboxPublisher(RabbitTemplate rabbitTemplate, int batchSize) {
        this.rabbitTemplate = rabbitTemplate;
        this.batchSize = batchSize;
    }

    // =========================================================================
    // Template Method — ciclo de polling
    // =========================================================================

    /**
     * Template Method: orquestra o ciclo completo de polling do outbox.
     *
     * <p>A subclasse deve chamar este método a partir do seu {@code @Scheduled},
     * opcionalmente envolvido em {@code @Transactional} ou
     * {@code TransactionTemplate} conforme a estratégia do serviço.</p>
     */
    protected void pollAndPublish() {
        List<T> pending = findPendingMessages(batchSize);

        if (pending.isEmpty()) {
            return;
        }

        logger.debug("Outbox polling: {} mensagem(ns) pendente(s)", pending.size());

        for (T msg : pending) {
            dispatchMessage(msg);
        }
    }

    // =========================================================================
    // Hooks com default — subclasse sobrescreve conforme necessidade
    // =========================================================================

    /**
     * Hook de indireção para publicação de uma mensagem individual.
     *
     * <p>Default: chama {@link #doPublish(Object)} diretamente.</p>
     *
     * <p>Sobrescreva para adicionar self-proxy ({@code @Lazy}),
     * {@code TransactionTemplate}, ou outra indireção necessária para
     * que {@code @Retryable} funcione em chamadas internas.</p>
     *
     * @param message Mensagem do outbox a ser publicada.
     */
    protected void dispatchMessage(T message) {
        doPublish(message);
    }

    /**
     * Hook pré-publicação. Retorna {@code false} para pular a mensagem.
     *
     * <p>Default: retorna {@code true} (publica sempre — SKIP LOCKED já
     * garante exclusividade entre instâncias).</p>
     *
     * <p>Sobrescreva para implementar claim atômico ({@code UPDATE ... WHERE
     * processed = false}) quando o modelo exigir idempotência adicional.</p>
     *
     * @param message Mensagem do outbox.
     * @return {@code true} para publicar, {@code false} para descartar.
     */
    protected boolean beforePublish(T message) {
        return true;
    }

    /**
     * Hook pós-publicação. Executado após envio bem-sucedido ao broker.
     *
     * <p>Default: no-op (quando o claim já marcou como processado).</p>
     *
     * <p>Sobrescreva para marcar {@code published_at = now()} ou
     * chamar {@code repository.save()} se o modelo exigir.</p>
     *
     * @param message Mensagem publicada com sucesso.
     */
    protected void afterPublish(T message) {
        // No-op por default — subclasse implementa se necessário.
    }

    // =========================================================================
    // Core publish — lógica compartilhada entre serviços
    // =========================================================================

    /**
     * Lógica core de publicação: beforePublish → build → send → afterPublish → log.
     *
     * <p>Pode ser chamado diretamente pela subclasse ou via
     * {@link #dispatchMessage(Object)}. A subclasse pode adicionar
     * {@code @Retryable} no método que delega para este.</p>
     *
     * @param message Mensagem do outbox a ser publicada.
     */
    public void doPublish(T message) {
        if (!beforePublish(message)) {
            logger.debug("Outbox event {} já processado por outra instância — descartando.",
                    getMessageId(message));
            return;
        }

        Message amqpMessage = buildAmqpMessage(message);
        String exchange = resolveExchange(message);
        String routingKey = resolveRoutingKey(message);

        rabbitTemplate.send(exchange, routingKey, amqpMessage);

        afterPublish(message);

        logger.info("Outbox event publicado: id={} type={} exchange={} routingKey={}",
                getMessageId(message), getEventType(message), exchange, routingKey);
    }

    // =========================================================================
    // Recovery — log padrão para falhas permanentes
    // =========================================================================

    /**
     * Handler de recuperação para falhas permanentes após retries esgotados.
     *
     * <p>A subclasse deve chamar este método a partir do seu {@code @Recover}.
     * Não propaga exceção — a mensagem permanece pendente para reprocessamento
     * no próximo ciclo de polling.</p>
     *
     * @param ex      Última exceção lançada.
     * @param message Mensagem que falhou permanentemente neste ciclo.
     */
    public void doRecover(Exception ex, T message) {
        logger.error(
                "Falha permanente ao publicar evento Outbox após múltiplas tentativas. "
                        + "id={} eventType={} error={}",
                getMessageId(message), getEventType(message), ex.getMessage(), ex);
    }

    // =========================================================================
    // Métodos abstratos — extensão points obrigatórios
    // =========================================================================

    /**
     * Busca mensagens pendentes com lock (SKIP LOCKED).
     *
     * @param batchSize Tamanho máximo do lote.
     * @return Lista de mensagens pendentes.
     */
    protected abstract List<T> findPendingMessages(int batchSize);

    /**
     * Constrói a mensagem AMQP a partir da entidade de outbox.
     *
     * @param message Entidade do outbox.
     * @return Mensagem AMQP pronta para envio.
     */
    protected abstract Message buildAmqpMessage(T message);

    /**
     * Resolve a exchange RabbitMQ de destino para a mensagem.
     *
     * @param message Entidade do outbox.
     * @return Nome da exchange.
     */
    protected abstract String resolveExchange(T message);

    /**
     * Resolve a routing key de destino para a mensagem.
     *
     * @param message Entidade do outbox.
     * @return Routing key.
     */
    protected abstract String resolveRoutingKey(T message);

    /**
     * Retorna o identificador da mensagem para logging.
     *
     * @param message Entidade do outbox.
     * @return ID da mensagem (UUID, Long, etc.).
     */
    protected abstract Object getMessageId(T message);

    /**
     * Retorna o tipo do evento para logging.
     *
     * @param message Entidade do outbox.
     * @return Nome do tipo do evento.
     */
    protected abstract String getEventType(T message);

    // =========================================================================
    // Accessors para subclasses
    // =========================================================================

    /** @return Template RabbitMQ injetado. */
    protected RabbitTemplate getRabbitTemplate() {
        return rabbitTemplate;
    }

    /** @return Tamanho do lote configurado. */
    protected int getBatchSize() {
        return batchSize;
    }
}
