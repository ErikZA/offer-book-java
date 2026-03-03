# Order Service

Microsserviço responsável pelo **Command Side e Query Side** do Livro de Ofertas.
Implementa CQRS com PostgreSQL no Command Side (escrita) e MongoDB no Query Side (leitura/Read Model).

## 📋 Responsabilidades

### Command Side
- ✅ Aceitar ordens (`POST /api/v1/orders`) com autenticação JWT (Keycloak)
- ✅ Verificar registro local do usuário (`tb_user_registry`) antes de aceitar a ordem
- ✅ Persistir a ordem em estado `PENDING` e gravar `ReserveFundsCommand` **e** `OrderReceivedEvent`
  em `tb_order_outbox` dentro da **mesma transação** (Outbox Pattern — AT-01.1)
- ✅ `OrderOutboxPublisherService` (scheduler) faz o relay atômico para o RabbitMQ de forma eventual
- ✅ Consumir eventos `FundsReservedEvent` / `FundsReservationFailedEvent` do wallet-service
- ✅ Executar o Motor de Match atômico via Script Lua no Redis Sorted Set
- ✅ Consumir eventos `REGISTER` do Keycloak (plugin aznamier) via `amq.topic` e popular `tb_user_registry`
- ✅ Rotear mensagens falhas para Dead Letter Queue (`order.dead-letter`) após retry esgotado
- ✅ Propagação W3C TraceContext em mensagens AMQP (`traceparent` header) e enriquecimento de spans com `saga.correlation_id` e `order.id` (AT-14.1)

### Query Side — Read Model (US-003)
- ✅ Projetar eventos da Saga em `OrderDocument` no MongoDB (consistência eventual)
- ✅ Expor histórico paginado de ordens por usuário (`GET /api/v1/orders`)
- ✅ Expor detalhe de ordem com array `history[]` completo (`GET /api/v1/orders/{orderId}`)
- ✅ Idempotência: `eventId` como chave de deduplicação no history — reentregas seguras

## 🏗️ Estrutura de Pacotes

```
src/main/java/com/vibranium/orderservice/
├── OrderServiceApplication.java
├── adapter/
│   ├── messaging/
│   │   ├── FundsReservationFailedEventConsumer.java  # CANCELLED após falha na reserva
│   │   ├── FundsReservedEventConsumer.java           # Match Engine → OPEN/FILLED
│   │   └── KeycloakEventConsumer.java                # Registro de usuário via REGISTER event
│   └── redis/
│       └── RedisMatchEngineAdapter.java              # Script Lua atômico no Sorted Set
├── application/service/
│   ├── IdempotencyKeyCleanupJob.java                  # Cleanup de tb_processed_events (7d retention)
│   ├── OrderCommandService.java                      # Orquestração do fluxo de ordem
│   ├── OrderOutboxPublisherService.java              # Relay outbox → RabbitMQ (scheduler)
│   └── SagaTimeoutCleanupJob.java                    # ⭐ Cancela PENDING expirados (AT-09.1)
├── config/
│   ├── JacksonConfig.java                            # ObjectMapper com JavaTimeModule
│   ├── MongoIndexConfig.java                         # Index pre-creation + connection pool MongoDB
│   ├── MongoTransactionConfig.java                   # TransactionManager MongoDB
│   ├── RabbitMQConfig.java                           # Topologia: exchanges, filas, bindings, DLQ
│   ├── SecurityConfig.java                           # JWT Resource Server (Keycloak)
│   └── TimeConfig.java                              # ⭐ Bean Clock.systemUTC() (AT-09.2)
├── domain/
│   ├── model/
│   │   ├── Order.java                                # Entidade com @Version (optimistic lock)
│   │   ├── OrderOutboxMessage.java                   # Outbox Pattern — relay para RabbitMQ
│   │   ├── ProcessedEvent.java                       # Idempotência por eventId
│   │   └── UserRegistry.java                        # Registro local de usuários Keycloak
│   └── repository/
│       ├── OrderOutboxRepository.java
│       ├── OrderRepository.java                      # ⭐ +findByStatusAndCreatedAtBefore (AT-09.1)
│       ├── ProcessedEventRepository.java
│       └── UserRegistryRepository.java
├── query/                                            # ← Query Side (Read Model — US-003)
│   ├── consumer/
│   │   └── OrderEventProjectionConsumer.java         # 4 listeners → projeta eventos no MongoDB
│   ├── model/
│   │   └── OrderDocument.java                       # @Document MongoDB com history[] desnorm.
│   └── repository/
│       └── OrderHistoryRepository.java               # MongoRepository com paginação por userId
└── web/
    ├── controller/
    │   ├── OrderCommandController.java               # POST /api/v1/orders
    │   └── OrderQueryController.java                 # GET /api/v1/orders, GET /{orderId}
    ├── dto/
    │   ├── PlaceOrderRequest.java                    # @Valid + Bean Validation
    │   └── PlaceOrderResponse.java
    └── exception/
        ├── GlobalExceptionHandler.java               # ResponseEntity<Map> — JSON plano no root
        └── UserNotRegisteredException.java
```

