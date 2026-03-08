package com.vibranium.performance;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;

import static io.gatling.javaapi.core.CoreDsl.*;

/**
 * Cenário Smoke: 10 req/s por 30 segundos.
 *
 * <p>Objetivo: validar corretude do endpoint e do pipeline end-to-end.
 * Não estressa o sistema — apenas confirma que o fluxo funciona sob carga mínima.</p>
 *
 * <h3>Critérios de aceite:</h3>
 * <ul>
 *   <li>Error rate &lt; 1%</li>
 *   <li>p99 latência &lt; 5.000ms (end-to-end com outbox delay)</li>
 * </ul>
 */
public class SmokeSimulation extends Simulation {

    private final ScenarioBuilder smokeScenario = scenario("Smoke — 10 req/s, 30s")
            .exec(BaseSimulationConfig.placeOrderChain());

    {
        setUp(
                smokeScenario.injectOpen(
                        // Ramp-up de 5s para chegar a 10 req/s, depois constante por 25s
                        rampUsersPerSec(1).to(10).during(5),
                        constantUsersPerSec(10).during(25)
                )
        )
        .protocols(BaseSimulationConfig.httpProtocol())
        .assertions(
                global().failedRequests().percent().lt(1.0),
                global().responseTime().percentile4().lt(5000)
        );
    }
}
