# 🛡️ Guia de Qualidade e Observabilidade: Confiança em Larga Escala

Em um sistema de missão crítica que envolve negociação de ativos como o Vibranium, não podemos confiar apenas em testes manuais. Precisamos de uma infraestrutura que valide a resiliência e a rastreabilidade exigidas.

## 1. Testes de Integração Reais (Testcontainers)

Tradicionalmente, desenvolvedores usam bancos de dados "falsos" (H2 ou mocks) para testes. No entanto, o comportamento de um **Redis Sorted Set** ou de uma transação **ACID no PostgreSQL** é difícil de simular perfeitamente.

* **O Conceito**: O *Testcontainers* permite que, durante a execução dos testes, o sistema suba instâncias reais (em contêineres Docker efêmeros) de todas as nossas bases de dados e mensageria.
* **A Aplicação**: Garantimos que o código Java interaja corretamente com as versões exatas do Postgres, Mongo, Redis e RabbitMQ que serão usadas em produção, eliminando o "na minha máquina funciona".

### 1.1 Configuração do PostgreSQL para testes

O PostgreSQL de testes não requer mais `wal_level=logical` pois o relay do Outbox agora usa Polling com `SELECT FOR UPDATE SKIP LOCKED` em vez de Debezium CDC. O container é iniciado com configuração padrão:

```java
// AbstractIntegrationTest.java
static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine")
    .withReuse(true);
```

Isso simplifica a infraestrutura de testes e elimina a dependência de replicação lógica.

### 1.2 Nota histórica: race condition com Debezium (removido)

> **Contexto histórico:** O Debezium Embedded foi removido do projeto e substituído por Polling SKIP LOCKED.
> A race condition documentada abaixo não se aplica mais, mas é mantida como referência.

Anterior ao Polling, o `DebeziumEngine` era iniciado em VirtualThread de forma assíncrona.
O slot de replicação levava 1–3 s para ficar ativo, criando uma janela cega onde INSERTs na
tabela outbox não eram capturados. A solução era `awaitSlotActive()` com poll de 500 ms.

**Com Polling SKIP LOCKED**, essa race condition não existe — o `@Scheduled` poll é
automaticamente executado pelo Spring Scheduler após o contexto estar completamente
inicializado.

---

## 2. Governança Arquitetural (ArchUnit)

Em um monorepo com múltiplos microsserviços e bibliotecas compartilhadas, é fácil um desenvolvedor acidentalmente misturar as camadas (ex: usar uma regra de negócio dentro de uma query de leitura).

* **O Conceito**: O *ArchUnit* é uma biblioteca de teste que analisa a estrutura do código (o bytecode).
* **A Aplicação**: Criamos "testes de arquitetura" que falham o build se alguém tentar, por exemplo, importar uma classe da pasta `command/` dentro da pasta `query/`. Isso garante que o padrão **CQRS** seja respeitado por todo o time para sempre.

## 3. Rastreabilidade Distribuída (AT-14.1 — Micrometer Tracing + OpenTelemetry)

Com ordens sendo enviadas freneticamente por robôs, entender por que uma transação falhou em um ecossistema de microsserviços é um desafio.

* **O Conceito**: Cada requisição recebe um **Trace ID** único no momento em que entra pelo API Gateway (Kong). Esse contexto é propagado automaticamente por todos os saltos — HTTP e AMQP — via o padrão **W3C TraceContext** (`traceparent` header).
* **A Aplicação**: O trace viaja pelo RabbitMQ dentro do header `traceparent`. Se a liquidação falhar no wallet-service, o Jaeger exibe a árvore de spans completa: `placeOrder → ReserveFunds → FundsReserved → Match → Settlement`, localizando exatamente onde a cadeia quebrou.

### 3.1 Implementação (AT-14.1)

#### Dependências (sem versão explícita — gerenciadas pelo Spring Boot BOM 3.4.x)

```xml
<!-- Conecta Micrometer Observation API → OTel SDK. Ativa W3CPropagator automático. -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>

<!-- Exporta spans via HTTP/protobuf para Jaeger (ou qualquer OTLP collector). -->
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>
```

#### Configuração (`application.yaml`)

