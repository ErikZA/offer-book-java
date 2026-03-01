package com.vibranium.utils.correlation;

import java.util.UUID;

/**
 * Gerador centralizado de IDs de correlação para rastreabilidade de requisições.
 *
 * <p>Implementa o padrão <em>Correlation ID</em> para propagação de contexto entre
 * microsserviços via headers AMQP ({@code X-Correlation-ID}) e HTTP ({@code X-Correlation-ID}).
 * Centralizar a geração garante consistência de formato em toda a plataforma.</p>
 *
 * <p>Todos os IDs gerados são UUIDs versão 4 (random), conforme RFC 4122, com
 * 122 bits de entropia — colisão estatisticamente desprezível mesmo em cargas de
 * 5.000 req/s sustentadas.</p>
 *
 * <p>Uso:</p>
 * <pre>{@code
 *   UUID correlationId = CorrelationIdGenerator.generate();
 *   String header      = CorrelationIdGenerator.generateAsString();
 * }</pre>
 */
public final class CorrelationIdGenerator {

    /** Construtor privado — esta classe não deve ser instanciada. */
    private CorrelationIdGenerator() {
        // utilitário estático
    }

    /**
     * Gera um novo ID de correlação como {@link UUID} v4.
     *
     * @return UUID v4 aleatório, nunca {@code null}
     */
    public static UUID generate() {
        return UUID.randomUUID();
    }

    /**
     * Gera um novo ID de correlação como {@link String} no formato UUID padrão.
     *
     * <p>Formato: {@code xxxxxxxx-xxxx-4xxx-[89ab]xxx-xxxxxxxxxxxx}</p>
     *
     * @return representação string do UUID v4, nunca {@code null}
     */
    public static String generateAsString() {
        return UUID.randomUUID().toString();
    }
}
