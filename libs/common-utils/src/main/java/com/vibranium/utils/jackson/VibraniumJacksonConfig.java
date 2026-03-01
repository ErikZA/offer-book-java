package com.vibranium.utils.jackson;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Utilitário central de configuração Jackson para todos os microsserviços da plataforma Vibranium.
 *
 * <p>Resolve a divergência histórica entre {@code order-service} (epoch-millis) e
 * {@code wallet-service} (ISO-8601): ambos passam a usar ISO-8601 como configuração
 * padrão do mapper. Campos que explicitamente necessitam de epoch-millis utilizam
 * a anotação {@code @JsonFormat(shape = JsonFormat.Shape.NUMBER_INT)} diretamente no Record,
 * sobrescrevendo o comportamento do mapper somente onde necessário.</p>
 *
 * <p>Uso:</p>
 * <pre>{@code
 *   ObjectMapper mapper = VibraniumJacksonConfig.configure(new ObjectMapper());
 * }</pre>
 *
 * <p>Esta classe é um utilitário estático puro (não é um {@code @Configuration} Spring).
 * Cada serviço declara seu próprio {@code @Bean @Primary ObjectMapper} delegando para cá,
 * garantindo que o bean seja registrado corretamente no contexto Spring.</p>
 *
 * <p><strong>Configurações aplicadas:</strong></p>
 * <ul>
 *   <li>{@link JavaTimeModule} — suporte a {@code Instant}, {@code LocalDate}, etc.</li>
 *   <li>{@code WRITE_DATES_AS_TIMESTAMPS = false} — ISO-8601 como padrão
 *       (ex.: {@code "2025-01-15T12:00:00Z"}). Campos anotados com
 *       {@code @JsonFormat(NUMBER_INT)} ainda produzem epoch-millis.</li>
 *   <li>{@code FAIL_ON_UNKNOWN_PROPERTIES = false} — tolerante a contratos evoluídos
 *       (novos campos futuros não quebram desserialização de mensagens antigas).</li>
 * </ul>
 */
public final class VibraniumJacksonConfig {

    /** Construtor privado — esta classe não deve ser instanciada. */
    private VibraniumJacksonConfig() {
        // utilitário estático
    }

    /**
     * Aplica a configuração padrão Vibranium a um {@link ObjectMapper} existente.
     *
     * <p>O método retorna o mesmo mapper recebido (fluent), permitindo encadeamento:</p>
     * <pre>{@code
     *   return VibraniumJacksonConfig.configure(new ObjectMapper());
     * }</pre>
     *
     * @param mapper o {@link ObjectMapper} a ser configurado; não deve ser {@code null}
     * @return o mesmo {@code mapper} configurado
     */
    public static ObjectMapper configure(ObjectMapper mapper) {
        return mapper
                // Registra suporte a Java 8 Time API (Instant, LocalDate, ZonedDateTime, etc.)
                .registerModule(new JavaTimeModule())
                // ISO-8601 como padrão de serialização de datas; campos específicos podem
                // sobrescrever com @JsonFormat(shape = JsonFormat.Shape.NUMBER_INT)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                // Tolerante a evolução de contratos: campos desconhecidos são ignorados
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
}