## 🏛️ Topologia RabbitMQ

### Exchanges

| Exchange              | Tipo   | Uso                                           |
|-----------------------|--------|-----------------------------------------------|
| `vibranium.commands`  | Direct | Comandos inter-serviços (ex: ReserveFunds)    |
| `vibranium.events`    | Topic  | Eventos de domínio compartilhados             |
| `vibranium.dlq`       | Direct | Dead Letter Exchange (mensagens rejeitadas)   |
| `amq.topic`           | Topic  | Exchange built-in do RabbitMQ (Keycloak)      |

### Filas consumidas pelo order-service

| Fila                        | Binding (RK)                                    | Consumer                         | DLX?  |
|-----------------------------|-------------------------------------------------|----------------------------------|-------|
| `order.events.funds-reserved`  | `wallet.events.funds-reserved`               | `FundsReservedEventConsumer`     | ✅    |
| `order.events.funds-failed`    | `wallet.events.funds-reservation-failed`     | `FundsReservationFailedEventConsumer` | ✅ |
| `order.keycloak.user-register` | `KK.EVENT.CLIENT.orderbook-realm.REGISTER`   | `KeycloakEventConsumer`          | ✅    |
| `order.dead-letter`            | `order.dead-letter` (em `vibranium.dlq`)     | — (inspecção manual)            | ❌    |

### Filas publicadas pelo order-service

| Fila / Exchange               | Exchange              | Routing Key                        | Propósito                 |
|-------------------------------|-----------------------|------------------------------------|---------------------------|
| `wallet.commands.reserve-funds` | `vibranium.commands`  | `wallet.commands.reserve-funds`  | Saga: reserva de fundos (via outbox)   |
| — (event)                     | `vibranium.events`    | `order.events.order-received`      | Projeção Read Model (via outbox — AT-01.1) |

### Filas de projeção (Query Side — US-003)

O Read Model MongoDB é populado por 4 filas dedicadas, cada uma consumindo um tipo de evento:

| Fila de Projeção                       | Binding (Routing Key)                         | Consumer                              |
|----------------------------------------|-----------------------------------------------|---------------------------------------|
| `order.projection.received`             | `order.events.order-received`                 | `onOrderReceived` → cria doc PENDING  |
| `order.projection.funds-reserved`       | `wallet.events.funds-reserved`                | `onFundsReserved` → status → OPEN     |
| `order.projection.match-executed`       | `order.events.match-executed`                 | `onMatchExecuted` → FILLED/PARTIAL    |
| `order.projection.cancelled`            | `order.events.order-cancelled`                | `onOrderCancelled` → status CANCELLED |

> **Fanout Pattern:** As filas de projeção compartilham a mesma fila `vibranium.events`
> TopicExchange com as filas do Command Side. Isso garante que o Read Model receba os mesmos
> eventos sem acoplamento direto com o fluxo da Saga.

> **ACK Mode — AT-1.2.1:** Os listeners de projeção usam `containerFactory = "autoAckContainerFactory"`
> (`AcknowledgeMode.AUTO`), enquanto os consumers do Command Side usam `manualAckContainerFactory`
> (`AcknowledgeMode.MANUAL`). O `application.yaml` define `acknowledge-mode: manual` globalmente;
> sem factory explícito, os listeners de projeção herdariam MANUAL e acumulariam mensagens
> `unacknowledged` no broker indefinidamente (sem `channel.basicAck()`). Projeções são idempotentes
> via filtro `$ne` no MongoDB, portanto AUTO ACK é seguro: a perda de um evento degrada o Read Model
> mas não corrompe os dados do Command Side.