```yaml
management:
  tracing:
    sampling:
      probability: 1.0       # dev: 100% dos spans; em prod reduzir para 0.1
  otlp:
    tracing:
      endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318}/v1/traces

logging:
  pattern:
    level: "%5p [${spring.application.name:},%X{traceId:-},%X{spanId:-}]"
```

O padrão de log injeta `traceId` e `spanId` via MDC em cada linha — permite `grep` direto por trace nos logs do container.

#### Propagação W3C entre serviços

O `RabbitTemplate` + `ObservationRegistry` criam um **span produtor** em cada `convertAndSend()` e injetam o header `traceparent` na mensagem AMQP:

```
traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
             └── version │ traceId (32 hex) │ spanId (16 hex) │ flags
```

O consumer (`@RabbitListener`) extrai o contexto via `W3CPropagator.extract()` e cria um **span filho** — o Jaeger exibe a relação `parent → child` dando visibilidade end-to-end.

#### Enriquecimento de spans com atributos de domínio

Os listeners da Saga adicionam atributos customizados ao span ativo via `Tracer.currentSpan()`:

```java
// FundsReservedEventConsumer, FundsReservationFailedEventConsumer, OrderCommandRabbitListener
io.micrometer.tracing.Span currentSpan = tracer.currentSpan();
if (currentSpan != null) {
    currentSpan
        .tag("saga.correlation_id", event.correlationId().toString())
        .tag("order.id", order.getId().toString());
}
```

No Jaeger, cada span exibe `saga.correlation_id` e `order.id` — facilitando correlação entre traces de serviços diferentes sem inspecionar logs.

#### Jaeger no ambiente de desenvolvimento

Jaeger `all-in-one:1.58` adicionado ao `docker-compose.dev.yml`:

| Porta | Protocolo | Uso |
|-------|-----------|-----|
| `16686` | HTTP | UI do Jaeger — visualizar traces |
| `4318`  | HTTP | OTLP receiver (apps → Jaeger) |
| `4317`  | gRPC | OTLP receiver alternativo |

Acesso: **`http://localhost:16686`** → selecionar serviço `order-service` ou `wallet-service`.

#### Considerações de performance

| Cenário | Overhead estimado |
|---|---|
| Sampling 1.0 em dev (500 spans/s × 5 µs/span) | ~2,5 ms total → ≤ 1% do SLA |
| Produção recomendado: sampling 0.1 ou tail-based | ~0,25 ms |

Em produção usar tail-based sampling via **OpenTelemetry Collector** (intermediário entre apps e backend) para amostrar apenas traces lentos ou com erro — sem reconfigurar os serviços.

### 3.2 Stack de Métricas (AT-12 — Prometheus + Grafana)

Complementando o tracing distribuído (Jaeger), a stack de **métricas** permite monitorar a saúde operacional dos serviços em tempo real.

#### Componentes

| Componente | Imagem | Porta (dev) | Função |
|:-----------|:-------|:------------|:-------|
| **Prometheus** | `prom/prometheus:v2.53.0` | `9090` | Coleta métricas via scrape `/actuator/prometheus` a cada 15s |
| **Grafana** | `grafana/grafana:11.1.0` | `3000` | Visualização, dashboards provisionados e alertas |

#### Scrape Targets

- `order-service:8080/actuator/prometheus` — métricas de ordens, matching, saga, outbox
- `wallet-service:8081/actuator/prometheus` — métricas de reservas, liquidações, liberações, outbox

#### Dashboards Provisionados (4)

| Dashboard | UID | Métricas principais |
|:----------|:----|:--------------------|
| **Order Flow** | `vibranium-order-flow` | `vibranium_orders_created_total`, `vibranium_orders_matched_total`, `vibranium_orders_cancelled_total`, `vibranium_outbox_queue_depth`, `vibranium_saga_duration_seconds`, `vibranium_redis_match_latency_seconds` |
| **Wallet Health** | `vibranium-wallet-health` | `vibranium_funds_reserved_total`, `vibranium_funds_settled_total`, `vibranium_funds_released_total`, `vibranium_outbox_queue_depth` |
| **Infrastructure** | `vibranium-infrastructure` | `hikaricp_connections_*`, `jvm_memory_*`, `jvm_gc_pause_seconds`, `process_cpu_usage`, `resilience4j_circuitbreaker_state` |
| **SLA** | `vibranium-sla` | `http_server_requests_seconds_bucket` (p50/p95/p99), error rate (5xx/total), `vibranium_saga_duration_seconds` |

