package com.vibranium.utils.outbox;

/**
 * Propriedades base de configuração do módulo Outbox.
 *
 * <p>Record imutável que encapsula as configurações comuns a qualquer
 * implementação do Transactional Outbox Pattern: tamanho do lote e
 * intervalo de polling.</p>
 *
 * <p>Cada serviço mapeia suas propriedades YAML específicas
 * ({@code @ConfigurationProperties}) para esta estrutura ao construir
 * a subclasse de {@link AbstractOutboxPublisher}.</p>
 *
 * @param batchSize         Tamanho máximo do lote de mensagens por ciclo
 *                          de polling ({@code LIMIT} da query SKIP LOCKED).
 * @param pollingIntervalMs Intervalo em milissegundos entre ciclos de polling
 *                          ({@code fixedDelay} do scheduler).
 */
public record OutboxConfigProperties(int batchSize, long pollingIntervalMs) {

    /**
     * Validação compacta via construtor canônico do record.
     */
    public OutboxConfigProperties {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive, got: " + batchSize);
        }
        if (pollingIntervalMs <= 0) {
            throw new IllegalArgumentException("pollingIntervalMs must be positive, got: " + pollingIntervalMs);
        }
    }
}
