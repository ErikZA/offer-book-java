package com.vibranium.walletservice.unit;

import com.vibranium.walletservice.application.service.IdempotencyKeyCleanupJob;
import com.vibranium.walletservice.domain.repository.IdempotencyKeyRepository;
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
 * Testes unitários do {@link IdempotencyKeyCleanupJob} — validam que a janela de
 * retenção de chaves de idempotência é calculada deterministicamente via
 * {@link Clock#fixed(Instant, java.time.ZoneId)}.
 *
 * <h3>Problema (AT-2.3.1)</h3>
 * <p>A tabela {@code idempotency_key} cresce indefinidamente. Chaves com mais de
 * 7 dias não protegem mais contra re-entrega (o broker não re-entrega mensagens
 * tão antigas), porém ocupam espaço e degradam o lookup de chegada por PK.</p>
 *
 * <h3>Estratégia TDD</h3>
 * <ul>
 *   <li><b>FASE RED</b>: testes falham porque {@link IdempotencyKeyCleanupJob} não existe.</li>
 *   <li><b>FASE GREEN</b>: após criação da classe, todos os testes passam.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IdempotencyKeyCleanupJob — Limpeza de chaves de idempotência (AT-2.3.1)")
class WalletIdempotencyCleanupJobTest {

    /** Ponto fixo no tempo: domingo 01/03/2026 às 04:00 UTC (janela de execução do job). */
    private static final Instant FIXED_NOW = Instant.parse("2026-03-01T04:00:00Z");

    /** Retenção aplicada pelo job: 7 dias. */
    private static final long RETENTION_DAYS = 7L;

    @Mock
    private IdempotencyKeyRepository idempotencyKeyRepository;

    // =========================================================================
    // TC-IKJ-1: deleção de chaves antigas
    // =========================================================================

    /**
     * TC-IKJ-1: dado um Clock fixo, o job deve chamar o repositório com o cutoff
     * exato de {@code now - 7 dias} e logar a quantidade de registros removidos.
     *
     * <p>O stub retorna 12 registros deletados para simular limpeza bem-sucedida.</p>
     */
    @Test
    @DisplayName("TC-IKJ-1: deve remover chaves de idempotência mais antigas que 7 dias")
    void testCleanup_deletesKeysOlderThanRetention() {
        // Arrange
        Clock fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        Instant expectedCutoff = FIXED_NOW.minus(RETENTION_DAYS, ChronoUnit.DAYS);

        // Simula 12 registros deletados
        when(idempotencyKeyRepository.deleteByProcessedAtBefore(expectedCutoff))
                .thenReturn(12L);

        IdempotencyKeyCleanupJob job = new IdempotencyKeyCleanupJob(idempotencyKeyRepository, fixedClock);

        // Act
        job.cleanupExpiredKeys();

        // Assert — repositório chamado com o cutoff correto
        verify(idempotencyKeyRepository).deleteByProcessedAtBefore(expectedCutoff);
    }

    // =========================================================================
    // TC-IKJ-2: preservação de chaves recentes
    // =========================================================================

    /**
     * TC-IKJ-2: uma chave processada há 6 dias (mais recente que o cutoff de 7 dias)
     * NÃO deve ser incluída no escopo do DELETE.
     *
     * <p>O teste valida indiretamente: confirma que a data recente está APÓS o cutoff
     * calculado, portanto o predicado {@code processedAt < cutoff} excluiria essa linha.</p>
     */
    @Test
    @DisplayName("TC-IKJ-2: deve preservar chaves de idempotência com menos de 7 dias")
    void testCleanup_keepsRecentKeys() {
        // Arrange
        Clock fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        Instant cutoff = FIXED_NOW.minus(RETENTION_DAYS, ChronoUnit.DAYS);

        // Chave de 6 dias atrás — mais recente que o cutoff
        Instant recentKeyProcessedAt = FIXED_NOW.minus(6, ChronoUnit.DAYS);

        // Stub retorna 0 — nenhuma chave estava fora da janela de proteção
        when(idempotencyKeyRepository.deleteByProcessedAtBefore(cutoff))
                .thenReturn(0L);

        IdempotencyKeyCleanupJob job = new IdempotencyKeyCleanupJob(idempotencyKeyRepository, fixedClock);

        // Act
        job.cleanupExpiredKeys();

        // Assert 1 — repositório chamado com cutoff correto
        verify(idempotencyKeyRepository).deleteByProcessedAtBefore(cutoff);

        // Assert 2 — chave recente está APÓS o cutoff, logo não seria deletada
        assertThat(recentKeyProcessedAt)
                .as("chave de 6 dias atrás deve ser posterior ao cutoff de 7 dias")
                .isAfter(cutoff);
    }
}
