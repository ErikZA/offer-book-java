package com.vibranium.walletservice.application.service;

import com.vibranium.walletservice.domain.repository.IdempotencyKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Job de limpeza da tabela {@code idempotency_key} no wallet-service.
 *
 * <h3>Problema (AT-2.3.1)</h3>
 * <p>Chaves de idempotência com mais de {@value #RETENTION_DAYS} dias não protegem
 * mais contra re-entrega de mensagens (o RabbitMQ não re-entrega mensagens tão
 * antigas em condições normais). Mantê-las indefinidamente degrada o lookup por PK
 * e aumenta o custo de autovacuum no PostgreSQL.</p>
 *
 * <h3>Política de retenção</h3>
 * <p>Remove chaves cujo {@code processed_at < now - 7 dias}. Chaves recentes
 * (dentro da janela de proteção) são preservadas para garantir idempotência
 * contra at-least-once delivery do RabbitMQ.</p>
 *
 * <h3>Segurança e idempotência</h3>
 * <p>O DELETE é atômico via {@code @Transactional}: em caso de falha de infra,
 * o rollback preserva todos os registros. A operação pode ser re-executada sem
 * efeitos colaterais.</p>
 *
 * <h3>Testabilidade</h3>
 * <p>O {@link Clock} é injetado via construtor (definido em
 * {@link com.vibranium.walletservice.config.TimeConfig}). Em produção usa
 * {@code Clock.systemUTC()}; em testes, {@code Clock.fixed(...)} garante
 * determinismo sem {@code Thread.sleep}.</p>
 *
 * <p>Executa às 04:00 UTC todo domingo (configurável via
 * {@code app.cleanup.idempotency.cron}).</p>
 */
@Component
public class IdempotencyKeyCleanupJob {

    private static final Logger logger = LoggerFactory.getLogger(IdempotencyKeyCleanupJob.class);

    /** Período de retenção em dias antes da remoção de chaves de idempotência. */
    private static final long RETENTION_DAYS = 7L;

    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final Clock clock;

    /**
     * Cria o job de limpeza de chaves de idempotência.
     *
     * @param idempotencyKeyRepository repositório JPA da tabela idempotency_key.
     * @param clock                    abstração temporal — use {@code Clock.fixed(...)}
     *                                 em testes para determinismo; nunca use {@code Instant.now()}.
     */
    public IdempotencyKeyCleanupJob(IdempotencyKeyRepository idempotencyKeyRepository, Clock clock) {
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.clock = clock;
    }

    /**
     * Remove chaves de idempotência com mais de {@value #RETENTION_DAYS} dias.
     *
     * <p>Agendado para executar às 04:00h UTC todo domingo.
     * O {@code cron} pode ser sobrescrito via {@code app.cleanup.idempotency.cron}.</p>
     *
     * <p>O predicado {@code processed_at < cutoff} garante que apenas chaves fora
     * da janela de proteção sejam deletadas em lote (uma única instrução SQL DELETE).</p>
     */
    @Scheduled(cron = "${app.cleanup.idempotency.cron:0 0 4 * * SUN}")
    @Transactional
    public void cleanupExpiredKeys() {
        // Usa clock.instant() para permitir testes determinísticos (AT-2.3.1)
        Instant cutoff = clock.instant().minus(RETENTION_DAYS, ChronoUnit.DAYS);

        logger.info("Cleanup idempotência: removendo chaves anteriores a {}", cutoff);

        long deleted = idempotencyKeyRepository.deleteByProcessedAtBefore(cutoff);

        logger.info("Cleanup idempotência: concluído — {} registros removidos, cutoff={}", deleted, cutoff);
    }
}
