package com.vibranium.performance;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;

import static io.gatling.javaapi.core.CoreDsl.*;

/**
 * Cenário Soak: 500 req/s por 30 minutos.
 *
 * <p>Objetivo: validar que o sistema não sofre de memory leaks, connection pool
 * exhaustion, ou degradação progressiva sob carga moderada sustentada.</p>
 *
 * <p>Métricas a observar durante o teste:</p>
 * <ul>
 *   <li>JVM heap usage (deve estabilizar, não crescer linearmente)</li>
 *   <li>HikariCP active connections (deve oscilar em faixa estável)</li>
 *   <li>Redis memory usage (deve estabilizar)</li>
 *   <li>RabbitMQ queue depth (não deve acumular indefinidamente)</li>
 *   <li>Outbox lag (não deve crescer ao longo do tempo)</li>
 * </ul>
 *
 * <h3>Critérios de aceite:</h3>
 * <ul>
 *   <li>Error rate &lt; 1% em qualquer janela de 5 minutos</li>
 *   <li>Latência p99 estável (sem tendência de crescimento)</li>
 *   <li>Sem OOM ou crash durante os 30 minutos</li>
 * </ul>
 */
public class SoakSimulation extends Simulation {

    private final ScenarioBuilder soakScenario = scenario("Soak — 500 req/s, 30min")
            .exec(BaseSimulationConfig.placeOrderChain());

    {
        setUp(
                soakScenario.injectOpen(
                        // Ramp-up suave de 30s para 500 req/s
                        rampUsersPerSec(1).to(500).during(30),
                        // Carga constante por 29 minutos e 30 segundos
                        constantUsersPerSec(500).during(1770)
                )
        )
        .protocols(BaseSimulationConfig.httpProtocol())
        .assertions(
                global().failedRequests().percent().lt(1.0),
                global().responseTime().percentile4().lt(10000)
        );
    }
}
