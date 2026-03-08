# Vibranium Platform — Prompts de Engenharia para Correção de Bugs e Escalabilidade

> **Fonte:** [docs/PERFORMANCE_REPORT.md](docs/PERFORMANCE_REPORT.md) — Seções 5 (Recomendações) e 7 (Bugs/Issues)  
> **Data:** 07/03/2026  
> **Autor:** Engenheiro de Prompt IA  
> **Stack:** Spring Boot 3.4.13, Java 21, PostgreSQL 16, MongoDB 7, Redis 7, RabbitMQ 3.13, Kong 3.4, Gatling 3.11.5

---

## Índice

| # | Issue/Recomendação | Severidade | Prompt |
|---|---|---|---|
| BUG-01 | `docker-compose.perf.yml` sem env vars JWT | Alta | [Prompt BUG-01](#bug-01--docker-composeperfyml-sem-env-vars-jwt) |
| BUG-02 | Usuário `tester` não registrado em `tb_user_registry` | Alta | [Prompt BUG-02](#bug-02--usuário-tester-não-registrado-em-tb_user_registry) |
| BUG-03 | order-service fica `unhealthy` após Stress Test | Média | [Prompt BUG-03](#bug-03--order-service-fica-unhealthy-após-stress-test) |
| BUG-04 | HTTP 500 sob carga — pool exhaustion sem tratamento graceful | Média | [Prompt BUG-04](#bug-04--http-500-sob-carga--pool-exhaustion-sem-tratamento-graceful) |
| REC-5.1 | Escalar horizontalmente (Load Balancer + Réplicas) | Alta | [Prompt REC-5.1](#rec-51--escalar-horizontalmente-kong-load-balancer--réplicas) |

---

## BUG-01 — `docker-compose.perf.yml` sem env vars JWT

### Role

Você é um **Engenheiro DevOps/SecOps Sênior** especialista em infraestrutura Docker, Spring Security OAuth2 e integração Keycloak. Você possui profundo conhecimento em configuração de ambientes de teste de performance com segurança end-to-end.

### Contexto

O arquivo `tests/performance/docker-compose.perf.yml` da plataforma Vibranium foi criado sem as variáveis de ambiente obrigatórias para validação JWT no `order-service` e `wallet-service`. Sem `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI` e `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI`, os serviços rejeitam TODOS os tokens JWT válidos emitidos pelo Keycloak interno, resultando em 100% de falhas 401/403 nos testes de performance.

O issue foi marcado como "Corrigido", porém é necessário:
1. Garantir que a correção está presente e completa.
2. Criar testes automatizados que previnam regressão.
3. Validar que a URI do issuer é consistente entre Keycloak e Spring Security.

### Objetivos

1. **Auditar** o `docker-compose.perf.yml` e o `docker-compose.perf.flat.yml` para garantir que TODAS as variáveis JWT estão presentes e corretas.
2. **Criar teste automatizado** (bash script) que valide a presença das env vars JWT antes de iniciar o benchmark.
3. **Documentar** o mapeamento `issuer ↔ jwk-set-uri` para cada ambiente (dev, perf, staging).

### Validação Obrigatória (antes de alterar código)

- [ ] Ler o arquivo `tests/performance/docker-compose.perf.yml` — verificar seções `order-service` e `wallet-service`.
- [ ] Ler o arquivo `tests/performance/docker-compose.perf.flat.yml` — mesma verificação.
- [ ] Ler `apps/order-service/src/main/resources/application.yaml` — verificar configuração `spring.security.oauth2.resourceserver.jwt`.
- [ ] Ler `apps/wallet-service/src/main/resources/application.yaml` — mesma verificação.
- [ ] Ler `infra/keycloak/realm-export.json` — confirmar nome do realm e issuer.
- [ ] Verificar `BaseSimulationConfig.java` e `KeycloakTokenFeeder.java` — confirmar que o Gatling obtém token do Keycloak correto.

### TDD — Fase RED (obrigatória)

Criar o script de teste ANTES de qualquer correção:

```bash
# tests/validate-jwt-env.sh
#!/bin/bash
# RED: Este teste DEVE falhar se as env vars JWT estiverem ausentes

COMPOSE_FILE=$1
SERVICE=$2

check_env() {
    local var=$1
    grep -q "$var" "$COMPOSE_FILE"
    if [ $? -ne 0 ]; then
        echo "FAIL: $var ausente em $SERVICE no arquivo $COMPOSE_FILE"
        exit 1
    fi
}

check_env "SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI"
check_env "SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI"

echo "PASS: Todas as variáveis JWT presentes para $SERVICE"
```

### Regras Obrigatórias

1. **NUNCA** hardcodar URLs de issuer — usar variáveis de ambiente com defaults seguros.
2. O `issuer-uri` dentro da rede Docker DEVE apontar para `http://keycloak:8080/realms/{realm}` (hostname Docker).
3. O token emitido pelo Keycloak via `localhost:8180` (mapeamento de porta host) terá `iss=http://localhost:8180/realms/{realm}` — o Spring Security rejeita se o issuer não bater. Garantir consistência.
4. Toda alteração em docker-compose DEVE manter a mesma convenção de secrets/env vars dos demais compose files do projeto.
5. Não expor portas desnecessárias ao host no ambiente de performance.

### Critérios de Aceite

| # | Critério | Validação |
|---|---|---|
| 1 | `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI` presente em ambos os serviços | `grep` no compose file |
| 2 | `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` presente e aponta para Keycloak Docker | `grep` no compose file |
| 3 | Issuer no token JWT (campo `iss`) bate com `issuer-uri` do Spring Security | Teste smoke retorna 202, não 401 |
| 4 | Script de validação existe e passa | `bash tests/validate-jwt-env.sh` exit code 0 |
| 5 | Nenhum token hardcoded nos compose files | Revisão manual |

### Saída Estruturada

```
/tests/performance/docker-compose.perf.yml       — env vars JWT adicionadas/verificadas
/tests/performance/docker-compose.perf.flat.yml   — env vars JWT adicionadas/verificadas
/tests/validate-jwt-env.sh                        — script de validação automatizada
/docs/JWT_ENV_MAPPING.md (opcional)               — mapeamento issuer por ambiente
```

### Resultado Esperado

- Smoke Test via `docker-compose.perf.yml` retorna 0% de erros (nenhum 401/403 por JWT inválido).
- Script de validação integrado ao fluxo de CI previne regressão futura.

### Arquivos de Referência

| Arquivo | Propósito |
|---|---|
| [tests/performance/docker-compose.perf.yml](tests/performance/docker-compose.perf.yml) | Compose a auditar |
| [tests/performance/docker-compose.perf.flat.yml](tests/performance/docker-compose.perf.flat.yml) | Compose com escala horizontal |
| [apps/order-service/src/main/resources/application.yaml](apps/order-service/src/main/resources/application.yaml) | Config JWT do order-service |
| [apps/wallet-service/src/main/resources/application.yaml](apps/wallet-service/src/main/resources/application.yaml) | Config JWT do wallet-service |
| [infra/keycloak/realm-export.json](infra/keycloak/realm-export.json) | Realm Keycloak |
| [tests/performance/src/test/java/com/vibranium/performance/helpers/KeycloakTokenFeeder.java](tests/performance/src/test/java/com/vibranium/performance/helpers/KeycloakTokenFeeder.java) | Obtenção de token para Gatling |

### Ferramentas e Referências

- **Spring Security OAuth2 Resource Server:** [docs](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html)
- **Keycloak OpenID Configuration:** `GET /realms/{realm}/.well-known/openid-configuration`
- **Docker Compose env vars:** [docs](https://docs.docker.com/compose/environment-variables/)
- **ShellCheck:** Validação de scripts bash — `shellcheck tests/validate-jwt-env.sh`

---

## BUG-02 — Usuário `tester` não registrado em `tb_user_registry`

### Role

Você é um **Engenheiro de Software Sênior Full-Stack** especialista em Spring Security, Keycloak, event-driven architecture e design de onboarding de usuários. Possui mentalidade de segurança e alta disponibilidade com foco em idempotência e resiliência.

### Contexto

A plataforma Vibranium utiliza um fluxo de registro de usuários baseado em eventos:
1. Usuário se registra no **Keycloak** (realm `orderbook-realm`).
2. Keycloak dispara evento `REGISTER` via plugin `keycloak-event-listener-rabbitmq` para o RabbitMQ (`amq.topic`).
3. O `order-service` (ou `wallet-service`) consome o evento e insere o registro na tabela `tb_user_registry`.

**Problema:** Ao importar o realm via `realm-export.json` com o usuário `tester` pré-configurado, o Keycloak **NÃO** dispara o evento `REGISTER` — ele é tratado como import, não como registro interativo. Consequência: o `tester` existe no Keycloak mas não na `tb_user_registry`, causando falhas de autorização/validação no order-service ao tentar criar ordens.

**Workaround atual:** INSERT manual no PostgreSQL — frágil, não escalável e viola a arquitetura event-driven.

### Objetivos

1. **Implementar seed automático** da `tb_user_registry` para usuários pré-importados no Keycloak no ambiente de teste.
2. **Criar listener/script de reconciliação** que detecte usuários no Keycloak sem registro na tabela e os insira.
3. **Garantir idempotência** — executar a reconciliação múltiplas vezes não deve gerar duplicatas.
4. **Manter a arquitetura event-driven** — a solução NÃO deve bypassar a fila RabbitMQ em produção.

### Validação Obrigatória (antes de alterar código)

- [ ] Ler a migration Flyway que cria `tb_user_registry` — entender schema e constraints.
- [ ] Ler o listener que consome eventos `REGISTER` do RabbitMQ — entender campos esperados.
- [ ] Ler `infra/keycloak/realm-export.json` — confirmar dados do usuário `tester`.
- [ ] Ler `tests/performance/docker-compose.perf.yml` e `docker-compose.perf.flat.yml` — verificar se há workaround existente.
- [ ] Verificar se existe tabela `tb_user_registry` no schema do order-service e/ou wallet-service (Flyway migrations em `src/main/resources/db/migration/`).

### TDD — Fase RED (obrigatória)

```java
// src/test/java/com/vibranium/order/infrastructure/UserRegistrySeedTest.java
@SpringBootTest
@Testcontainers
class UserRegistrySeedTest {

    @Test
    @DisplayName("RED: Usuário importado no Keycloak deve existir em tb_user_registry após seed")
    void shouldHaveTestUserInRegistryAfterSeed() {
        // GIVEN: Keycloak realm importado com usuário 'tester'
        // WHEN: Seed/reconciliation é executado
        // THEN: tb_user_registry contém registro para 'tester'
        Optional<UserRegistry> user = userRegistryRepository.findByKeycloakId(TESTER_KEYCLOAK_ID);
        assertThat(user).isPresent();
        assertThat(user.get().getUsername()).isEqualTo("tester");
    }

    @Test
    @DisplayName("RED: Seed deve ser idempotente — não gerar duplicatas")
    void shouldBeIdempotent() {
        // Executar seed 2x
        seedService.reconcile();
        seedService.reconcile();
        long count = userRegistryRepository.countByKeycloakId(TESTER_KEYCLOAK_ID);
        assertThat(count).isEqualTo(1);
    }
}
```

### Regras Obrigatórias

1. **Idempotência:** Usar `INSERT ... ON CONFLICT DO NOTHING` ou `MERGE` para evitar duplicatas.
2. **Separação de ambientes:** A reconciliação automática só deve rodar em profiles `test`, `perf`, `staging` — NUNCA em `prod`.
3. **Não acoplar ao Keycloak Admin API em runtime** — o seed deve ser self-contained (dados hardcoded ou lidos do realm-export.json no startup).
4. **Log obrigatório:** Registrar via SLF4J cada usuário reconciliado: `logger.info("Reconciled user {} (keycloakId={})", username, keycloakId)`.
5. **Validar entrada:** O keycloakId deve ser um UUID válido — rejeitar silenciosamente entradas malformadas.
6. **Não usar `System.out.println()`** — apenas SLF4J.

### Critérios de Aceite

| # | Critério | Validação |
|---|---|---|
| 1 | Após subir `docker-compose.perf.yml`, `tester` existe em `tb_user_registry` | Query: `SELECT * FROM tb_user_registry WHERE username='tester'` |
| 2 | Smoke Test completa com 0% erros (sem 403 por user not found) | Gatling report |
| 3 | Executar reconciliação 2x não gera duplicatas | Count query retorna 1 |
| 4 | Em profile `prod`, a reconciliação NÃO executa | Log: "Seed skipped — not in test/staging profile" |
| 5 | Testes unitários passam (RED → GREEN) | `mvn test` exit code 0 |

### Saída Estruturada

```
apps/order-service/src/main/java/.../seed/UserRegistrySeedRunner.java         — ApplicationRunner para seed
apps/order-service/src/main/resources/db/migration/V*__seed_user_registry.sql  — Migration com seed data (se abordagem SQL)
apps/order-service/src/test/java/.../UserRegistrySeedTest.java                 — Testes TDD
```

### Resultado Esperado

- O usuário `tester` está automaticamente disponível na `tb_user_registry` em ambientes de teste/performance.
- Nenhum INSERT manual necessário.
- A arquitetura event-driven permanece intacta para produção.

### Arquivos de Referência

| Arquivo | Propósito |
|---|---|
| [infra/keycloak/realm-export.json](infra/keycloak/realm-export.json) | Dados do usuário tester |
| [apps/order-service/src/main/resources/db/migration/](apps/order-service/src/main/resources/db/migration/) | Flyway migrations |
| [apps/order-service/src/main/resources/application.yaml](apps/order-service/src/main/resources/application.yaml) | Profiles ativos |
| Listener de eventos REGISTER (buscar no codebase) | Consumer RabbitMQ de registro |

### Ferramentas e Referências

- **Spring Boot ApplicationRunner:** [docs](https://docs.spring.io/spring-boot/docs/current/api/org/springframework/boot/ApplicationRunner.html)
- **Flyway Repeatable Migrations:** [docs](https://documentation.red-gate.com/fd/repeatable-migrations-184127527.html)
- **PostgreSQL ON CONFLICT:** [docs](https://www.postgresql.org/docs/16/sql-insert.html#SQL-ON-CONFLICT)
- **Spring Profiles:** `@Profile({"test", "staging", "perf"})` [docs](https://docs.spring.io/spring-framework/reference/core/beans/environment.html)
- **Keycloak Admin REST API:** [docs](https://www.keycloak.org/docs-api/22.0.5/rest-api/index.html)

---

## BUG-03 — order-service fica `unhealthy` após Stress Test

### Role

Você é um **Arquiteto de Software Sênior** especialista em **resiliência de sistemas distribuídos**, padrões de **Circuit Breaker**, **Bulkhead**, **Rate Limiting** e **self-healing**. Possui experiência com Resilience4j, Spring Boot Actuator e design para alta disponibilidade sob falha catastrófica.

### Contexto

Após o Stress Test (5000 req/s, 120s), o `order-service` entrou em estado `unhealthy` e **NÃO recuperou sozinho**. O Docker healthcheck (`wget /actuator/health`) falhou continuamente, exigindo restart manual do container.

**Causas prováveis:**
- Thread pool do Tomcat saturado e não drenado (threads em `WAITING` por recursos esgotados).
- HikariCP connection pool deadlocked (todas as conexões em uso, timeout, retry → loop).
- Redis Circuit Breaker em estado OPEN permanente (não transicionou para HALF_OPEN).
- JVM em GC thrashing (heap exaurido por objetos acumulados durante a carga).
- Backpressure do RabbitMQ causando channel starvation.

**Configuração atual do Circuit Breaker:**
```yaml
resilience4j:
    circuitbreaker:
        instances:
            redisMatchEngine:
                failure-rate-threshold: 50
                sliding-window-size: 10
                wait-duration-in-open-state: 30s
                automatic-transition-from-open-to-half-open-enabled: true
```

### Objetivos

1. **Diagnosticar** a causa raiz do travamento — instrumentar métricas e logs para capturar o estado interno.
2. **Implementar self-healing** — o serviço DEVE recuperar sozinho dentro de 60s após cessação da carga.
3. **Adicionar Circuit Breakers** para TODOS os recursos externos (PostgreSQL, MongoDB, RabbitMQ), não apenas Redis.
4. **Implementar Bulkhead** para isolar threads por recurso, prevenindo cascading failure.
5. **Configurar liveness vs readiness probes** no Spring Actuator para que o Kubernetes/Docker restart o pod se necessário.

### Validação Obrigatória (antes de alterar código)

- [ ] Ler `apps/order-service/src/main/resources/application.yaml` — verificar configuração existente de resilience4j.
- [ ] Ler todos os `@Service` que fazem chamadas externas (Redis, PostgreSQL, MongoDB, RabbitMQ).
- [ ] Ler `apps/order-service/pom.xml` — confirmar dependência resilience4j.
- [ ] Ler o healthcheck do Dockerfile — entender o que `/actuator/health` verifica.
- [ ] Verificar se `management.endpoint.health.group.liveness` e `.readiness` estão configurados.
- [ ] Analisar logs do stress test (se disponíveis) para identificar exceções recorrentes.

### TDD — Fase RED (obrigatória)

```java
// src/test/java/com/vibranium/order/resilience/CircuitBreakerRecoveryTest.java
@SpringBootTest
@Testcontainers
class CircuitBreakerRecoveryTest {

    @Test
    @DisplayName("RED: Após falha massiva no Redis, circuit breaker deve transicionar para HALF_OPEN e eventual CLOSED")
    void shouldRecoverFromRedisFailure() {
        // GIVEN: Redis está indisponível → circuit breaker OPEN
        redisContainer.stop();
        // Executar N chamadas para abrir o circuit breaker
        for (int i = 0; i < 20; i++) {
            assertThrows(Exception.class, () -> matchEngineService.submitOrder(testOrder()));
        }
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("redisMatchEngine");
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // WHEN: Redis volta a funcionar
        redisContainer.start();
        // Aguardar wait-duration-in-open-state
        await().atMost(Duration.ofSeconds(35)).until(
            () -> cb.getState() == CircuitBreaker.State.HALF_OPEN
        );

        // THEN: Próxima chamada deve passar e circuito fechar
        assertDoesNotThrow(() -> matchEngineService.submitOrder(testOrder()));
        await().atMost(Duration.ofSeconds(5)).until(
            () -> cb.getState() == CircuitBreaker.State.CLOSED
        );
    }

    @Test
    @DisplayName("RED: Health endpoint deve reportar DOWN quando circuit breaker está OPEN")
    void shouldReportUnhealthyWhenCircuitOpen() {
        // Abrir circuit breaker
        // GET /actuator/health/readiness deve retornar 503
        // GET /actuator/health/liveness deve retornar 200 (liveness = "o processo está vivo")
    }
}
```

### Regras Obrigatórias

1. **Liveness ≠ Readiness:** Liveness probe indica que o processo está vivo (JVM não travou). Readiness indica que aceita tráfego. Um circuit breaker OPEN afeta readiness, NÃO liveness.
2. **Circuit Breaker obrigatório em TODO recurso externo:** Redis, PostgreSQL (HikariCP), MongoDB, RabbitMQ.
3. **Bulkhead por recurso:** Usar `@Bulkhead` ou thread pool isolation para limitar threads concorrentes por recurso.
4. **Fallback obrigatório:** Quando o circuit breaker está OPEN, retornar `503 Service Unavailable` com header `Retry-After`, NÃO travar o thread.
5. **Métricas Micrometer:** Expor estado dos circuit breakers via `/actuator/prometheus` — tag: `resilience4j_circuitbreaker_state`.
6. **Log estruturado:** `logger.warn("Circuit breaker {} transitioned to {}", name, state)` em transições.
7. **Não silenciar exceções** — sempre logar + propagar com status code adequado.

### Critérios de Aceite

| # | Critério | Validação |
|---|---|---|
| 1 | Após stress test, order-service recupera em ≤60s | Healthcheck volta a 200 |
| 2 | Circuit breakers em Redis, PG, MongoDB, RabbitMQ configurados | `application.yaml` |
| 3 | `/actuator/health/liveness` retorna 200 mesmo durante sobrecarga | `curl` |
| 4 | `/actuator/health/readiness` retorna 503 quando circuit breaker OPEN | `curl` |
| 5 | Métricas circuit breaker visíveis no Prometheus | `curl /actuator/prometheus \| grep circuitbreaker` |
| 6 | Bulkhead limita threads por recurso (max concurrency configurável) | Config YAML |
| 7 | Testes de recuperação passam | `mvn test` |

### Saída Estruturada

```
apps/order-service/src/main/resources/application.yaml               — Circuit breakers + bulkheads + health groups
apps/order-service/src/main/java/.../config/ResilienceConfig.java     — @Bean customizado (se necessário)
apps/order-service/src/main/java/.../handler/GlobalExceptionHandler.java — Fallback com 503 + Retry-After
apps/order-service/src/test/java/.../CircuitBreakerRecoveryTest.java  — Testes TDD
```

### Resultado Esperado

- O order-service se recupera automaticamente após cessação de carga extrema.
- Health probes diferenciados permitem que o orquestrador (Docker/K8s) tome decisões corretas de roteamento.
- Dashboards Grafana exibem estado dos circuit breakers em tempo real.

### Arquivos de Referência

| Arquivo | Propósito |
|---|---|
| [apps/order-service/src/main/resources/application.yaml](apps/order-service/src/main/resources/application.yaml) | Config resilience4j atual |
| [apps/order-service/pom.xml](apps/order-service/pom.xml) | Dependências |
| [apps/order-service/src/test/resources/application-test.yml](apps/order-service/src/test/resources/application-test.yml) | Config test |
| [infra/grafana/dashboards/infrastructure.json](infra/grafana/dashboards/infrastructure.json) | Dashboard circuit breaker |
| [infra/grafana/provisioning/alerting/alerting.yml](infra/grafana/provisioning/alerting/alerting.yml) | Alerta circuit breaker |

### Ferramentas e Referências

- **Resilience4j Spring Boot 3:** [docs](https://resilience4j.readme.io/docs/getting-started-3)
- **Resilience4j CircuitBreaker:** [docs](https://resilience4j.readme.io/docs/circuitbreaker)
- **Resilience4j Bulkhead:** [docs](https://resilience4j.readme.io/docs/bulkhead)
- **Spring Boot Actuator Health Groups:** [docs](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html#actuator.endpoints.health.groups)
- **Micrometer Resilience4j:** [docs](https://resilience4j.readme.io/docs/micrometer)
- **Pattern: Bulkhead** — Michael Nygard, "Release It!" Chapter 5
- **Pattern: Circuit Breaker** — Martin Fowler [article](https://martinfowler.com/bliki/CircuitBreaker.html)

---

## BUG-04 — HTTP 500 sob carga — pool exhaustion sem tratamento graceful

### Role

Você é um **Engenheiro de Performance e Confiabilidade Sênior (SRE)** especialista em tuning de connection pools (HikariCP, Lettuce, MongoClient), tratamento de erros sob carga, graceful degradation e observabilidade de sistemas JVM de alta concorrência.

### Contexto

Durante os testes de performance (Load e Soak), ~0.2-0.7% das requests que estabeleceram conexão TCP receberam HTTP 500 — um erro genérico não tratado que esconde a causa raiz.

**Causas prováveis identificadas no relatório:**
- **HikariCP pool exhaustion:** Pool de 20 conexões (config atual) esgotado. Requests aguardam conexão até `connectionTimeout` (3000ms) e falham com `SQLTransientConnectionException`.
- **MongoDB connection pool exhaust:** Config padrão do driver MongoDB Java ~100 conexões. Sob carga, pode esgotar.
- **RabbitMQ channel starvation:** Publisher confirm com muitas publicações simultâneas.
- **Resposta genérica 500:** O `@ControllerAdvice` (ou sua ausência) não trata essas exceções específicas, retornando stack trace completo ao cliente — risco de **information disclosure** (OWASP A01).

### Objetivos

1. **Implementar tratamento graceful** de pool exhaustion — retornar `503 Service Unavailable` com `Retry-After` ao invés de `500 Internal Server Error`.
2. **Incrementar HikariCP pool** para 30 conexões (fórmula: `(cores * 2) + spindles` com margem para I/O-bound).
3. **Configurar MongoDB connection pool** explicitamente: `minPoolSize=20, maxPoolSize=100`.
4. **Eliminar vazamento de informação** — HTTP 500 NUNCA deve retornar stack trace ou detalhes internos ao cliente.
5. **Expor métricas de pool** via Micrometer para detecção proativa no Prometheus/Grafana.

### Validação Obrigatória (antes de alterar código)

- [ ] Ler `apps/order-service/src/main/resources/application.yaml` — verificar pool sizes atuais.
- [ ] Buscar `@ControllerAdvice` ou `@ExceptionHandler` no codebase — verificar se existe tratamento global de exceções.
- [ ] Ler o endpoint `POST /api/v1/orders` — rastrear o fluxo completo: Controller → Service → Repository.
- [ ] Verificar se `spring.datasource.hikari.leak-detection-threshold` está configurado.
- [ ] Ler `pom.xml` — confirmar dependência `micrometer-registry-prometheus` para métricas de pool.
- [ ] Verificar se `management.metrics.enable.hikaricp=true` está explícito.

### TDD — Fase RED (obrigatória)

```java
// src/test/java/com/vibranium/order/api/GracefulDegradationTest.java
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Testcontainers
class GracefulDegradationTest {

    @Test
    @DisplayName("RED: Pool exhaustion deve retornar 503 com Retry-After, não 500")
    void shouldReturn503WhenPoolExhausted() {
        // GIVEN: HikariCP com pool size = 1 (test override)
        // WHEN: 5 requests simultâneas (2+ falham por pool exhaustion)
        // THEN: Requests falhadas recebem 503, NÃO 500
        //       Response body NÃO contém stack trace
        //       Header Retry-After presente
    }

    @Test
    @DisplayName("RED: Resposta de erro NUNCA deve conter stack trace ou info interna")
    void shouldNotLeakInternalInfoOnError() {
        // GIVEN: Uma exceção qualquer
        // WHEN: O serviço retorna erro
        // THEN: Response body contém apenas: { "error": "...", "correlationId": "..." }
        //       NÃO contém: class name, line number, package name, SQL query
    }

    @Test
    @DisplayName("RED: Métricas HikariCP pool devem estar expostas no Prometheus endpoint")
    void shouldExposeHikariMetrics() {
        // GET /actuator/prometheus
        // Deve conter: hikaricp_connections_active, hikaricp_connections_idle,
        //              hikaricp_connections_pending, hikaricp_connections_timeout_total
    }
}
```

### Regras Obrigatórias

1. **OWASP A01 — Broken Access Control / Information Disclosure:** HTTP 500 NUNCA retorna stack trace, nome de classe, query SQL ou detalhes de infraestrutura ao cliente. Apenas: `{"error": "Service temporarily unavailable", "correlationId": "xxx", "retryAfter": 5}`.
2. **Toda exceção de pool exhaustion** (`SQLTransientConnectionException`, `MongoWaitQueueFullException`, `AmqpResourceNotAvailableException`) DEVE ser mapeada para HTTP 503 com header `Retry-After`.
3. **Logging obrigatório:** `logger.error("Pool exhaustion on {}: {}", resourceName, ex.getMessage(), ex)` — log completo no servidor, resposta limpa ao cliente.
4. **HikariCP leak detection:** Configurar `leak-detection-threshold: 30000` (30s) para detectar conexões não devolvidas.
5. **Métricas Micrometer:** HikariCP, MongoDB driver e RabbitMQ channel pool DEVEM expor métricas.
6. **Constructor Injection:** Todas as dependências injetadas via construtor com `private final`.

### Critérios de Aceite

| # | Critério | Validação |
|---|---|---|
| 1 | HTTP 500 substituído por 503 com `Retry-After` para pool exhaustion | Teste de integração |
| 2 | Response body de erro NÃO contém stack trace | Teste + auditoria manual |
| 3 | HikariCP pool size = 30 | `application.yaml` |
| 4 | MongoDB pool configurado explicitamente | URI ou config |
| 5 | Leak detection habilitado (30s) | Config |
| 6 | Métricas de pool visíveis em `/actuator/prometheus` | `curl` |
| 7 | Testes TDD passam | `mvn test` |
| 8 | Sob carga de 200 req/s, HTTP 500 = 0% (substituídos por 503 se necessário) | Gatling report |

### Saída Estruturada

```
apps/order-service/src/main/java/.../handler/GlobalExceptionHandler.java   — @ControllerAdvice com handlers
apps/order-service/src/main/java/.../dto/ErrorResponse.java                — DTO de resposta de erro padronizado
apps/order-service/src/main/resources/application.yaml                     — Pool tuning + leak detection
apps/order-service/src/test/java/.../GracefulDegradationTest.java          — Testes TDD
```

### Resultado Esperado

- Zero HTTP 500 nos relatórios de performance — substituídos por 503 com `Retry-After` onde aplicável.
- Nenhum vazamento de informação interna nas respostas de erro.
- Dashboards Grafana exibem uso de pools em tempo real para detecção proativa.

### Arquivos de Referência

| Arquivo | Propósito |
|---|---|
| [apps/order-service/src/main/resources/application.yaml](apps/order-service/src/main/resources/application.yaml) | Pool configs |
| [apps/order-service/pom.xml](apps/order-service/pom.xml) | Dependências |
| Controller que recebe `POST /api/v1/orders` (buscar no codebase) | Fluxo a instrumentar |
| [infra/grafana/dashboards/infrastructure.json](infra/grafana/dashboards/infrastructure.json) | Dashboard de infra |
| [docs/PERFORMANCE_REPORT.md](docs/PERFORMANCE_REPORT.md) | Relatório com números |

### Ferramentas e Referências

- **HikariCP Configuration:** [docs](https://github.com/brettwooldridge/HikariCP#frequently-used)
- **HikariCP Pool Sizing:** [About Pool Sizing](https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing)
- **Spring Boot Error Handling:** [docs](https://docs.spring.io/spring-boot/docs/current/reference/html/web.html#web.servlet.spring-mvc.error-handling)
- **OWASP Error Handling:** [Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Error_Handling_Cheat_Sheet.html)
- **Micrometer HikariCP Metrics:** [docs](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html#actuator.metrics.supported.data-source)
- **MongoDB Java Driver Connection Pooling:** [docs](https://www.mongodb.com/docs/drivers/java/sync/current/fundamentals/connection/connection-pool-settings/)
- **Pattern: Graceful Degradation** — Sam Newman, "Building Microservices" Chapter 11

---

## REC-5.1 — Escalar Horizontalmente (Kong Load Balancer + Réplicas)

### Role

Você é um **Arquiteto de Infraestrutura e Performance Sênior** especialista em **escalabilidade horizontal de microsserviços**, **API Gateway patterns (Kong)**, **load balancing**, **Docker Compose multi-replica** e **benchmark com Gatling**. Possui mentalidade de engenheiro de plataforma com foco em alta disponibilidade, resiliência e observabilidade.

### Contexto

O relatório de performance da plataforma Vibranium (07/03/2026) revelou que uma **única instância** do `order-service` (2 CPU, 2 GB RAM) sustenta apenas **~38-101 req/s**, muito abaixo do target de **1000 req/s**.

**Dados do relatório:**
| Cenário | Target | Resultado | Taxa Erro |
|---------|--------|-----------|-----------|
| Smoke (10 req/s) | PASS | 9.23 req/s, p99=26ms | 0.0% |
| Load (1000 req/s) | FAIL | 38.8 req/s efetivo | 94.2% |
| Stress (5000 req/s) | FAIL | 0 req/s OK | 100.0% |
| Soak (500 req/s) | FAIL | 101.3 req/s efetivo | 79.5% |

**Conclusão do relatório:** O serviço processa bem dentro da capacidade (p50=10-21ms), o gargalo é **exclusivamente de escalabilidade horizontal e infraestrutura**.

**Projeção:**
| Target | Réplicas | CPU total |
|--------|----------|-----------|
| 500 req/s | 5 | 10 CPU |
| 1000 req/s | 10 | 20 CPU |

**Solução necessária:**
1. `docker-compose.perf.flat.yml` com **5 réplicas** do `order-service` atrás do **Kong API Gateway** como load balancer round-robin.
2. Kong configurado com **upstreams + targets + healthchecks ativos/passivos**.
3. Rate limiting via **Redis distribuído** (não local) para funcionar corretamente com múltiplos nós Kong.
4. **Gatling apontando para Kong** (`http://kong:8000`) ao invés de diretamente ao order-service.

### Objetivos

1. **Criar** `tests/performance/docker-compose.perf.flat.yml` com a topologia completa de escala horizontal.
2. **Configurar Kong** como load balancer com:
   - Upstream `order-service-upstream` com algoritmo `round-robin`.
   - 5 targets: `order-service-1:8080` a `order-service-5:8080`.
   - Active healthchecks (GET `/actuator/health` a cada 5s).
   - Passive healthchecks (falhas detectadas automaticamente).
3. **Configurar Kong** com Redis para rate-limiting distribuído (redis-kong dedicado).
4. **Tuning do Tomcat** em cada réplica: `threads.max=400`, `max-connections=10000`.
5. **Tuning do HikariCP** em cada réplica: `maximum-pool-size=30`.
6. **Tuning do PostgreSQL** compartilhado: `max_connections=500` (suportar 5 réplicas × 30 conexões).
7. **Executar Smoke Test** via Kong e validar load balancing (requests distribuídas entre réplicas).

### Validação Obrigatória (antes de alterar código)

- [ ] Ler `tests/performance/docker-compose.perf.yml` — entender o compose existente (single instance).
- [ ] Ler `infra/docker-compose.yml` — entender como Kong está configurado no ambiente base.
- [ ] Ler `infra/kong/kong-setup.sh` — entender o provisionamento via Admin API.
- [ ] Ler `infra/kong/kong-config.md` — entender a topologia de rotas e plugins.
- [ ] Ler `infra/kong/kong-init.yml` — entender o formato declarativo (decK v3.0).
- [ ] Ler `apps/order-service/docker/Dockerfile` — confirmar que a imagem de produção (JAR) funciona corretamente.
- [ ] Ler `apps/wallet-service/docker/Dockerfile` — mesma verificação.
- [ ] Ler `tests/performance/src/test/java/com/vibranium/performance/BaseSimulationConfig.java` — confirmar que `TARGET_BASE_URL` é configurável via env var.
- [ ] Ler `tests/performance/src/test/java/com/vibranium/performance/helpers/KeycloakTokenFeeder.java` — confirmar compatibilidade com issuer interno.
- [ ] Verificar que `KEYCLOAK_ISSUER` no kong-init bate com o `iss` do token JWT.

### TDD — Fase RED (obrigatória)

```bash
#!/bin/bash
# tests/performance/validate-perf-flat.sh
# RED: Validação do docker-compose.perf.flat.yml antes de executar o benchmark

set -e

COMPOSE="tests/performance/docker-compose.perf.flat.yml"

echo "=== Validação docker-compose.perf.flat.yml ==="

# 1. Arquivo existe
test -f "$COMPOSE" || { echo "FAIL: $COMPOSE não existe"; exit 1; }

# 2. Sintaxe válida
docker compose -f "$COMPOSE" config > /dev/null 2>&1 || { echo "FAIL: Sintaxe inválida"; exit 1; }

# 3. Kong service presente
grep -q "kong:" "$COMPOSE" || { echo "FAIL: Kong não encontrado"; exit 1; }

# 4. Pelo menos 5 réplicas do order-service
ORDER_COUNT=$(grep -c "order-service-[0-9]:" "$COMPOSE")
[ "$ORDER_COUNT" -ge 5 ] || { echo "FAIL: Apenas $ORDER_COUNT réplicas (mínimo 5)"; exit 1; }

# 5. Kong upstream configurado para round-robin
grep -q "round-robin" "$COMPOSE" || { echo "FAIL: round-robin não configurado"; exit 1; }

# 6. Redis-Kong para rate-limiting distribuído
grep -q "redis-kong" "$COMPOSE" || { echo "FAIL: redis-kong ausente"; exit 1; }

# 7. Gatling aponta para Kong, não diretamente ao order-service
grep -q "TARGET_BASE_URL.*kong:8000" "$COMPOSE" || { echo "FAIL: Gatling não aponta para Kong"; exit 1; }

# 8. Healthchecks ativos no upstream
grep -q "healthchecks" "$COMPOSE" || { echo "FAIL: Healthchecks não configurados no upstream"; exit 1; }

# 9. JWT env vars presentes nos order-service
grep -q "SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI" "$COMPOSE" || { echo "FAIL: JWT env var ausente"; exit 1; }

# 10. PostgreSQL max_connections suficiente (≥300)
grep -q "max_connections=\(3\|4\|5\)[0-9][0-9]" "$COMPOSE" || { echo "FAIL: max_connections insuficiente"; exit 1; }

echo "PASS: Todas as validações passaram"
```

### Regras Obrigatórias

1. **Imagens de produção:** Usar `Dockerfile` (multi-stage com JAR), NÃO `Dockerfile.dev` (mvn spring-boot:run). Performance test deve refletir produção.
2. **Network isolation:** Todos os serviços na mesma rede `vibranium-perf-flat-network`. Nenhuma porta exposta desnecessariamente ao host.
3. **Kong como único entry-point:** Order-services NÃO expõem porta 8080 ao host. Apenas Kong expõe 8000.
4. **Rate-limiting via Redis:** Policy DEVE ser `redis` (não `local`) para funcionar corretamente com load balancer. Sem isso, cada nó Kong teria contador independente.
5. **Healthchecks obrigatórios:** Docker + Kong upstream (active + passive). Se uma réplica morre, Kong deve removê-la automaticamente.
6. **YAML anchors (x-):** Usar `x-order-service-common` para evitar duplicação de config entre réplicas.
7. **Resource limits:** Cada réplica com `limits: memory=2G, cpus=2.0`. PostgreSQL com recursos aumentados: `memory=2G, cpus=2.0`.
8. **Idempotência do kong-init:** O script de provisionamento DEVE usar `PUT` (idempotente), não apenas `POST`.
9. **Secrets:** Usar env vars com defaults `${VAR:-perftest}` — NÃO hardcodar senhas.
10. **Volumes nomeados com sufixo `-flat`:** Para não conflitar com `docker-compose.perf.yml`.

### Critérios de Aceite

| # | Critério | Validação |
|---|---|---|
| 1 | Arquivo `docker-compose.perf.flat.yml` existe e é válido | `docker compose config` |
| 2 | 5 réplicas do order-service definidas | `grep -c "order-service-[0-9]"` ≥ 5 |
| 3 | Kong upstream com 5 targets round-robin | Kong Admin API: `GET /upstreams/order-service-upstream/targets` |
| 4 | Active healthcheck no upstream (path: `/actuator/health`) | Kong Admin API |
| 5 | Passive healthcheck habilitado | Kong Admin API |
| 6 | Rate-limiting com `policy=redis` | Kong Admin API: `GET /plugins` |
| 7 | Redis-Kong dedicado presente | `docker ps \| grep redis-kong` |
| 8 | Gatling `TARGET_BASE_URL=http://kong:8000` | Compose env var |
| 9 | Smoke Test via Kong: 0% erros, requests distribuídas | Gatling report + Kong logs |
| 10 | JWT env vars presentes em todos os order-service | `grep` no compose |
| 11 | Nenhuma porta do order-service exposta ao host | Compose: sem `ports:` |
| 12 | Script de validação (`validate-perf-flat.sh`) passa | Exit code 0 |

### Saída Estruturada

```
tests/performance/docker-compose.perf.flat.yml        — Compose com escala horizontal (CRIADO)
tests/performance/validate-perf-flat.sh                — Script de validação pré-benchmark
docs/HORIZONTAL_SCALING.md (opcional)                  — Documentação da topologia
```

### Resultado Esperado

- **Smoke Test (10 req/s):** 0% erros, requests distribuídas entre as 5 réplicas (verificar via logs ou Prometheus).
- **Load Test (1000 req/s):** Taxa de erro **< 5%** (vs. 94.2% com single instance).
- **Throughput estimado:** `5 réplicas × ~100 req/s = ~500 req/s` mínimo.
- Kong exibe 5 targets `healthy` em `GET /upstreams/order-service-upstream/health`.

### Arquivos de Referência

| Arquivo | Propósito |
|---|---|
| [tests/performance/docker-compose.perf.yml](tests/performance/docker-compose.perf.yml) | Compose single-instance (base) |
| [tests/performance/docker-compose.perf.flat.yml](tests/performance/docker-compose.perf.flat.yml) | **NOVO** — Compose com escala horizontal |
| [infra/docker-compose.yml](infra/docker-compose.yml) | Kong config de referência |
| [infra/kong/kong-setup.sh](infra/kong/kong-setup.sh) | Provisionamento Admin API |
| [infra/kong/kong-config.md](infra/kong/kong-config.md) | Topologia de rotas |
| [infra/kong/kong-init.yml](infra/kong/kong-init.yml) | Formato declarativo (decK) |
| [apps/order-service/docker/Dockerfile](apps/order-service/docker/Dockerfile) | Imagem de produção |
| [apps/wallet-service/docker/Dockerfile](apps/wallet-service/docker/Dockerfile) | Imagem de produção |
| [tests/performance/src/test/java/com/vibranium/performance/BaseSimulationConfig.java](tests/performance/src/test/java/com/vibranium/performance/BaseSimulationConfig.java) | Config Gatling |
| [tests/performance/src/test/java/com/vibranium/performance/helpers/KeycloakTokenFeeder.java](tests/performance/src/test/java/com/vibranium/performance/helpers/KeycloakTokenFeeder.java) | Token JWT |
| [docs/PERFORMANCE_REPORT.md](docs/PERFORMANCE_REPORT.md) | Relatório com números |

### Ferramentas e Referências

- **Kong Upstream Object:** [docs](https://docs.konghq.com/gateway/3.4.x/admin-api/#upstream-object)
- **Kong Load Balancing:** [docs](https://docs.konghq.com/gateway/3.4.x/how-kong-works/load-balancing/)
- **Kong Active Health Checks:** [docs](https://docs.konghq.com/gateway/3.4.x/how-kong-works/health-checks/)
- **Kong Rate Limiting Plugin (Redis):** [docs](https://docs.konghq.com/hub/kong-inc/rate-limiting/)
- **Docker Compose YAML Anchors:** [docs](https://docs.docker.com/compose/compose-file/10-fragments/)
- **Gatling Java DSL:** [docs](https://docs.gatling.io/reference/script/protocols/http/protocol/)
- **Spring Boot Actuator Health:** [docs](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html)
- **HikariCP Pool Sizing:** [About Pool Sizing](https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing)
- **PostgreSQL max_connections tuning:** [docs](https://www.postgresql.org/docs/16/runtime-config-connection.html)
- **ZGC (Java 21):** [docs](https://docs.oracle.com/en/java/javase/21/gctuning/z-garbage-collector.html)
- **Pattern: Load Balancer** — Chris Richardson, "Microservices Patterns" Chapter 8
- **Pattern: Service Discovery** — Sam Newman, "Building Microservices" Chapter 8
- **12-Factor App — Concurrency:** [docs](https://12factor.net/concurrency)

### Topologia do docker-compose.perf.flat.yml

```
                           ┌──────────────────┐
                           │   Gatling Runner  │
                           │  (Maven + JDK 21) │
                           └────────┬─────────┘
                                    │ http://kong:8000
                           ┌────────▼─────────┐
                           │   Kong 3.4 (LB)  │
                           │  round-robin +    │
                           │  active health    │
                           └────────┬─────────┘
                 ┌──────────┬───────┼───────┬──────────┐
                 │          │       │       │          │
          ┌──────▼──┐ ┌────▼───┐ ┌─▼────┐ ┌▼─────┐ ┌─▼──────┐
          │ order-1 │ │order-2 │ │ord-3 │ │ord-4 │ │ order-5│
          │  :8080  │ │ :8080  │ │:8080 │ │:8080 │ │  :8080 │
          └────┬────┘ └───┬────┘ └──┬───┘ └──┬───┘ └───┬────┘
               │          │         │        │         │
     ┌─────────┴──────────┴─────────┴────────┴─────────┴──────┐
     │                    Shared Infrastructure                │
     │  PostgreSQL 16 │ MongoDB 7 │ Redis 7 │ RabbitMQ 3.13   │
     └─────────────────────────────────────────────────────────┘
               │                                    │
     ┌─────────▼──────────┐               ┌────────▼────────┐
     │   Keycloak 22      │               │  Redis-Kong     │
     │ (JWT issuer)       │               │ (rate-limiting) │
     └────────────────────┘               └─────────────────┘
```

---

## Apêndice A — Checklist de Execução

### Pré-requisitos para rodar o benchmark com escala horizontal

```powershell
# 1. Build das imagens de produção
mvn clean package -DskipTests -am -pl apps/order-service,apps/wallet-service

# 2. Build das imagens Docker
docker compose -f tests/performance/docker-compose.perf.flat.yml build

# 3. Subir infraestrutura
docker compose -f tests/performance/docker-compose.perf.flat.yml up -d

# 4. Aguardar todos os serviços ficarem healthy
docker compose -f tests/performance/docker-compose.perf.flat.yml ps

# 5. Verificar logs do kong-init
docker compose -f tests/performance/docker-compose.perf.flat.yml logs kong-init

# 6. Validar upstream do Kong
curl -s http://localhost:8001/upstreams/order-service-upstream/health | jq .

# 7. Executar Smoke Test
docker compose -f tests/performance/docker-compose.perf.flat.yml run --rm -e GATLING_SIMULATION=com.vibranium.performance.SmokeSimulation gatling

# 8. Verificar resultados em tests/performance/results-flat/

# 9. Cleanup
docker compose -f tests/performance/docker-compose.perf.flat.yml down -v
```

### Validação do Load Balancing

```bash
# Verificar que Kong distribui requests entre as 5 réplicas
curl -s http://localhost:8001/upstreams/order-service-upstream/health | jq '.data[].health'
# Esperado: 5x "HEALTHY"

# Verificar targets registrados
curl -s http://localhost:8001/upstreams/order-service-upstream/targets | jq '.data[].target'
# Esperado:
#   "order-service-1:8080"
#   "order-service-2:8080"
#   "order-service-3:8080"
#   "order-service-4:8080"
#   "order-service-5:8080"
```

---

## Apêndice B — Glossário de Padrões

| Padrão | Descrição | Uso na Plataforma |
|--------|-----------|-------------------|
| **Circuit Breaker** | Interrompe chamadas a serviços falhos para evitar cascading failure | BUG-03: Resilience4j em Redis, PG, MongoDB |
| **Bulkhead** | Isola recursos por pool para evitar que uma falha afete tudo | BUG-03: Thread pool isolation por recurso |
| **Graceful Degradation** | Retorna erro controlado ao invés de travar | BUG-04: 503 com Retry-After |
| **Load Balancer** | Distribui tráfego entre múltiplas instâncias | REC-5.1: Kong round-robin |
| **Rate Limiting** | Limita taxa de requests para proteger o backend | REC-5.1: Kong + Redis |
| **Health Check** | Verifica se o serviço está operacional | REC-5.1: Active + Passive no Kong upstream |
| **Outbox Pattern** | Garante consistência eventual entre DB e broker | Arquitetura existente (não alterar) |
| **CQRS** | Separa leitura de escrita | Arquitetura existente (não alterar) |
