package com.vibranium.orderservice.integration;

import com.vibranium.orderservice.domain.model.OrderOutboxMessage;
import com.vibranium.orderservice.domain.repository.OrderOutboxRepository;
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
 * Testa que SELECT FOR UPDATE SKIP LOCKED no order-service distribui
 * mensagens entre múltiplas threads sem duplicatas.
 */
@DisplayName("[Integration] OrderOutboxRepository — SKIP LOCKED concurrency")
class OrderOutboxSkipLockedConcurrencyTest extends AbstractIntegrationTest {

    @Autowired private OrderOutboxRepository outboxRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("N threads concorrentes com SKIP LOCKED não devem processar a mesma mensagem")
    void skipLockedShouldPreventDuplicateProcessing() throws Exception {
        int totalMessages = 100;
        Set<UUID> allClaimed = ConcurrentHashMap.newKeySet();
        Set<UUID> insertedIds = ConcurrentHashMap.newKeySet();

        // Limpa TODAS as mensagens pendentes para evitar interferência de dados residuais
        jdbcTemplate.update("DELETE FROM tb_order_outbox WHERE published_at IS NULL");

        // Insere 100 mensagens pendentes e rastreia os IDs inseridos
        for (int i = 0; i < totalMessages; i++) {
            UUID id = UUID.randomUUID();
            insertedIds.add(id);
            jdbcTemplate.update("""
                INSERT INTO tb_order_outbox (id, aggregate_type, aggregate_id, event_type, exchange, routing_key, payload, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """,
                id, "Order", UUID.randomUUID(),
                "SkipLockedTestEvent", "vibranium.commands", "wallet.commands.reserve-funds",
                "{\"test\":" + i + "}", Timestamp.from(Instant.now()));
        }

        // 5 threads competem
        int threadCount = 5;
        int batchSize = 25;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        List<Future<Set<UUID>>> futures = new ArrayList<>();

        for (int t = 0; t < threadCount; t++) {
            futures.add(executor.submit(() -> {
                barrier.await();
                Set<UUID> claimed = new HashSet<>();
                while (true) {
                    // Transação explícita mantém o lock FOR UPDATE durante o processamento
                    List<UUID> batchIds = transactionTemplate.execute(status -> {
                        List<OrderOutboxMessage> batch = outboxRepository.findPendingWithLock(batchSize);
                        List<UUID> ids = new ArrayList<>();
                        for (OrderOutboxMessage msg : batch) {
                            ids.add(msg.getId());
                            msg.markAsPublished();
                            outboxRepository.save(msg);
                        }
                        return ids;
                    });
                    if (batchIds == null || batchIds.isEmpty()) break;
                    claimed.addAll(batchIds);
                }
                return claimed;
            }));
        }

        for (Future<Set<UUID>> f : futures) {
            Set<UUID> threadClaimed = f.get(30, TimeUnit.SECONDS);
            for (UUID id : threadClaimed) {
                // Conta apenas mensagens que nós inserimos (ignora scheduler background)
                if (insertedIds.contains(id)) {
                    assertThat(allClaimed.add(id))
                        .as("Mensagem %s não deve ser duplicada", id).isTrue();
                }
            }
        }

        executor.shutdown();

        // Todas as nossas mensagens devem ter sido processadas (por threads ou pelo scheduler)
        long remaining = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM tb_order_outbox WHERE event_type = 'SkipLockedTestEvent' AND published_at IS NULL",
            Long.class);
        assertThat(remaining).as("Todas as mensagens inseridas devem estar publicadas").isZero();
        // Nenhuma duplicata entre as threads
        assertThat(allClaimed).hasSizeLessThanOrEqualTo(totalMessages);
    }
}
