package com.vibranium.orderservice.integration;

import com.vibranium.orderservice.application.service.OrderOutboxPublisherService;
import com.vibranium.orderservice.domain.model.OrderOutboxMessage;
import com.vibranium.orderservice.domain.repository.OrderOutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Testa o comportamento de {@code @Retryable} e {@code @Recover} no
 * {@link OrderOutboxPublisherService}.
 *
 * <p>Usa {@code @MockitoBean} para substituir o {@link RabbitTemplate}
 * por um mock, permitindo simular falhas do broker sem infraestrutura real.</p>
 *
 * <p>O scheduler é desabilitado via {@code delay-ms=3600000} para isolar
 * a invocação direta de {@code publishSingle()}.</p>
 *
 * <p>O {@code publishSingle()} é chamado diretamente no bean Spring
 * (proxy AOP), garantindo que {@code @Retryable} e {@code @Recover}
 * sejam interceptados corretamente.</p>
 */
@TestPropertySource(properties = "app.outbox.delay-ms=3600000")
@DisplayName("[Unit] OrderOutbox — @Retryable + @Recover")
class OrderOutboxRetryTest extends AbstractIntegrationTest {

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private OrderOutboxPublisherService publisherService;

    @Autowired
    private OrderOutboxRepository outboxRepository;

    @BeforeEach
    void setup() {
        outboxRepository.deleteAll();
        reset(rabbitTemplate);
    }

    @Test
    @DisplayName("@Retryable deve retentar após AmqpException e publicar na tentativa seguinte")
    void shouldRetryAndSucceedOnSubsequentAttempt() {
        OrderOutboxMessage msg = createAndSaveOutboxMessage();

        // Falha 2x, sucesso na 3ª tentativa (maxAttempts=3)
        doThrow(new AmqpException("Broker indisponível"))
                .doThrow(new AmqpException("Broker indisponível"))
                .doNothing()
                .when(rabbitTemplate).send(anyString(), anyString(), any(Message.class));

        publisherService.publishSingle(msg);

        // Verifica que houve 3 chamadas (2 falhas + 1 sucesso)
        verify(rabbitTemplate, times(3)).send(anyString(), anyString(), any(Message.class));

        // Mensagem deve estar marcada como publicada
        OrderOutboxMessage saved = outboxRepository.findById(msg.getId()).orElseThrow();
        assertThat(saved.getPublishedAt())
                .as("published_at deve ser preenchido após retry bem-sucedido")
                .isNotNull();
    }

    @Test
    @DisplayName("@Recover deve ser chamado após esgotar maxAttempts, sem propagar exceção")
    void shouldCallRecoverAfterExhaustingRetries() {
        OrderOutboxMessage msg = createAndSaveOutboxMessage();

        // Todas as tentativas falham
        doThrow(new AmqpException("Broker permanentemente indisponível"))
                .when(rabbitTemplate).send(anyString(), anyString(), any(Message.class));

        // @Recover absorve a exceção — não deve propagar
        assertThatCode(() -> publisherService.publishSingle(msg))
                .doesNotThrowAnyException();

        // Verifica que houve maxAttempts (3) chamadas
        verify(rabbitTemplate, times(3)).send(anyString(), anyString(), any(Message.class));

        // Mensagem NÃO deve ser marcada como publicada
        OrderOutboxMessage saved = outboxRepository.findById(msg.getId()).orElseThrow();
        assertThat(saved.getPublishedAt())
                .as("published_at deve permanecer null após @Recover (mensagem fica para próximo ciclo)")
                .isNull();
    }

    @Test
    @DisplayName("Mensagem não deve ser marcada como publicada quando @Recover é acionado")
    void messageShouldRemainUnpublishedAfterRecovery() {
        OrderOutboxMessage msg = createAndSaveOutboxMessage();

        doThrow(new AmqpException("Falha persistente"))
                .when(rabbitTemplate).send(anyString(), anyString(), any(Message.class));

        publisherService.publishSingle(msg);

        // Confirma que a mensagem aparece na query de pendentes
        assertThat(outboxRepository.findByPublishedAtIsNull())
                .as("Mensagem deve permanecer na lista de pendentes após @Recover")
                .extracting(OrderOutboxMessage::getId)
                .contains(msg.getId());
    }

    private OrderOutboxMessage createAndSaveOutboxMessage() {
        OrderOutboxMessage msg = new OrderOutboxMessage(
                UUID.randomUUID(), "Order", "RetryTestEvent",
                "vibranium.commands", "test.retry",
                "{\"test\":true}");
        return outboxRepository.saveAndFlush(msg);
    }
}
