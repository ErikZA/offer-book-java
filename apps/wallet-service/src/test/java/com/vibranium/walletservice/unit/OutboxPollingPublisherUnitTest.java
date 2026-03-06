package com.vibranium.walletservice.unit;

import com.vibranium.walletservice.config.OutboxProperties;
import com.vibranium.walletservice.domain.model.OutboxMessage;
import com.vibranium.walletservice.domain.repository.OutboxMessageRepository;
import com.vibranium.walletservice.infrastructure.outbox.OutboxPublisherService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Testa o loop de polling do OutboxPublisherService em isolamento (sem Spring context).
 *
 * <p>Verifica que o método @Scheduled:</p>
 * <ol>
 *   <li>Chama findPendingWithLock(batchSize) para obter mensagens com SKIP LOCKED</li>
 *   <li>Publica cada mensagem no RabbitMQ com exchange/routingKey corretos</li>
 *   <li>Marca como processed=true via claimAndMarkProcessed</li>
 *   <li>Não faz nada quando não há mensagens pendentes</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("[Unit] OutboxPublisherService — polling loop")
class OutboxPollingPublisherUnitTest {

    @Mock private OutboxMessageRepository outboxRepository;
    @Mock private RabbitTemplate rabbitTemplate;

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private OutboxPublisherService publisher;

    @BeforeEach
    void setUp() {
        // Cria OutboxProperties com valores default para testes
        OutboxProperties properties = new OutboxProperties(
                50, // batchSize
                new OutboxProperties.PollingProperties(1000L) // intervalMs
        );
        publisher = new OutboxPublisherService(rabbitTemplate, outboxRepository, properties, meterRegistry);
    }

    @Test
    @DisplayName("Deve publicar mensagem pendente e marcar como processada")
    void shouldPublishAndMarkProcessed() {
        // Arrange
        UUID eventId = UUID.randomUUID();
        OutboxMessage msg = OutboxMessage.create("FundsReservedEvent",
                UUID.randomUUID().toString(), "{\"amount\":100}");
        // Precisamos setar o ID via reflection já que OutboxMessage.create() gera UUID via JPA
        setId(msg, eventId);

        when(outboxRepository.findPendingWithLock(anyInt())).thenReturn(List.of(msg));
        when(outboxRepository.claimAndMarkProcessed(eventId)).thenReturn(1);

        // Act
        publisher.publishPendingMessages();

        // Assert
        verify(rabbitTemplate).send(
            eq("vibranium.events"),
            eq("wallet.events.funds-reserved"),
            any(Message.class));
        verify(outboxRepository).claimAndMarkProcessed(eventId);
    }

    @Test
    @DisplayName("Não deve fazer nada quando não há mensagens pendentes")
    void shouldDoNothingWhenNoPendingMessages() {
        when(outboxRepository.findPendingWithLock(anyInt())).thenReturn(List.of());

        publisher.publishPendingMessages();

        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    @DisplayName("Deve pular mensagem já processada por outra instância (claim retorna 0)")
    void shouldSkipAlreadyClaimedMessage() {
        UUID eventId = UUID.randomUUID();
        OutboxMessage msg = OutboxMessage.create("FundsReservedEvent",
                UUID.randomUUID().toString(), "{\"amount\":50}");
        setId(msg, eventId);

        when(outboxRepository.findPendingWithLock(anyInt())).thenReturn(List.of(msg));
        when(outboxRepository.claimAndMarkProcessed(eventId)).thenReturn(0);

        publisher.publishPendingMessages();

        verifyNoInteractions(rabbitTemplate);
    }

    // --- Helper: seta o ID via reflection (campo gerenciado pelo JPA) ---
    private void setId(OutboxMessage msg, UUID id) {
        try {
            var field = OutboxMessage.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(msg, id);
        } catch (Exception e) {
            throw new RuntimeException("Falha ao setar ID do OutboxMessage via reflection", e);
        }
    }
}