#### Alertas Provisionados (3 cenários críticos)

| Alerta | Condição | `for` | Severidade |
|:-------|:---------|:------|:-----------|
| **Outbox Depth Critical** | `vibranium_outbox_queue_depth > 1000` | 5m | critical |
| **Error Rate > 5%** | `HTTP 5xx / total > 0.05` | 5m | critical |
| **Circuit Breaker Open** | `resilience4j_circuitbreaker_state > 0` | 1m | warning |

#### Provisioning via volumes (zero configuração manual)

Toda a configuração é injetada via volumes Docker no `docker-compose.dev.yml`:

```
infra/
├── prometheus/
│   └── prometheus.yml              # Scrape config (targets + interval)
└── grafana/
    ├── provisioning/
    │   ├── datasources/
    │   │   └── prometheus.yml       # Datasource Prometheus (auto-default)
    │   ├── dashboards/
    │   │   └── dashboard.yml        # Provider: carrega JSONs de /var/lib/grafana/dashboards
    │   └── alerting/
    │       └── alerting.yml         # Contact points + policies + 3 alert rules
    └── dashboards/
        ├── order-flow.json
        ├── wallet-health.json
        ├── infrastructure.json
        └── sla.json
```

Acesso: **`http://localhost:3000`** (admin/admin) → Folder "Vibranium" com os 4 dashboards.

---

## 4. Tolerância a Falhas (Resilience4j)

O desafio pede para compreender o que acontece quando diferentes componentes falham.

* **O Conceito**: Implementamos padrões de "disjuntor" (Circuit Breaker) e limites de taxa (Rate Limiting).
* **A Aplicação**: Se o banco de dados PostgreSQL ficar lento sob carga extrema, o *Resilience4j* "abre o circuito" para evitar que o serviço de Wallet trave completamente o sistema, permitindo uma falha graciosa ou uma resposta de erro rápida enquanto o banco se recupera.

### 4.1 Circuit Breaker — Redis Match Engine (order-service)

O `RedisMatchEngineAdapter` é protegido por um Circuit Breaker (`redisMatchEngine`) que evita chamadas repetidas ao Redis quando o servidor está indisponível.

#### Estados do Circuit Breaker

| Estado | Comportamento |
|:---------|:---------------------------------------------------------------|
| **CLOSED** | Requisições fluem normalmente para o Redis. Falhas são contadas na sliding window. |
| **OPEN** | Requisições falham imediatamente com `CallNotPermittedException` — sem tocar no Redis. A ordem é cancelada com motivo `REDIS_UNAVAILABLE`. |
| **HALF_OPEN** | Após `waitDurationInOpenState`, 1 requisição de teste é permitida. Se sucesso → CLOSED; se falha → OPEN novamente. |

#### Configuração

```yaml
resilience4j:
  circuitbreaker:
    instances:
      redisMatchEngine:
        failure-rate-threshold: 50          # Abre com 50% de falhas
        sliding-window-type: COUNT_BASED
        sliding-window-size: 10             # Janela de 10 chamadas
        minimum-number-of-calls: 5          # Mínimo para avaliar
        wait-duration-in-open-state: 30s    # Tempo em OPEN antes de HALF_OPEN
        permitted-number-of-calls-in-half-open-state: 1
        automatic-transition-from-open-to-half-open-enabled: true
  ratelimiter:
    instances:
      redisMatchEngine:
        limit-for-period: 500               # Proteção contra burst pós-recovery
        limit-refresh-period: 1s
        timeout-duration: 100ms
```

#### Exceções Monitoradas

Apenas exceções de infraestrutura Redis são registradas como falhas:
- `RedisConnectionFailureException`, `RedisSystemException` (Spring Data Redis)
- `RedisConnectionException`, `RedisCommandTimeoutException` (Lettuce)
- `QueryTimeoutException` (Spring DAO)

