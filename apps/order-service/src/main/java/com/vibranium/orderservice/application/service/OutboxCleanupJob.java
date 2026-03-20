package com.vibranium.orderservice.application.service;

import com.vibranium.orderservice.domain.repository.OrderOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Job de limpeza da tabela {@code outbox_message} no order-service.
 *
 * <h3>Problema (AT-2.3.1)</h3>
 * <p>Mensagens com {@code published_at != null} e mais de {@value #RETENTION_DAYS} dias
 * já foram publicadas com sucesso no RabbitMQ e não têm utilidade operacional.
 * Mantê-las indefinidamente aumenta o custo de {@code VACUUM} e degrada a
 * performance do índice parcial {@code WHERE published_at IS NULL} usado pelo relay.</p>
 *
 * <h3>Política de retenção</h3>
 * <p>Remove apenas mensagens com {@code published_at != null AND created_at < now - 7 dias}.
 * Mensagens pendentes ({@code published_at IS NULL}) nunca são tocadas — preservar o
 * outbox pendente é crítico para at-least-once delivery.</p>
 *
 * <h3>Segurança e idempotência</h3>
 * <p>O DELETE é atômico via {@code @Transactional}: em caso de falha de infra,
 * o rollback preserva todos os registros. A operação é idempotente — re-execução
 * não produz efeitos colaterais.</p>
 *
 * <h3>Testabilidade</h3>
 * <p>O {@link Clock} é injetado via construtor (definido em
 * {@link com.vibranium.orderservice.config.TimeConfig}). Em produção usa
 * {@code Clock.systemUTC()}; em testes, {@code Clock.fixed(...)} garante
 * determinismo sem {@code Thread.sleep}.</p>
 *
 * <p>Executa às 03:00 UTC todo domingo (configurável via
 * {@code app.cleanup.outbox.cron}).</p>
 */
@Component
public class OutboxCleanupJob {

    private static final Logger logger = LoggerFactory.getLogger(OutboxCleanupJob.class);

    /** Período de retenção em dias antes da remoção de mensagens processadas. */
    private static final long RETENTION_DAYS = 7L;

    private final OrderOutboxRepository outboxMessageRepository;
    private final Clock clock;

    /**
     * Cria o job de limpeza do outbox.
     *
     * @param outboxMessageRepository repositório JPA da tabela outbox_message.
     * @param clock                   abstração temporal — use {@code Clock.fixed(...)}
     *                                em testes para determinismo; nunca use {@code Instant.now()}.
     */
    public OutboxCleanupJob(OrderOutboxRepository outboxMessageRepository, Clock clock) {
        this.outboxMessageRepository = outboxMessageRepository;
        this.clock = clock;
    }

    /**
     * Remove mensagens de outbox processadas com mais de {@value #RETENTION_DAYS} dias.
     *
     * <p>Agendado para executar às 03:00h UTC todo domingo.
     * O {@code cron} pode ser sobrescrito via {@code app.cleanup.outbox.cron}.</p>
     *
     * <p>Apenas mensagens com {@code published_at != null} são elegíveis para remoção.
     * O predicado {@code created_at < cutoff} garante que apenas registros fora da
     * janela de retenção sejam deletados em lote (uma única instrução SQL DELETE).</p>
     */
    @Scheduled(cron = "${app.cleanup.outbox.cron:0 0 3 * * SUN}")
    @Transactional
    public void cleanupProcessedOutboxMessages() {
        // Usa clock.instant() para permitir testes determinísticos (AT-2.3.1)
        Instant cutoff = clock.instant().minus(RETENTION_DAYS, ChronoUnit.DAYS);

        logger.info("Cleanup outbox: removendo mensagens processadas anteriores a {}", cutoff);

        long deleted = outboxMessageRepository.deleteByPublishedAtNotNullAndCreatedAtBefore(cutoff);

        logger.info("Cleanup outbox: concluído — {} registros removidos, cutoff={}", deleted, cutoff);
    }
}
