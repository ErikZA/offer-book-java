package com.vibranium.performance;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;

import static io.gatling.javaapi.core.CoreDsl.*;

/**
 * Validação do RNF01 — Alta Escalabilidade (5.000 trades/s).
 *
 * <h3>Problema</h3>
 * <p>Executar 5.000 req/s exige infraestrutura de produção (múltiplos nós dedicados).
 * Localmente, o Docker compartilha CPU e memória de uma única máquina, inviabilizando
 * a saturação simultânea de MongoDB, PostgreSQL, Redis, RabbitMQ e serviços aplicativos.</p>
 *
 * <h3>Abordagem — Validação por Projeção de Escalabilidade Horizontal</h3>
 * <p>Serviços stateless (como order-service) escalam linearmente. Esta simulação:</p>
 * <ol>
 *   <li>Injeta uma taxa <b>viável localmente</b> (default: 100 req/s) contra as instâncias disponíveis</li>
 *   <li>Valida que throughput sustentado ≥ 95% da taxa injetada</li>
 *   <li>Valida que p99 &lt; threshold (default: 2.000ms — acomodando overhead Docker)</li>
 *   <li>Valida error rate &lt; 1%</li>
 *   <li>Projeta: se N instâncias sustentam T req/s cada → ceil(5000 / T) instâncias atendem RNF01</li>
 * </ol>
 *
 * <h3>Variáveis de ambiente</h3>
 * <table>
 *   <tr><th>Variável</th><th>Default</th><th>Descrição</th></tr>
 *   <tr><td>{@code RNF01_TARGET_RPS}</td><td>100</td><td>Taxa alvo em req/s</td></tr>
 *   <tr><td>{@code RNF01_DURATION_SECS}</td><td>60</td><td>Duração da carga constante</td></tr>
 *   <tr><td>{@code RNF01_RAMP_SECS}</td><td>10</td><td>Ramp-up em segundos</td></tr>
 *   <tr><td>{@code RNF01_P99_THRESHOLD_MS}</td><td>2000</td><td>p99 máximo em ms</td></tr>
 *   <tr><td>{@code RNF01_INSTANCE_COUNT}</td><td>10</td><td>Nº instâncias projetadas para produção</td></tr>
 * </table>
 *
 * <h3>Uso local (Docker Compose)</h3>
 * <pre>
 * docker compose -f tests/performance/docker-compose.perf.yml --profile run run --rm \
 *   -e GATLING_SIMULATION=com.vibranium.performance.Rnf01ScalabilitySimulation \
 *   -e RNF01_TARGET_RPS=100 \
 *   gatling
 * </pre>
 *
 * <h3>Uso em ambiente maior (CI/CD, staging)</h3>
 * <pre>
 * # Validar com taxa mais alta quando a infra comporta
 * -e RNF01_TARGET_RPS=500 -e RNF01_INSTANCE_COUNT=10
 * # → 500 × 10 = 5.000 req/s projetado ✓
 * </pre>
 *
 * <h3>Lógica de projeção</h3>
 * <p>Se 1 instância sustenta T req/s sem degradação (error &lt; 1%, p99 estável),
 * então N instâncias stateless sustentam N × T req/s, pois:</p>
 * <ul>
 *   <li>Cada instância possui pool de conexões isolado (HikariCP, Lettuce, AMQP)</li>
 *   <li>O motor de matching é distribuído via Redis (sem estado local)</li>
 *   <li>O load balancer distribui uniformemente entre instâncias</li>
 * </ul>
 */
public class Rnf01ScalabilitySimulation extends Simulation {

    private static final double RNF01_GLOBAL_TARGET = 5000.0;

    private static final int TARGET_RPS = intEnv("RNF01_TARGET_RPS", 100);
    private static final int DURATION_SECS = intEnv("RNF01_DURATION_SECS", 60);
    private static final int RAMP_SECS = intEnv("RNF01_RAMP_SECS", 10);
    private static final int P99_THRESHOLD_MS = intEnv("RNF01_P99_THRESHOLD_MS", 2000);
    private static final int INSTANCE_COUNT = intEnv("RNF01_INSTANCE_COUNT", 10);

    private static final double PROJECTED_THROUGHPUT = (double) TARGET_RPS * INSTANCE_COUNT;
    private static final double MIN_THROUGHPUT = TARGET_RPS * 0.95;
    private static final int INSTANCES_NEEDED = (int) Math.ceil(RNF01_GLOBAL_TARGET / TARGET_RPS);

    private final ScenarioBuilder rnf01Scenario = scenario(
            String.format("RNF01 Scalability — %d req/s (projected: %.0f with %d instances)",
                    TARGET_RPS, PROJECTED_THROUGHPUT, INSTANCE_COUNT))
            .exec(BaseSimulationConfig.placeOrderChain());

    {
        System.out.printf("""
                %n╔═══════════════════════════════════════════════════════════════╗
                ║          RNF01 — Validação de Escalabilidade                  ║
                ║          Meta global: 5.000 trades/s                          ║
                ╠═══════════════════════════════════════════════════════════════╣
                ║  Taxa injetada (por teste):    %,7d req/s                    ║
                ║  Ramp-up:                      %,7d s                        ║
                ║  Duração sustentada:           %,7d s                        ║
                ║  p99 máximo:                   %,7d ms                       ║
                ║  Throughput mínimo (95%%):      %,7.0f req/s                  ║
                ╠═══════════════════════════════════════════════════════════════╣
                ║  Instâncias projetadas (prod): %,7d                          ║
                ║  Throughput projetado:         %,7.0f req/s                   ║
                ║  Instâncias mín. p/ RNF01:    %,7d                           ║
                ║  RNF01 atendido (projeção):    %-7s                          ║
                ╚═══════════════════════════════════════════════════════════════╝%n""",
                TARGET_RPS, RAMP_SECS, DURATION_SECS, P99_THRESHOLD_MS,
                MIN_THROUGHPUT, INSTANCE_COUNT, PROJECTED_THROUGHPUT,
                INSTANCES_NEEDED, PROJECTED_THROUGHPUT >= RNF01_GLOBAL_TARGET ? "SIM ✓" : "NÃO ✗");

        setUp(
                rnf01Scenario.injectOpen(
                        rampUsersPerSec(1).to(TARGET_RPS).during(RAMP_SECS),
                        constantUsersPerSec(TARGET_RPS).during(DURATION_SECS)
                )
        )
        .protocols(BaseSimulationConfig.httpProtocol())
        .assertions(
                // 1. Error rate < 1% — instância não colapsa sob esta carga
                global().failedRequests().percent().lt(1.0),
                // 2. p99 estável — sem degradação exponencial de latência
                global().responseTime().percentile4().lt(P99_THRESHOLD_MS),
                // 3. Throughput sustentado ≥ 95% do injetado — sem backpressure
                global().requestsPerSec().gte(MIN_THROUGHPUT)
        );
    }

    private static int intEnv(String key, int defaultValue) {
        String v = System.getenv(key);
        if (v != null && !v.isBlank()) {
            try {
                return Integer.parseInt(v.trim());
            } catch (NumberFormatException ignored) {
                // fallback para default
            }
        }
        return defaultValue;
    }
}
