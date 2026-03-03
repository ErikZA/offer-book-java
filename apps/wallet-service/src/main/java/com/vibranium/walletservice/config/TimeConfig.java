package com.vibranium.walletservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Configuração de beans relacionados a tempo para o wallet-service.
 *
 * <h3>Motivação (AT-2.3.1)</h3>
 * <p>O uso direto de {@code Instant.now()} em jobs de limpeza torna os testes
 * não-determinísticos e dependentes de {@code Thread.sleep}.</p>
 *
 * <p>Ao expor {@link Clock} como bean Spring:</p>
 * <ul>
 *   <li>Em <strong>produção</strong>: {@code Clock.systemUTC()} — comportamento
 *       idêntico ao {@code Instant.now()}, sempre em UTC.</li>
 *   <li>Em <strong>testes</strong>: {@code Clock.fixed(...)} — instante controlado,
 *       determinístico, sem dependência de tempo real. Basta sobrescrever o bean
 *       com {@code @Primary} em um {@code @TestConfiguration}.</li>
 * </ul>
 *
 * <h3>Padrão de uso</h3>
 * <pre>
 * // Injete o Clock via construtor:
 * private final Clock clock;
 *
 * // Use clock.instant() em vez de Instant.now():
 * Instant cutoff = clock.instant().minus(7, ChronoUnit.DAYS);
 * </pre>
 */
@Configuration
public class TimeConfig {

    /**
     * Bean {@link Clock} de produção usando UTC.
     *
     * <p>Em testes unitários, o Clock é instanciado diretamente via
     * {@code Clock.fixed(FIXED_NOW, ZoneOffset.UTC)} sem necessidade de contexto Spring.
     * Em testes de integração, este bean pode ser sobreposto com {@code @Primary}
     * em um {@code @TestConfiguration}, sem qualquer alteração nesta classe.</p>
     *
     * @return {@code Clock.systemUTC()} — equivalente a {@code Instant.now()} mas testável.
     */
    @Bean
    public Clock clock() {
        // Produção sempre usa UTC para consistência com TIMESTAMPTZ do PostgreSQL
        return Clock.systemUTC();
    }
}
