package com.vibranium.performance;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;

import static io.gatling.javaapi.core.CoreDsl.*;

/**
 * Cenário Load: 1.000 req/s por 60 segundos.
 *
 * <p>Objetivo: validar estabilidade do sistema sob carga moderada.
 * Confirma que throughput sustentado se mantém acima de 950 req/s
 * sem degradação significativa.</p>
 *
 * <h3>Critérios de aceite:</h3>
 * <ul>
 *   <li>Error rate &lt; 1%</li>
 *   <li>Throughput sustentado &ge; 950 req/s</li>
 *   <li>p99 latência &lt; 10.000ms</li>
 * </ul>
 */
public class LoadSimulation extends Simulation {

    private final ScenarioBuilder loadScenario = scenario("Load — 1000 req/s, 60s")
            .exec(BaseSimulationConfig.placeOrderChain());

    {
        setUp(
                loadScenario.injectOpen(
                        // Ramp-up gradual de 15s: 0 → 1000 req/s
                        rampUsersPerSec(1).to(1000).during(15),
                        // Carga constante por 45s
                        constantUsersPerSec(1000).during(45)
                )
        )
        .protocols(BaseSimulationConfig.httpProtocol())
        .assertions(
                global().failedRequests().percent().lt(1.0),
                global().responseTime().percentile4().lt(10000),
                // Throughput: pelo menos 950 req/s (95% do target)
                global().requestsPerSec().gte(950.0)
        );
    }
}
