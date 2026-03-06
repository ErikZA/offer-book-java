package com.vibranium.utils.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Testes unitários do {@link AbstractOutboxPublisher} em isolamento.
 *
 * <p>Verifica o Template Method Pattern: ciclo claim → publish → mark processed,
 * comportamento de retry (delegado à subclasse) e recover.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("[Unit] AbstractOutboxPublisher — Template Method")
class AbstractOutboxPublisherTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    private TestOutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new TestOutboxPublisher(rabbitTemplate, 50);
    }

    // =========================================================================
    // pollAndPublish — ciclo de polling
    // =========================================================================

    @Nested
    @DisplayName("pollAndPublish — ciclo de polling")
    class PollAndPublishTests {

        @Test
        @DisplayName("Deve publicar mensagem pendente e executar afterPublish")
        void shouldPublishPendingMessageAndCallAfterPublish() {
            // Arrange
            TestMessage msg = new TestMessage(
                    UUID.randomUUID(), "FundsReservedEvent",
                    "vibranium.events", "wallet.events.funds-reserved",
                    "{\"amount\":100}");
            publisher.setPendingMessages(List.of(msg));

            // Act
            publisher.pollAndPublish();

            // Assert
            verify(rabbitTemplate).send(
                    eq("vibranium.events"),
                    eq("wallet.events.funds-reserved"),
                    any(Message.class));
            assertThat(msg.published).isTrue();
        }

        @Test
        @DisplayName("Deve publicar múltiplas mensagens no mesmo ciclo")
        void shouldPublishMultipleMessagesInBatch() {
            TestMessage msg1 = new TestMessage(
                    UUID.randomUUID(), "EventA",
                    "exchange.a", "routing.a", "{\"a\":1}");
            TestMessage msg2 = new TestMessage(
                    UUID.randomUUID(), "EventB",
                    "exchange.b", "routing.b", "{\"b\":2}");
            publisher.setPendingMessages(List.of(msg1, msg2));

            publisher.pollAndPublish();

            verify(rabbitTemplate, times(2)).send(anyString(), anyString(), any(Message.class));
            assertThat(msg1.published).isTrue();
            assertThat(msg2.published).isTrue();
        }

        @Test
        @DisplayName("Não deve interagir com RabbitTemplate quando não há mensagens pendentes")
        void shouldDoNothingWhenNoPendingMessages() {
            publisher.setPendingMessages(List.of());

            publisher.pollAndPublish();

            verifyNoInteractions(rabbitTemplate);
        }

        @Test
        @DisplayName("Deve respeitar o batchSize passado para findPendingMessages")
        void shouldPassBatchSizeToFindPending() {
            publisher.setPendingMessages(List.of());

            publisher.pollAndPublish();

            assertThat(publisher.lastBatchSizeRequested).isEqualTo(50);
        }
    }

    // =========================================================================
    // beforePublish — claim atômico
    // =========================================================================

    @Nested
    @DisplayName("beforePublish — claim atômico")
    class BeforePublishTests {

        @Test
        @DisplayName("Deve pular mensagem quando beforePublish retorna false (claim falhou)")
        void shouldSkipMessageWhenBeforePublishReturnsFalse() {
            TestMessage msg = new TestMessage(
                    UUID.randomUUID(), "EventX",
                    "exchange.x", "routing.x", "{\"x\":1}");
            msg.claimable = false; // simula claim falhado
            publisher.setUseClaimCheck(true);
            publisher.setPendingMessages(List.of(msg));

            publisher.pollAndPublish();

            verifyNoInteractions(rabbitTemplate);
            assertThat(msg.published).isFalse();
        }

        @Test
        @DisplayName("Deve publicar quando beforePublish retorna true (claim OK)")
        void shouldPublishWhenBeforePublishReturnsTrue() {
            TestMessage msg = new TestMessage(
                    UUID.randomUUID(), "EventY",
                    "exchange.y", "routing.y", "{\"y\":1}");
            msg.claimable = true;
            publisher.setUseClaimCheck(true);
            publisher.setPendingMessages(List.of(msg));

            publisher.pollAndPublish();

            verify(rabbitTemplate).send(eq("exchange.y"), eq("routing.y"), any(Message.class));
            assertThat(msg.published).isTrue();
        }
    }

    // =========================================================================
    // doPublish — lógica core
    // =========================================================================

    @Nested
    @DisplayName("doPublish — lógica core de publicação")
    class DoPublishTests {

        @Test
        @DisplayName("Deve enviar mensagem com exchange e routingKey corretos")
        void shouldSendWithCorrectExchangeAndRoutingKey() {
            TestMessage msg = new TestMessage(
                    UUID.randomUUID(), "TestEvent",
                    "test.exchange", "test.routing", "{\"test\":true}");

            publisher.doPublish(msg);

            verify(rabbitTemplate).send(
                    eq("test.exchange"),
                    eq("test.routing"),
                    any(Message.class));
        }

        @Test
        @DisplayName("Deve chamar afterPublish após envio bem-sucedido")
        void shouldCallAfterPublishOnSuccess() {
            TestMessage msg = new TestMessage(
                    UUID.randomUUID(), "TestEvent",
                    "ex", "rk", "{}");

            publisher.doPublish(msg);

            assertThat(msg.published).isTrue();
        }

        @Test
        @DisplayName("Não deve chamar afterPublish se RabbitTemplate lançar exceção")
        void shouldNotCallAfterPublishOnFailure() {
            TestMessage msg = new TestMessage(
                    UUID.randomUUID(), "TestEvent",
                    "ex", "rk", "{}");
            doThrow(new AmqpException("broker down"))
                    .when(rabbitTemplate).send(anyString(), anyString(), any(Message.class));

            try {
                publisher.doPublish(msg);
            } catch (AmqpException ignored) {
                // esperado
            }

            assertThat(msg.published).isFalse();
        }
    }

    // =========================================================================
    // doRecover — recuperação de falhas permanentes
    // =========================================================================

    @Nested
    @DisplayName("doRecover — recuperação de falhas permanentes")
    class DoRecoverTests {

        @Test
        @DisplayName("Deve logar erro sem propagar exceção")
        void shouldLogErrorWithoutPropagating() {
            TestMessage msg = new TestMessage(
                    UUID.randomUUID(), "FailEvent",
                    "ex", "rk", "{}");
            Exception cause = new AmqpException("permanent failure");

            // doRecover não deve lançar exceção
            publisher.doRecover(cause, msg);

            // Verificação: mensagem NÃO foi marcada como publicada
            assertThat(msg.published).isFalse();
        }
    }

    // =========================================================================
    // OutboxConfigProperties — validação
    // =========================================================================

    @Nested
    @DisplayName("OutboxConfigProperties — validação de parâmetros")
    class ConfigPropertiesTests {

        @Test
        @DisplayName("Deve criar properties com valores válidos")
        void shouldCreateWithValidValues() {
            OutboxConfigProperties props = new OutboxConfigProperties(100, 2000);
            assertThat(props.batchSize()).isEqualTo(100);
            assertThat(props.pollingIntervalMs()).isEqualTo(2000L);
        }

        @Test
        @DisplayName("Deve rejeitar batchSize <= 0")
        void shouldRejectNonPositiveBatchSize() {
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> new OutboxConfigProperties(0, 1000));
        }

        @Test
        @DisplayName("Deve rejeitar pollingIntervalMs <= 0")
        void shouldRejectNonPositivePollingInterval() {
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> new OutboxConfigProperties(100, 0));
        }
    }

    // =========================================================================
    // Stub concreto para teste
    // =========================================================================

    /**
     * Mensagem de teste simples — substitui a entidade JPA.
     */
    static class TestMessage {
        final UUID id;
        final String eventType;
        final String exchange;
        final String routingKey;
        final String payload;
        boolean published = false;
        boolean claimable = true;

        TestMessage(UUID id, String eventType, String exchange,
                    String routingKey, String payload) {
            this.id = id;
            this.eventType = eventType;
            this.exchange = exchange;
            this.routingKey = routingKey;
            this.payload = payload;
        }
    }

    /**
     * Implementação concreta para teste — preenche todos os extension points
     * com lógica mínima controlável.
     */
    static class TestOutboxPublisher extends AbstractOutboxPublisher<TestMessage> {

        private List<TestMessage> pendingMessages = List.of();
        private boolean useClaimCheck = false;
        int lastBatchSizeRequested = -1;

        TestOutboxPublisher(RabbitTemplate rabbitTemplate, int batchSize) {
            super(rabbitTemplate, batchSize);
        }

        void setPendingMessages(List<TestMessage> messages) {
            this.pendingMessages = messages;
        }

        void setUseClaimCheck(boolean useClaimCheck) {
            this.useClaimCheck = useClaimCheck;
        }

        @Override
        protected List<TestMessage> findPendingMessages(int batchSize) {
            this.lastBatchSizeRequested = batchSize;
            return pendingMessages;
        }

        @Override
        protected boolean beforePublish(TestMessage message) {
            if (useClaimCheck) {
                return message.claimable;
            }
            return super.beforePublish(message);
        }

        @Override
        protected Message buildAmqpMessage(TestMessage message) {
            return MessageBuilder
                    .withBody(message.payload.getBytes(StandardCharsets.UTF_8))
                    .setContentType("application/json")
                    .build();
        }

        @Override
        protected String resolveExchange(TestMessage message) {
            return message.exchange;
        }

        @Override
        protected String resolveRoutingKey(TestMessage message) {
            return message.routingKey;
        }

        @Override
        protected void afterPublish(TestMessage message) {
            message.published = true;
        }

        @Override
        protected Object getMessageId(TestMessage message) {
            return message.id;
        }

        @Override
        protected String getEventType(TestMessage message) {
            return message.eventType;
        }
    }
}
