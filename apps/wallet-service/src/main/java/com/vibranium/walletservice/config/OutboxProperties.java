package com.vibranium.walletservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configurações do módulo Outbox Publisher, mapeadas a partir do prefixo
 * {@code app.outbox} no {@code application.yaml}.
 *
 * <p>Uso de {@link ConfigurationProperties} com records garante imutabilidade,
 * tipagem segura e validação automática pelo Spring Boot Binder.</p>
 *
 * <p><b>Exemplo de configuração (Polling SKIP LOCKED):</b></p>
 * <pre>
 * app:
 *   outbox:
 *     batch-size: 100
 *     polling:
 *       interval-ms: 2000
 * </pre>
 */
@ConfigurationProperties(prefix = "app.outbox")
public record OutboxProperties(

        /**
         * Tamanho máximo do lote de mensagens processadas por ciclo de polling.
         * Controla o {@code LIMIT} da query {@code SELECT FOR UPDATE SKIP LOCKED}.
         * Default: 100.
         */
        @DefaultValue("100") int batchSize,

        /** Configurações do polling scheduler. */
        @DefaultValue PollingProperties polling

) {

    /**
     * Propriedades do scheduler de polling do Outbox.
     *
     * <p>O intervalo controla o {@code fixedDelay} do {@code @Scheduled}.
     * Valores menores reduzem latência de publicação mas aumentam carga no DB.
     * Recomendado: 1000-5000ms para balancear latência vs. carga.</p>
     */
    public record PollingProperties(

            /**
             * Intervalo em milissegundos entre ciclos de polling.
             * Usado como {@code fixedDelay} no scheduler.
             * Default: 2000ms.
             */
            @DefaultValue("2000") long intervalMs

    ) {}
}