Exceções de negócio (ex: `IllegalArgumentException`) **não** contam como falha do circuito.

#### Métricas (Micrometer + Actuator)

O módulo `resilience4j-micrometer` publica automaticamente métricas que podem ser consultadas via Prometheus/Grafana:
- `resilience4j_circuitbreaker_state` — estado atual (0=CLOSED, 1=OPEN, 2=HALF_OPEN)
- `resilience4j_circuitbreaker_calls_seconds` — latência por resultado (successful, failed)
- `resilience4j_circuitbreaker_failure_rate` — taxa de falha na sliding window

Endpoints Actuator: `/actuator/circuitbreakers`, `/actuator/circuitbreakerevents`

#### Testes

| Classe | Tipo | Cenários |
|:-------|:-----|:---------|
| `CircuitBreakerOpenTest` | Unitário | Abertura após 5 falhas consecutivas, fail-fast em < 50ms, exceções de negócio não abrem |
| `CircuitBreakerHalfOpenTest` | Unitário | Fechamento após chamada bem-sucedida, reabertura se falhar em half-open |
| `CircuitBreakerMetricsIntegrationTest` | Integração | Registro no registry, endpoint Actuator, métricas Micrometer |
| `GracefulDegradationIntegrationTest` | Integração | Cancelamento gracioso com `REDIS_UNAVAILABLE`, processamento normal quando closed |

---

## 5. Isolamento de Contexto Spring em Testes de Integração (wallet-service)

### Configuração atual: `@EnableScheduling` + Polling

O `OutboxPublisherService` usa `@Scheduled(fixedDelayString = "${app.outbox.polling.interval-ms:2000}")` para executar o relay periodicamente. O bean é sempre carregado no contexto Spring (sem `@ConditionalOnProperty`).

Para testes que não precisam do relay ativo, o intervalo de polling pode ser configurado para um valor alto ou os eventos podem ser verificados diretamente no banco.

Para testes de integração que validam o relay end-to-end, o `application-test.yaml` configura:

```yaml
app:
  outbox:
    batch-size: 50
    polling:
      interval-ms: 1000   # polling mais rápido para testes
```

---

## 6. Idempotência por `eventId` — Dois Mecanismos, Uma Dependência (US-002)

### O problema: dois níveis de idempotência diferentes

O `FundsReservedEventConsumer` já tinha a guarda:

```java
if (order.getStatus() != OrderStatus.PENDING) { return; }  // descarta duplicata
```

Essa guarda protege contra o cenário onde a ordem já foi processada (status avançou). **Não protege**, porém, contra a janela entre a chegada da mensagem e o commit da transação JPA: se o broker re-entregar o mesmo `FundsReservedEvent` (mesmo `eventId`) antes do primeiro processamento fazer commit, ambas as threads passarão pela guarda de status (ambas vêem `PENDING`) e tentariam executar o match duas vezes.

### Solução: tabela `tb_processed_events` + INSERT-first

```java
// FundsReservedEventConsumer.java
try {
    // saveAndFlush garante que a violação de PK seja imediata,
    // antes de qualquer operação de negócio
    processedEventRepository.saveAndFlush(new ProcessedEvent(event.eventId()));
} catch (DataIntegrityViolationException duplicate) {
    logger.info("Re-entrega detectada: eventId={}", event.eventId());
    return;  // descarta silenciosamente
}
```

O banco garante unicidade pela PK `event_id` — mesma estratégia da tabela `idempotency_key` no `wallet-service`. O `saveAndFlush` força o flush antes de continuar, transformando a corrida em uma serialização determinísta pelo banco.

### Por que `saveAndFlush` e não `save`?

| `save` | `saveAndFlush` |
|---|---|
| Atrasa o INSERT até o flush automático do Hibernate | FORÇA o INSERT imediatamente |
| A PK dupóda pode não ser detectada na linha `save` | Violação é lançada na linha `saveAndFlush` |
| Risco de ambas as threads avançarem antes do commit | Somente a primeira thread avança |

### Relação com a guarda de status

As duas guardas são **complementares**, não redundantes:

