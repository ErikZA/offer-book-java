package com.vibranium.orderservice.application.service;

import com.vibranium.orderservice.domain.model.OrderOutboxMessage;
import com.vibranium.orderservice.domain.repository.OrderOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Serviço de relay do Outbox Pattern para o order-service.
 *
 * <p>Executa periodicamente para publicar no RabbitMQ os comandos armazenados
 * na tabela {@code tb_order_outbox} pelo {@link OrderCommandService#placeOrder}.
 * Após publicação bem-sucedida, marca {@code published_at} para que o comando
 * não seja reenviado na próxima execução.</p>
 *
 * <p>O ciclo de vida de cada mensagem:</p>
 * <ol>
 *   <li>{@code OrderCommandService} grava a mensagem com {@code published_at = NULL}.</li>
 *   <li>Este serviço lê os registros {@code WHERE published_at IS NULL}.</li>
 *   <li>Serializa o payload como bytes raw e publica via {@link RabbitTemplate#send}
 *       (evita double-serialization do {@link org.springframework.amqp.rabbit.core.RabbitTemplate#convertAndSend}).</li>
 *   <li>Atualiza {@code published_at = now()} via {@link OrderOutboxMessage#markAsPublished()}.</li>
 * </ol>
 *
 * <p>Em caso de falha do broker, a mensagem permanece com {@code published_at = NULL}
 * e será reprocessada na próxima janela do scheduler — garantindo entrega eventual.</p>
 *
 * <p>O {@code fixedDelay} garante que a próxima execução só começa após o término
 * da anterior. Isso evita execuções paralelas que publicariam a mesma mensagem
 * duas vezes (necessariamente serializado pela constraint de unicidade é suficiente
 * aqui pois o receptor usa idempotência própria).</p>
 */
@Service
public class OrderOutboxPublisherService {

    private static final Logger logger = LoggerFactory.getLogger(OrderOutboxPublisherService.class);

    private final OrderOutboxRepository outboxRepository;
    private final RabbitTemplate        rabbitTemplate;

    public OrderOutboxPublisherService(OrderOutboxRepository outboxRepository,
                                       RabbitTemplate rabbitTemplate) {
        this.outboxRepository = outboxRepository;
        this.rabbitTemplate   = rabbitTemplate;
    }

    /**
     * Itera sobre mensagens pendentes e publica cada uma no RabbitMQ.
     *
     * <p>Configurável via {@code app.outbox.delay-ms} (padrão: 5000 ms).
     * Em produção, recomenda-se ajustar conforme o SLA do placeOrder:
     * valores entre 1000–5000 ms balanceiam latência e carga sobre o broker.</p>
     *
     * <p>Cada mensagem é publicada e salva em uma sub-transação própria para que
     * a falha de uma não reverta o commit das anteriores já bem-sucedidas.</p>
     */
    @Scheduled(fixedDelayString = "${app.outbox.delay-ms:5000}")
    public void publishPendingMessages() {
        List<OrderOutboxMessage> pending = outboxRepository.findByPublishedAtIsNull();

        if (pending.isEmpty()) {
            return;
        }

        logger.debug("Outbox relay: {} mensagem(ns) pendente(s) para publicação", pending.size());

        for (OrderOutboxMessage msg : pending) {
            publishSingle(msg);
        }
    }

    /**
     * Publica uma única mensagem do outbox e atualiza seu estado em transação própria.
     *
     * <p>A anotação {@code @Transactional} aqui garante que a atualização do
     * {@code published_at} só seja commitada se a publicação no RabbitMQ
     * não lançar exceção — se o broker falhar, o status permanece {@code null}.</p>
     *
     * <p>Usa {@link RabbitTemplate#send} com bytes raw em vez de
     * {@link RabbitTemplate#convertAndSend}: o payload já é JSON serializado
     * ({@code String}), e passar uma {@code String} ao {@code Jackson2JsonMessageConverter}
     * causaria double-serialization — a mensagem chegaria aos consumers como
     * {@code "\"{ ... }\""} em vez de {@code { ... }}.</p>
     *
     * @param msg Mensagem do outbox a ser publicada.
     */
    @Transactional
    public void publishSingle(OrderOutboxMessage msg) {
        try {
            // Publica no RabbitMQ usando exchange e routing key gravados na mensagem.
            // O payload já é JSON serializado — usa rabbitTemplate.send() com bytes raw
            // para evitar double-serialization que ocorreria com convertAndSend(String payload):
            // Jackson2JsonMessageConverter re-serializaria a String como JSON quoted → consumidores
            // receberiam "\"{ ... }\"" em vez de { ... } → MismatchedInputException.
            Message amqpMessage = MessageBuilder
                    .withBody(msg.getPayload().getBytes(StandardCharsets.UTF_8))
                    .andProperties(new MessageProperties())
                    .build();
            amqpMessage.getMessageProperties().setContentType(MessageProperties.CONTENT_TYPE_JSON);
            rabbitTemplate.send(msg.getExchange(), msg.getRoutingKey(), amqpMessage);

            // Marca como publicada na mesma transação do commit
            msg.markAsPublished();
            outboxRepository.save(msg);

            logger.info("Outbox relay: publicado eventType={} aggregateId={} outboxId={}",
                    msg.getEventType(), msg.getAggregateId(), msg.getId());

        } catch (AmqpException ex) {
            // Broker indisponível: loga e não atualiza published_at.
            // A mensagem será retentada na próxima janela do scheduler.
            logger.warn("Outbox relay: falha ao publicar outboxId={} eventType={} — retentará: {}",
                    msg.getId(), msg.getEventType(), ex.getMessage());
        }
    }
}