### Dead Letter Queue (DLQ)

Filas com `x-dead-letter-exchange=vibranium.dlq` e `x-dead-letter-routing-key=order.dead-letter`
enviam mensagens para `order.dead-letter` após o número máximo de tentativas de retry.

O binding `vibranium.dlq → order.dead-letter` é declarado explicitamente em `RabbitMQConfig.deadLetterBinding()`.
Sem esse binding, mensagens mortas seriam descartadas silenciosamente (unroutable).

**Configuração de retry** (em `application.yaml`):
```yaml
spring.rabbitmq.listener.simple:
  retry:
    enabled: true
    max-attempts: 3
    initial-interval: 1000
    multiplier: 2.0
    max-interval: 10000
```

## 🔄 Fluxo Completo (Command Side + Query Side)

```
POST /api/v1/orders
   → verifica tb_user_registry (403 se ausente)
   → persiste Order{PENDING} em tb_orders (PostgreSQL)
   ╔══════════════════════════════════════════════════════╗
   ║  MESMA TRANSAÇÃO @Transactional — Outbox Pattern     ║
   ║  (AT-01.1: elimina Dual Write / Virtual Thread)      ║
   ║  → INSERT tb_order_outbox: ReserveFundsCommand       ║
   ║  → INSERT tb_order_outbox: OrderReceivedEvent        ║
   ╚══════════════════════════════════════════════════════╝
       ↓ (scheduler OrderOutboxPublisherService — relay assíncrono)
   RabbitMQ
      ├── wallet.commands.reserve-funds (ReserveFundsCommand)
      │       ↓
      │   wallet-service
      │      ├── Fundos OK → FundsReservedEvent
      │      │      → FundsReservedEventConsumer
      │      │            → Lua no Redis
      │      │                ├── match → FILLED
      │      │                └── no match → OPEN
      │      └── Insuficiente → FundsReservationFailed
      │             → FundsReservationFailedEventConsumer → Order{CANCELLED}
      │
      └── vibranium.events / order.events.order-received (OrderReceivedEvent)
              ↓ (Query Side — MongoDB)
          OrderEventProjectionConsumer
             onOrderReceived   → OrderDocument{PENDING}
             onFundsReserved   → status → OPEN
             onMatchExecuted   → FILLED ou PARTIAL
             onOrderCancelled  → status → CANCELLED

GET /api/v1/orders          → OrderQueryController → MongoDB (Read Model — consistência eventual)
GET /api/v1/orders/{id}     → OrderQueryController → MongoDB (history[] completo)
```

## 🔐 Segurança

- JWT RS256 validado pelo Spring Security Resource Server
- `issuer-uri` aponta para o Keycloak (`http://localhost:8080/realms/orderbook-realm`)
- Em testes: `SecurityMockMvcRequestPostProcessors.jwt()` substitui o decoder — sem chamada HTTP ao Keycloak

## 🚀 Desenvolvimento

### Executar localmente via Maven
```bash
./mvnw spring-boot:run -pl apps/order-service -Dspring-boot.run.profiles=dev
```

### Executar via Docker
```bash
docker compose -f infra/docker-compose.dev.yml up order-service
```

### Executar testes
```bash
# Apenas order-service
mvn test -pl apps/order-service

# Suite completa
mvn clean test

# Apenas o teste de resiliência do Outbox (AT-01.2)
mvn test -pl apps/order-service -Dtest=OrderOutboxResilienceIntegrationTest

# Apenas o teste de guarda arquitetural (AT-02.2)
mvn test -pl apps/order-service -Dtest=RoutingKeyLiteralTest

# Apenas o SagaTimeoutCleanupJob (AT-09.1/AT-09.2)
mvn test -pl apps/order-service -Dtest=SagaTimeoutCleanupJobTest
```

## 🧪 Cobertura de Testes de Integração

