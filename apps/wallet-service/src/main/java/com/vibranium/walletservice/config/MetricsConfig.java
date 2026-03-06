package com.vibranium.walletservice.config;

import com.vibranium.walletservice.domain.repository.OutboxMessageRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Configuration;

/**
 * Configuração de métricas de negócio Micrometer para o wallet-service.
 *
 * <p>Registra Gauges que dependem de suppliers dinâmicos (ex: profundidade do outbox).
 * Counters e Timers são registrados diretamente nos services que os incrementam.</p>
 *
 * <p><strong>AT-15.2:</strong> Todas as métricas custom usam o prefixo {@code vibranium.}
 * para evitar colisão com métricas auto-configuradas do Spring Boot.</p>
 */
@Configuration
public class MetricsConfig {

    /**
     * Registra o Gauge {@code vibranium.outbox.queue.depth} que reflete a quantidade
     * de mensagens pendentes (não processadas) na tabela {@code outbox_message}.
     *
     * @param registry         MeterRegistry injetado pelo Spring Boot (auto-configurado).
     * @param outboxRepository Repositório do outbox para contagem de pendentes.
     */
    public MetricsConfig(MeterRegistry registry, OutboxMessageRepository outboxRepository) {
        Gauge.builder("vibranium.outbox.queue.depth", outboxRepository,
                        repo -> repo.countByProcessedFalse())
                .description("Number of pending (unprocessed) messages in the wallet outbox")
                .register(registry);
    }
}
