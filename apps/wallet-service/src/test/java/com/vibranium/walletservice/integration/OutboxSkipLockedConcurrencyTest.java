package com.vibranium.walletservice.integration;

import com.vibranium.walletservice.AbstractIntegrationTest;
import com.vibranium.walletservice.domain.model.OutboxMessage;
import com.vibranium.walletservice.domain.repository.OutboxMessageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testa que SELECT FOR UPDATE SKIP LOCKED distribui mensagens entre
 * múltiplas threads (simulando múltiplas instâncias) sem duplicatas.
 *
 * <p>Este teste valida a segurança horizontal: quando N "instâncias" (threads)
 * competem pelo mesmo lote de mensagens pendentes, cada mensagem é processada
 * por EXATAMENTE uma instância — sem duplicatas nem mensagens perdidas.</p>
 */
@DisplayName("[Integration] OutboxRepository — SKIP LOCKED concurrency")
class OutboxSkipLockedConcurrencyTest extends AbstractIntegrationTest {

    @Autowired private OutboxMessageRepository outboxRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("N threads concorrentes com SKIP LOCKED não devem processar a mesma mensagem")
    void skipLockedShouldPreventDuplicateProcessing() throws Exception {
        // Arrange: insere 100 mensagens pendentes
        int totalMessages = 100;
        Set<UUID> insertedIds = ConcurrentHashMap.newKeySet();

        for (int i = 0; i < totalMessages; i++) {
            UUID id = UUID.randomUUID();
            insertedIds.add(id);
            jdbcTemplate.update("""
                INSERT INTO outbox_message (id, event_type, aggregate_id, payload, created_at, processed)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, "FundsReservedEvent", UUID.randomUUID().toString(),
                "{\"test\":" + i + "}", Timestamp.from(Instant.now()), false);
        }

        // Act: 5 threads competem para processar mensagens via SKIP LOCKED
        int threadCount = 5;
        int batchSize = 25; // Cada thread tenta pegar até 25
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        Set<UUID> allClaimed = ConcurrentHashMap.newKeySet();
        List<Future<Set<UUID>>> futures = new ArrayList<>();

        for (int t = 0; t < threadCount; t++) {
            futures.add(executor.submit(() -> {
                barrier.await(); // Sincroniza para máxima concorrência
                Set<UUID> claimed = new HashSet<>();

                // Cada thread faz múltiplas rodadas até não encontrar mais pendentes
                while (true) {
                    // Transação explícita mantém o lock FOR UPDATE durante o processamento
                    List<UUID> batchIds = transactionTemplate.execute(status -> {
                        List<OutboxMessage> batch = outboxRepository.findPendingWithLock(batchSize);
                        List<UUID> ids = new java.util.ArrayList<>();
                        for (OutboxMessage msg : batch) {
                            ids.add(msg.getId());
                            outboxRepository.claimAndMarkProcessed(msg.getId());
                        }
                        return ids;
                    });
                    if (batchIds == null || batchIds.isEmpty()) break;
                    claimed.addAll(batchIds);
                }
                return claimed;
            }));
        }

        // Collect
        for (Future<Set<UUID>> f : futures) {
            Set<UUID> threadClaimed = f.get(30, TimeUnit.SECONDS);
            // Verifica que nenhuma mensagem foi processada por mais de uma thread
            for (UUID id : threadClaimed) {
                boolean wasNew = allClaimed.add(id);
                assertThat(wasNew)
                    .as("Mensagem %s não deve ser processada por múltiplas threads", id)
                    .isTrue();
            }
        }

        executor.shutdown();

        // Assert: TODAS as mensagens foram processadas (nenhuma perdida)
        assertThat(allClaimed).hasSize(totalMessages);

        // Assert: TODAS marcadas como processed=true no banco
        long unpublished = outboxRepository.countByProcessedFalseAndEventType("FundsReservedEvent");
        assertThat(unpublished).isZero();
    }
}