| Classe de Teste | Tipo | Cenário Principal | Acceptance Tag |
|---|---|---|---|
| `OrderCommandControllerTest` | Integração REST | Fluxo HTTP completo: 202, 400, 403 | — |
| `OrderIdempotencyIntegrationTest` | Integração | Reentrega idempotente de eventos via `eventId` | — |
| `OrderOutboxIntegrationTest` | Integração | Persistência atômica do Outbox (Fase RED → GREEN) | AT-01.1 |
| **`OrderOutboxResilienceIntegrationTest`** | **Integração (Resiliência)** | **Broker pausado → atomicidade → sem processamento indevido → recovery → não duplicidade** | **AT-01.2** |
| `OrderSagaConcurrencyTest` | Integração (Concorrência) | Optimistic lock + retry em ordens concorrentes | — |
| `KeycloakUserRegistryIntegrationTest` | Integração | Registro de usuário via evento Keycloak | — |
| `MatchEngineRedisIntegrationTest` | Integração | Script Lua atômico no Sorted Set Redis | — |
| **`RedisKeyFormatIT`** | **Integração (Spring Context)** | **FASE RED → GREEN: keys com hash tag `{vibranium}` injetadas via `@Value`** | **AT-11.1** |
| **`RedisClusterHashTagIT`** | **Integração (CRC16 + Testcontainers Cluster)** | **CRC16 slot equality; CROSSSLOT antes e sem erro após hash tags em cluster real** | **AT-11.1** |
| `OrderQueryControllerTest` | Integração REST | Read Model MongoDB — paginação e detalhe | — |
| **`RoutingKeyLiteralTest`** | **Guarda Arquitetural** | **Impede strings literais de routing key fora de `RabbitMQConfig`** | **AT-02.2** |
| **`TracingW3CPropagationIntegrationTest`** | **Integração (Tracing)** | **RED→GREEN: header W3C `traceparent` injetado pelo `RabbitTemplate` após AT-14.1** | **AT-14.1** |
| **`SagaTimeoutCleanupJobTest`** | **Integração (Clock fixo)** | **PENDING expirado → CANCELLED; OPEN preservado; idempotência; lista vazia** | **AT-09.1/09.2** |

### AT-11.1 — Hash Tags Redis para Redis Cluster

As keys do Match Engine foram atualizadas para usar a hash tag `{vibranium}`, garantindo que `{vibranium}:asks`, `{vibranium}:bids` e `{vibranium}:order_index` calculem o mesmo hash slot CRC16. Sem essa mudança, o Redis Cluster retornaria `CROSSSLOT Keys in request don't hash to the same slot` ao executar o `match_engine.lua` (que usa múltiplas keys via `KEYS[]`).

**Regra:** scripts Lua multi-key (`EVAL`/`EVALSHA`) só executam em Redis Cluster se todas as `KEYS[]` estiverem no mesmo slot. Hash tags resolvem isso de forma transparente com Redis standalone.

Testes de validação:
- `RedisKeyFormatIT` — valida formato das keys no Spring Context (FASE RED → GREEN)
- `RedisClusterHashTagIT` — demonstra CROSSSLOT sem hash tag e execução correta com hash tag em cluster real

```yaml
# application.yaml
app.redis.keys:
  asks:        "{vibranium}:asks"
  bids:        "{vibranium}:bids"
  order-index: "{vibranium}:order_index"
```

### AT-01.2 — Estratégia de Resiliência

O teste `OrderOutboxResilienceIntegrationTest` valida o Outbox Pattern em condição de falha real do broker:

- **Falha determinística**: combina `docker pause` (cgroups freezer) + `CachingConnectionFactory.resetConnection()` para forçar `AmqpConnectException` em ≤ 1 s por ciclo.
- **Sem sleeps fixos**: usa `Awaitility.during()` (invariant poll) e `Awaitility.until(isBrokerAmqpReachable)` para todas as esperas.
- **Containers isolados**: `@Container` sem `withReuse()`. Não herda `AbstractIntegrationTest` para garantir que `pause/unpause` não afete demais testes. O `RabbitMQContainer` usa sua wait strategy padrão (log message `"Server startup complete"`) — não sobrescrever com `Wait.forListeningPort()`, que verifica apenas a porta TCP antes do broker terminar o startup.
- **Override de properties**:
  - `app.outbox.delay-ms=1000` — reduz o poll de 5 s para 1 s (3 ciclos confirmados em 4 s).
  - `spring.rabbitmq.connection-timeout=1000` — AMQP handshake expira em 1 s (não 120 s TCP).

