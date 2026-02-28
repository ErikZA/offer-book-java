# Order Service

Microsserviço responsável pelo **Command Side e Query Side** do Livro de Ofertas.
Implementa CQRS com PostgreSQL no Command Side (escrita) e MongoDB no Query Side (leitura/Read Model).

## 📋 Responsabilidades

### Command Side
- ✅ Aceitar ordens (`POST /api/v1/orders`) com autenticação JWT (Keycloak)
- ✅ Verificar registro local do usuário (`tb_user_registry`) antes de aceitar a ordem
- ✅ Persistir a ordem em estado `PENDING` e publicar `ReserveFundsCommand` no RabbitMQ
- ✅ Consumir eventos `FundsReservedEvent` / `FundsReservationFailedEvent` do wallet-service
- ✅ Executar o Motor de Match atômico via Script Lua no Redis Sorted Set
- ✅ Consumir eventos `REGISTER` do Keycloak (plugin aznamier) via `amq.topic` e popular `tb_user_registry`
- ✅ Rotear mensagens falhas para Dead Letter Queue (`order.dead-letter`) após retry esgotado

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
│   └── OrderCommandService.java                      # Orquestração do fluxo de ordem
├── config/
│   ├── JacksonConfig.java                            # ObjectMapper com JavaTimeModule
│   ├── MongoIndexConfig.java                         # Index pre-creation + connection pool MongoDB
│   ├── RabbitMQConfig.java                           # Topologia: exchanges, filas, bindings, DLQ
│   └── SecurityConfig.java                           # JWT Resource Server (Keycloak)
├── domain/
│   ├── model/
│   │   ├── Order.java                                # Entidade com @Version (optimistic lock)
│   │   └── UserRegistry.java                        # Registro local de usuários Keycloak
│   └── repository/
│       ├── OrderRepository.java
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
| `wallet.commands.reserve-funds` | `vibranium.commands`  | `wallet.commands.reserve-funds`  | Saga: reserva de fundos   |
| — (event)                     | `vibranium.events`    | `order.events.order-received`      | Projeção Read Model       |

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
   → publica ReserveFundsCommand em wallet.commands.reserve-funds
   → publica OrderReceivedEvent em vibranium.events (Virtual Thread — não bloqueia a tx)
      │                                                        │
      │    (Command Side)                                      │ (Query Side — MongoDB)
      ↓                                               ┌────────┘
   wallet-service                             OrderEventProjectionConsumer
      ├── Fundos OK → FundsReservedEvent      │  onOrderReceived   → OrderDocument{PENDING}
      │      → FundsReservedEventConsumer     │  onFundsReserved   → status → OPEN
      │            → Lua no Redis             │  onMatchExecuted   → FILLED ou PARTIAL
      │                ├── match → FILLED     │  onOrderCancelled  → status → CANCELLED
      │                └── no match → OPEN   │
      └── Insuficiente → FundsReservationFailed
             → FundsReservationFailedEventConsumer → Order{CANCELLED}

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
```

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
| Flyway                            | Migrations (`V1__user_registry`, `V2__orders`)  |
| common-contracts                  | DTOs/Events compartilhados entre serviços       |
| testcontainers:mongodb (test)     | `MongoDBContainer` para testes de integração    |

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

**Service ID**: order-service  
**Port**: 8080 (produção) / aleatória em testes (`RANDOM_PORT`)  
**Perfis**: `dev`, `test`
