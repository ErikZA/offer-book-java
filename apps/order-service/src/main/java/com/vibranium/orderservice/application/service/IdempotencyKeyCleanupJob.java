package com.vibranium.orderservice.application.service;

import com.vibranium.orderservice.domain.repository.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Job de limpeza da tabela {@code tb_processed_events}.
 *
 * <p>Aplica a política de retenção de 7 dias: remove entradas cujo
 * {@code processed_at} é anterior ao instante corrente menos 7 dias,
 * evitando crescimento ilimitado da tabela sem comprometer a proteção
 * contra duplicatas de mensagens recentes.</p>
 *
 * <p>Executa às 02:00 UTC todo domingo (configurável via {@code app.cleanup.cron}).</p>
 *
 * <p>A anotação {@code @Transactional} garante consistência do DELETE em lote:
 * se ocorrer erro de infra, o rollback preserva todos os registros.</p>
 */
@Service
public class IdempotencyKeyCleanupJob {

    private static final Logger logger = LoggerFactory.getLogger(IdempotencyKeyCleanupJob.class);

    /** Período de retenção em dias antes da remoção. */
    private static final long RETENTION_DAYS = 7;

    private final ProcessedEventRepository processedEventRepository;

    public IdempotencyKeyCleanupJob(ProcessedEventRepository processedEventRepository) {
        this.processedEventRepository = processedEventRepository;
    }

    /**
     * Remove eventos processados com mais de {@value #RETENTION_DAYS} dias.
     *
     * <p>Agendado para executar às 02:00h UTC todo domingo.
     * O {@code cron} pode ser sobrescrito via {@code app.cleanup.cron}.</p>
     */
    @Scheduled(cron = "${app.cleanup.cron:0 0 2 * * SUN}")
    @Transactional
    public void cleanupExpiredKeys() {
        Instant cutoff = Instant.now().minus(RETENTION_DAYS, ChronoUnit.DAYS);

        logger.info("Cleanup de idempotência: removendo eventos anteriores a {}", cutoff);

        processedEventRepository.deleteByProcessedAtBefore(cutoff);

        logger.info("Cleanup de idempotência: concluído — cutoff={}", cutoff);
    }
}