## 🔗 Endpoints

| Método | Path                     | Auth       | Descrição                                      |
|--------|--------------------------|------------|------------------------------------------------|
| POST   | `/api/v1/orders`         | JWT (USER) | Aceitar ordem de compra ou venda (202 Accepted)|
| GET    | `/api/v1/orders`         | JWT (USER) | Listar ordens paginadas do usuário (Read Model)|
| GET    | `/api/v1/orders/{id}`    | JWT (USER) | Detalhe de ordem com history[] completo        |

### Paginação (GET /api/v1/orders)

| Parâmetro | Padrão | Máximo | Descrição              |
|-----------|--------|--------|------------------------|
| `page`    | `0`    | —      | Página zero-indexed     |
| `size`    | `20`   | `100`  | Docs por página         |

> **Segurança:** o `userId` é extraído exclusivamente do claim `sub` do JWT.
> Usuários não podem consultar ordens uns dos outros, mesmo passando outro ID na URL.

### Respostas de erro padronizadas

O `GlobalExceptionHandler` retorna `ResponseEntity<Map<String, Object>>` com campos no root do JSON
(não RFC 7807 / `ProblemDetail`) para garantir compatibilidade com JSONPath nos testes e clientes:

| Exceção                       | Status | Corpo                                       |
|-------------------------------|--------|---------------------------------------------|
| `UserNotRegisteredException`  | 403    | `{"error": "USER_NOT_REGISTERED", ...}`     |
| `MethodArgumentNotValidException` | 400 | `{"errors": [{"field": "...", "message": "..."}], ...}` |

## 📦 Dependências principais

| Biblioteca                        | Uso                                             |
|-----------------------------------|-------------------------------------------------|
| Spring Boot Web                   | REST API                                        |
| Spring Data JPA                   | PostgreSQL / Hibernate (Command Side)           |
| Spring Data MongoDB               | Read Model — `OrderDocument` + `MongoRepository`|
| Spring AMQP                       | RabbitMQ (producers + consumers + projection)   |
| Spring Data Redis                 | StringRedisTemplate para script Lua             |
| Spring Security OAuth2            | JWT Resource Server                             |
| Flyway                            | Migrations (`V1`–`V5` tabelas, `V6__add_index_orders_saga_timeout`) |
| common-contracts                  | DTOs/Events compartilhados entre serviços       |
| testcontainers:mongodb (test)     | `MongoDBContainer` para testes de integração    |
| testcontainers:rabbitmq (test)    | `RabbitMQContainer` — AT-01.2 resiliência com `docker pause/unpause` |
| awaitility (test)                 | Esperas determinísticas sem `Thread.sleep()` nos testes de resiliência |

## 🍃 Read Model — MongoDB

### OrderDocument (coleção `orders`)

```
OrderDocument {
  orderId:       String   // @Id — UUID como string
  userId:        String   // claim sub do JWT
  orderType:     String   // BUY | SELL
  price:         BigDecimal
  amount:        BigDecimal
  remainingQty:  BigDecimal  // decrementado a cada MatchExecutedEvent
  status:        String   // PENDING → OPEN → FILLED | PARTIAL | CANCELLED
  createdAt:     Instant
  updatedAt:     Instant
  history[]:     OrderHistoryEntry[]  // log imutável de cada evento
    ├── eventId:    UUID     // chave de idempotência (deduplicação)
    ├── eventType:  String   // ORDER_RECEIVED | FUNDS_RESERVED | MATCH_EXECUTED | CANCELLED
    ├── timestamp:  Instant
    └── metadata:  Map<String, Object>
}
```

**Índice composto** `idx_userId_createdAt` → `{userId: 1, createdAt: -1}`:  
Suporta `findByUserIdOrderByCreatedAtDesc(userId, Pageable)` em O(log n).

### Consistência

