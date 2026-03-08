package com.vibranium.performance;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;

import static io.gatling.javaapi.core.CoreDsl.*;

/**
 * Cenário Stress: 5.000 req/s por 120 segundos.
 *
 * <p>Objetivo: validar o SLA RNF01 de 5.000 trades/s. Identifica o throughput
 * máximo real do sistema e o primeiro ponto de gargalo.</p>
 *
 * <p>O ramp-up escalonado (stepped) permite observar em qual patamar o sistema
 * começa a degradar, facilitando a identificação do bottleneck.</p>
 *
 * <h3>Critérios de aceite:</h3>
 * <ul>
 *   <li>Error rate &lt; 5%</li>
 *   <li>Documentar throughput máximo real alcançado</li>
 *   <li>Identificar primeiro gargalo (PG connections, Redis ops, RabbitMQ queue depth)</li>
 * </ul>
 */
public class StressSimulation extends Simulation {

    private final ScenarioBuilder stressScenario = scenario("Stress — 5000 req/s, 120s")
            .exec(BaseSimulationConfig.placeOrderChain());

    {
        setUp(
                stressScenario.injectOpen(
                        // Ramp-up escalonado para observar degradação por patamar:
                        // 0 → 1000 em 10s
                        rampUsersPerSec(1).to(1000).during(10),
                        constantUsersPerSec(1000).during(10),
                        // 1000 → 2000 em 10s
                        rampUsersPerSec(1000).to(2000).during(10),
                        constantUsersPerSec(2000).during(10),
                        // 2000 → 3000 em 10s
                        rampUsersPerSec(2000).to(3000).during(10),
                        constantUsersPerSec(3000).during(10),
                        // 3000 → 4000 em 10s
                        rampUsersPerSec(3000).to(4000).during(10),
                        constantUsersPerSec(4000).during(10),
                        // 4000 → 5000 em 10s + sustentado por 20s
                        rampUsersPerSec(4000).to(5000).during(10),
                        constantUsersPerSec(5000).during(20)
                )
        )
        .protocols(BaseSimulationConfig.httpProtocol())
        .assertions(
                // SLA relaxado no stress — objetivo é documentar o limite real
                global().failedRequests().percent().lt(5.0)
        );
    }
}
