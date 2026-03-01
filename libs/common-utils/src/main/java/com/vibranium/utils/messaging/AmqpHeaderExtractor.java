package com.vibranium.utils.messaging;

import org.springframework.amqp.core.MessageProperties;

import java.util.Objects;
import java.util.Optional;

/**
 * Utilitário para extração de IDs de rastreabilidade de headers AMQP.
 *
 * <p>O protocolo AMQP 0-9-1 define o campo {@code message-id} como propriedade
 * nativa da mensagem ({@link MessageProperties#getMessageId()}). Por convenção
 * da plataforma Vibranium, esse campo é usado como ID de correlação primário.
 * Como fallback, é verificado o header customizado {@code X-Correlation-ID},
 * que pode ser propagado por gateways HTTP → AMQP (ex.: Kong → RabbitMQ).</p>
 *
 * <p>Ordem de prioridade:</p>
 * <ol>
 *   <li>{@code MessageProperties.messageId} (campo nativo AMQP)</li>
 *   <li>Header {@code X-Correlation-ID} (propagação via gateway)</li>
 * </ol>
 *
 * <p>Uso padrão em um {@code @RabbitListener}:</p>
 * <pre>{@code
 *   @RabbitListener(queues = "my.queue")
 *   public void handle(MyEvent event, Message message) {
 *       String correlationId = AmqpHeaderExtractor
 *           .extractCorrelationId(message.getMessageProperties())
 *           .orElseGet(CorrelationIdGenerator::generateAsString);
 *       ...
 *   }
 * }</pre>
 */
public final class AmqpHeaderExtractor {

    /** Header customizado de correlação propagado por gateways e clientes HTTP. */
    public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";

    /** Construtor privado — esta classe não deve ser instanciada. */
    private AmqpHeaderExtractor() {
        // utilitário estático
    }

    /**
     * Extrai o campo nativo {@code message-id} das propriedades da mensagem AMQP.
     *
     * @param messageProperties propriedades da mensagem; não deve ser {@code null}
     * @return {@link Optional} contendo o messageId, ou vazio se ausente/em branco
     * @throws NullPointerException se {@code messageProperties} for {@code null}
     */
    public static Optional<String> extractMessageId(MessageProperties messageProperties) {
        Objects.requireNonNull(messageProperties, "messageProperties must not be null");
        String messageId = messageProperties.getMessageId();
        if (messageId == null || messageId.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(messageId);
    }

    /**
     * Extrai o ID de correlação seguindo a ordem de prioridade da plataforma.
     *
     * <p>Tenta {@code message-id} primeiro; se ausente ou em branco, tenta o
     * header {@code X-Correlation-ID}; caso nenhum esteja disponível, retorna
     * {@link Optional#empty()}.</p>
     *
     * @param messageProperties propriedades da mensagem; não deve ser {@code null}
     * @return {@link Optional} com o ID de correlação encontrado, ou vazio
     * @throws NullPointerException se {@code messageProperties} for {@code null}
     */
    public static Optional<String> extractCorrelationId(MessageProperties messageProperties) {
        Objects.requireNonNull(messageProperties, "messageProperties must not be null");

        // 1ª fonte: messageId nativo AMQP
        Optional<String> fromMessageId = extractMessageId(messageProperties);
        if (fromMessageId.isPresent()) {
            return fromMessageId;
        }

        // 2ª fonte: header customizado X-Correlation-ID (propagado por gateways)
        Object customHeader = messageProperties.getHeader(CORRELATION_ID_HEADER);
        if (customHeader instanceof String value && !value.isBlank()) {
            return Optional.of(value);
        }

        return Optional.empty();
    }
}