O Read Model é **eventualmente consistente** com o Command Side (PostgreSQL).
Após o `POST /api/v1/orders` retornar `202 Accepted`, o documento MongoDB pode
levar alguns milissegundos para ser criado (tempo de propagação pelo RabbitMQ).

---

## 🪨 MongoDB Replica Set — Suporte a Transações (AT-1.3.1)

### Problema
MongoDB standalone não suporta transações multi-documento. O `MongoTransactionManager` falha com:
```
Transaction numbers are only allowed on a replica set member or mongos
```

### Solução — Single-Node Replica Set `rs0`

```
Boot do container MongoDB:
  docker-entrypoint-override.sh
    → gera /etc/mongod-keyfile (openssl rand -base64 756)
    → delega ao entrypoint oficial: mongod --replSet rs0 --bind_ip_all --keyFile ...

Servico mongo-rs-init (container de curta duração):
  depends_on: mongodb: service_healthy
    → rs.initiate({ _id: "rs0", members: [{_id: 0, host: "mongodb:27017"}] })
    → aguarda stateStr === "PRIMARY"
    → sai com código 0

order-service:
  depends_on: mongo-rs-init: service_completed_successfully
  SPRING_DATA_MONGODB_URI: ...?replicaSet=rs0&authSource=admin
```

**Por que `--keyFile`?** MongoDB 7 exige autenticação intra-cluster (`keyFile`) sempre que `--auth` e `--replSet` estão ativos juntos — mesmo num single-node. O `docker-entrypoint-override.sh` gera o keyFile automaticamente no boot; regen na cada restart é seguro porque nenhum outro membro precisa autenticar com este nó.

### URI de conexão

| Ambiente | URI |
|---|---|
| Dev local (`mongod --replSet rs0`) | `mongodb://localhost:27017/vibranium_orders?replicaSet=rs0` |
| Docker Compose dev | `mongodb://admin:***@mongodb:27017/vibranium_orders?replicaSet=rs0&authSource=admin` |

---

## ⏱️ Saga Timeout — Ciclo de Vida Finito (AT-09.1 + AT-09.2)

### Problema
Ordens podem ficar presas em `PENDING` indefinidamente se a wallet falhar, o broker perder a mensagem ou o serviço reiniciar. Sem cleanup, acumulam-se **ordens zumbi**.

### Solução — `SagaTimeoutCleanupJob`
Job `@Scheduled` (a cada 60s) que cancela ordens `PENDING` com `created_at < now() - threshold`:

```
A cada 60s:
  cutoff = clock.instant() - app.saga.pending-timeout-minutes (padrão: 5min)
  stale  = orderRepository.findByStatusAndCreatedAtBefore(PENDING, cutoff)
  stale.forEach:
    → order.cancel("SAGA_TIMEOUT")          // PENDING → CANCELLED
    → orderRepository.save(order)           // persiste
    → outboxRepository.save(OrderCancelledEvent)  // evento no outbox
    → logger.warn(orderId, userId, age)     // log auditoria
```

### Configuração (`application.yaml`)
```yaml
app:
  saga:
    pending-timeout-minutes: 5    # threshold para cancelamento automático
    cleanup-delay-ms: 60000       # intervalo de execução do job (fixedDelay)
```

### Abstração temporal — `TimeConfig` + `Clock` (AT-09.2)
O `SagaTimeoutCleanupJob` usa `clock.instant()` em vez de `Instant.now()`. O bean `Clock` (definido em `TimeConfig`) é `Clock.systemUTC()` em produção e `Clock.fixed(...)` em testes:

```java
// Produção: TimeConfig.java
@Bean public Clock clock() { return Clock.systemUTC(); }

// Teste: @TestConfiguration com @Primary
@Bean @Primary Clock testClock() {
    return Clock.fixed(Instant.now().plusSeconds(3_600), ZoneOffset.UTC);
}
```

### Migração Flyway V6
Adicionado índice parcial para performance do job:
```sql
CREATE INDEX idx_orders_status_created_at ON tb_orders (created_at)
    WHERE status = 'PENDING';  -- apenas linhas elegíveis → índice compacto
```

---

**Service ID**: order-service  
**Port**: 8080 (produção) / aleatória em testes (`RANDOM_PORT`)  
**Perfis**: `dev`, `test`
