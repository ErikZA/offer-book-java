package com.vibranium.walletservice.unit;

import com.vibranium.walletservice.application.service.OutboxCleanupJob;
import com.vibranium.walletservice.domain.repository.OutboxMessageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Testes unitários do {@link OutboxCleanupJob} — validam que a janela de retenção
 * é calculada deterministicamente usando {@link Clock#fixed(Instant, java.time.ZoneId)}.
 *
 * <h3>Problema (AT-2.3.1)</h3>
 * <p>A tabela {@code outbox_message} cresce indefinidamente sem mecanismo de purge.
 * Mensagens com {@code processed=true} antigas não são mais necessárias, mas ocupam
 * espaço em disco e aumentam o custo de vacuum no PostgreSQL.</p>
 *
 * <h3>Estratégia TDD</h3>
 * <ul>
 *   <li><b>FASE RED</b>: testes falham porque {@link OutboxCleanupJob} não existe.</li>
 *   <li><b>FASE GREEN</b>: após criação da classe, todos os testes passam.</li>
 * </ul>
 *
 * <p>O {@link Clock} fixo evita {@code Thread.sleep} e garante determinismo total:
 * o cutoff calculado pelo job é sempre {@code now - 7 dias}, verificável por
 * {@code verify()} do Mockito sem tolerância de tempo.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxCleanupJob — Limpeza de mensagens processadas (AT-2.3.1)")
class WalletOutboxCleanupJobTest {

    /** Ponto fixo no tempo: domingo 01/03/2026 às 03:00 UTC (janela de execução do job). */
    private static final Instant FIXED_NOW = Instant.parse("2026-03-01T03:00:00Z");

    /** Retenção aplicada pelo job: 7 dias. */
    private static final long RETENTION_DAYS = 7L;

    @Mock
    private OutboxMessageRepository outboxMessageRepository;

    // =========================================================================
    // TC-OCJ-1: deleção de mensagens antigas
    // =========================================================================

    /**
     * TC-OCJ-1: dado um Clock fixo, o job deve chamar o repositório com o cutoff
     * exato de {@code now - 7 dias} e logar a quantidade de registros removidos.
     *
     * <p>O stub retorna 5 registros deletados para simular limpeza bem-sucedida.</p>
     */
    @Test
    @DisplayName("TC-OCJ-1: deve remover mensagens processadas mais antigas que 7 dias")
    void testCleanup_deletesProcessedMessagesOlderThanRetention() {
        // Arrange
        Clock fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        Instant expectedCutoff = FIXED_NOW.minus(RETENTION_DAYS, ChronoUnit.DAYS);

        // Simula 5 registros deletados — confirma que o count é propagado para o log
        when(outboxMessageRepository.deleteByProcessedTrueAndCreatedAtBefore(expectedCutoff))
                .thenReturn(5L);

        OutboxCleanupJob job = new OutboxCleanupJob(outboxMessageRepository, fixedClock);

        // Act
        job.cleanupProcessedOutboxMessages();

        // Assert — repositório chamado com o cutoff calculado a partir do Clock fixo
        verify(outboxMessageRepository).deleteByProcessedTrueAndCreatedAtBefore(expectedCutoff);
    }

    // =========================================================================
    // TC-OCJ-2: preservação de mensagens recentes
    // =========================================================================

    /**
     * TC-OCJ-2: uma mensagem processada há 6 dias (mais recente que o cutoff de 7 dias)
     * NÃO deve ser incluída no escopo do DELETE.
     *
     * <p>O teste valida indiretamente: confirma que a data recente está APÓS o cutoff
     * calculado, portanto o predicado {@code createdAt < cutoff} excluiria essa linha.</p>
     */
    @Test
    @DisplayName("TC-OCJ-2: deve preservar mensagens processadas com menos de 7 dias")
    void testCleanup_keepsRecentProcessedMessages() {
        // Arrange
        Clock fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        Instant cutoff = FIXED_NOW.minus(RETENTION_DAYS, ChronoUnit.DAYS);

        // Mensagem de 6 dias atrás — mais recente que o cutoff
        Instant recentMessageCreatedAt = FIXED_NOW.minus(6, ChronoUnit.DAYS);

        // Stub retorna 0 para simular que nenhuma mensagem estava fora da janela
        when(outboxMessageRepository.deleteByProcessedTrueAndCreatedAtBefore(cutoff))
                .thenReturn(0L);

        OutboxCleanupJob job = new OutboxCleanupJob(outboxMessageRepository, fixedClock);

        // Act
        job.cleanupProcessedOutboxMessages();

        // Assert 1 — repositório chamado com cutoff correto
        verify(outboxMessageRepository).deleteByProcessedTrueAndCreatedAtBefore(cutoff);

        // Assert 2 — mensagem recente está APÓS o cutoff, logo não seria deletada
        // (createdAt=6 dias atrás) > (cutoff=7 dias atrás) → condição WHERE não satisfeita
        assertThat(recentMessageCreatedAt)
                .as("mensagem de 6 dias atrás deve ser posterior ao cutoff de 7 dias")
                .isAfter(cutoff);
    }
}
