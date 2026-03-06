package com.vibranium.orderservice.config;

import com.vibranium.orderservice.domain.repository.OrderOutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Configuration;

/**
 * Configuração de métricas de negócio Micrometer para o order-service.
 *
 * <p>Registra Gauges que dependem de suppliers dinâmicos (ex: profundidade do outbox).
 * Counters e Timers são registrados diretamente nos services que os incrementam,
 * pois dependem de parâmetros (tags) disponíveis apenas no momento da operação.</p>
 *
 * <p><strong>AT-15.2:</strong> Todas as métricas custom usam o prefixo {@code vibranium.}
 * para evitar colisão com métricas auto-configuradas do Spring Boot.</p>
 */
@Configuration
public class MetricsConfig {

    /**
     * Registra o Gauge {@code vibranium.outbox.queue.depth} que reflete a quantidade
     * de mensagens pendentes (não publicadas) na tabela {@code tb_order_outbox}.
     *
     * <p>O supplier é executado a cada scrape do Prometheus — não adiciona overhead
     * no hot path de criação de ordens.</p>
     *
     * @param registry         MeterRegistry injetado pelo Spring Boot (auto-configurado).
     * @param outboxRepository Repositório do outbox para contagem de pendentes.
     */
    public MetricsConfig(MeterRegistry registry, OrderOutboxRepository outboxRepository) {
        Gauge.builder("vibranium.outbox.queue.depth", outboxRepository, repo -> repo.countPending())
                .description("Number of pending (unpublished) messages in the order outbox")
                .register(registry);
    }
}