| Guarda | Protege contra |
|---|---|
| `eventId` (tb_processed_events) | Re-entrega de mensagem pelo broker (at-least-once delivery) |
| `order.status != PENDING` | Processamento duplicado por race condition interna (Optimistic Lock) |

Manter ambas garante cobertura contra mensagens duplicadas **e** corridas de atualização de estado.

---

## 7. Métricas de Negócio via Micrometer/Prometheus (AT-15.2)

O tracing distribuído (AT-14.1) mostra **onde** uma requisição passou. As métricas de negócio complementam mostrando **quanto** e **quão rápido** o sistema está operando.

### 7.1 Dependência

```xml
<!-- Auto-configura PrometheusMeterRegistry + endpoint /actuator/prometheus -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

Adicionado a ambos os serviços (`order-service` e `wallet-service`). A versão é gerenciada pelo Spring Boot BOM.

### 7.2 Endpoint Prometheus

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
```

Acesso: `GET /actuator/prometheus` — retorna todas as métricas em formato text/plain para scrape do Prometheus.

### 7.3 Métricas instrumentadas

| Métrica | Tipo | Serviço | Tags | Descrição |
|---------|------|---------|------|-----------|
| `vibranium.orders.created` | Counter | order-service | `orderType=BUY\|SELL` | Incrementado em `OrderCommandService.placeOrder()` |
| `vibranium.orders.matched` | Counter | order-service | `fillType=TOTAL\|PARTIAL` | Incrementado em `FundsReservedEventConsumer.handleMatches()` |
| `vibranium.orders.cancelled` | Counter | order-service | `reason=<FailureReason>` | Incrementado em `FundsReservedEventConsumer.cancelOrder()` |
| `vibranium.funds.reserved` | Counter | wallet-service | `asset=BRL\|VIBRANIUM` | Incrementado em `WalletService.reserveFunds()` |
| `vibranium.funds.settled` | Counter | wallet-service | — | Incrementado em `WalletService.settleFunds()` |
| `vibranium.funds.released` | Counter | wallet-service | `reason=SAGA_COMPENSATION` | Incrementado em `WalletService.releaseFunds()` |
| `vibranium.saga.duration` | Timer | order-service | `outcome=MATCHED\|CANCELLED` | Tempo `createdAt → match/cancel` da Saga TCC |
| `vibranium.redis.match.latency` | Timer | order-service | — | Latência da execução Lua no Redis (match engine) |
| `vibranium.outbox.publish.latency` | Timer | ambos | — | Latência de publicação de cada mensagem outbox |
| `vibranium.outbox.queue.depth` | Gauge | ambos | — | Número de mensagens pendentes no outbox |

### 7.4 Implementação — Padrões utilizados

**Injeção:** `MeterRegistry` é injetado via construtor (constructor injection) em todos os serviços instrumentados.

**Contadores:** `Counter.builder("vibranium.xxx").tag(k, v).register(meterRegistry).increment()` — criados inline na primeira chamada, reutilizados pelo registro interno do Micrometer.

**Timers:** `Timer.builder("vibranium.xxx").register(meterRegistry).record(duration)` — registra distribuição estatística (p50, p95, p99, max).

**Gauges:** Registrados via `MetricsConfig` com `Gauge.builder().register()` usando um `Supplier<Number>` que consulta o repositório JPA.

### 7.5 Testes

| Teste | Serviço | Escopo |
|-------|---------|--------|
| `OrderMetricsTest` | order-service | Valida `orders.created` (BUY + SELL) e `outbox.queue.depth` |
| `PrometheusEndpointTest` | order-service | Valida `PrometheusMeterRegistry` bean + scrape contém métricas |
| `WalletMetricsTest` | wallet-service | Valida `funds.reserved` (BRL + VIB), `funds.settled`, `outbox.queue.depth` |
| `PrometheusEndpointTest` | wallet-service | Valida `PrometheusMeterRegistry` bean + scrape contém métricas |

> **Nota:** Testes de métricas usam `@AutoConfigureObservability` porque o Spring Boot 3 desabilita
> auto-configurações de exportação de métricas em testes por padrão
> (`ObservabilityContextCustomizerFactory$DisableObservabilityContextCustomizer`).

---
