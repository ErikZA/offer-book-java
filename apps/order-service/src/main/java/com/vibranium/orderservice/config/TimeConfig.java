package com.vibranium.orderservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Configuração de beans relacionados a tempo.
 *
 * <h3>Motivação (AT-09.2)</h3>
 * <p>O uso direto de {@code Instant.now()} no código de produção torna os testes
 * não-determinísticos, dependentes de {@code Thread.sleep} e frágeis.</p>
 *
 * <p>Ao expor {@link Clock} como bean Spring:</p>
 * <ul>
 *   <li>Em <strong>produção</strong>: {@code Clock.systemUTC()} — comportamento idêntico
 *       ao {@code Instant.now()}.</li>
 *   <li>Em <strong>testes</strong>: {@code Clock.fixed(...)} — instante controlado,
 *       determinístico, sem dependência de tempo real.</li>
 * </ul>
 *
 * <p>O clock deve ser singleton: uma única instância compartilhada por todos
 * os beans que precisam de tempo (jobs, services). Isso garante consistência
 * entre as leituras de tempo dentro de um mesmo ciclo de processamento.</p>
 *
 * <h3>Padrão de uso</h3>
 * <pre>
 * // Injete o Clock via construtor:
 * private final Clock clock;
 *
 * // Use clock.instant() em vez de Instant.now():
 * Instant cutoff = clock.instant().minus(5, ChronoUnit.MINUTES);
 * </pre>
 */
@Configuration
public class TimeConfig {

    /**
     * Cria o bean {@link Clock} de produção usando UTC.
     *
     * <p>Em testes de integração, este bean é sobreposto com {@code @Primary}
     * por um {@code Clock.fixed(...)} definido em {@code @TestConfiguration},
     * sem qualquer alteração nesta classe.</p>
     *
     * @return {@code Clock.systemUTC()} — equivalente a {@code Instant.now()} mas testável.
     */
    @Bean
    public Clock clock() {
        // Produção sempre usa UTC para consistência com os timestamps do banco (TIMESTAMPTZ)
        return Clock.systemUTC();
    }
}
