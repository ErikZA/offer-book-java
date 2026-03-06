package com.vibranium.orderservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configurações do módulo Outbox Publisher, mapeadas a partir do prefixo
 * {@code app.outbox} no {@code application.yaml}.
 *
 * <p>Uso de {@link ConfigurationProperties} com records garante imutabilidade,
 * tipagem segura e validação automática pelo Spring Boot Binder.</p>
 *
 * <p><b>Exemplo de configuração:</b></p>
 * <pre>
 * app:
 *   outbox:
 *     batch-size: 100
 *     delay-ms: 500
 * </pre>
 *
 * @see OutboxConfig
 * @see com.vibranium.orderservice.application.service.OrderOutboxPublisherService
 */
@ConfigurationProperties(prefix = "app.outbox")
public record OutboxProperties(

        /**
         * Tamanho máximo do lote de mensagens processadas por ciclo de polling.
         * Controla o {@code LIMIT} da query {@code SELECT FOR UPDATE SKIP LOCKED}.
         * Default: 100.
         */
        @DefaultValue("100") int batchSize,

        /**
         * Intervalo em milissegundos entre ciclos de polling do scheduler.
         * Usado como {@code fixedDelay} no {@code @Scheduled}.
         * Valores menores reduzem latência mas aumentam carga no DB.
         * Default: 500ms.
         */
        @DefaultValue("500") long delayMs

) {}
