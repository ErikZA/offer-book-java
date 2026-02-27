# Order Service

Microsserviço do **Command Side** responsável por aceitar ordens de compra/venda,
executar o Motor de Match via Redis e coordenar a Saga de fundos com o wallet-service.

## 📋 Responsabilidades

- ✅ Aceitar ordens (`POST /api/v1/orders`) com autenticação JWT (Keycloak)
- ✅ Verificar registro local do usuário (`tb_user_registry`) antes de aceitar a ordem
- ✅ Persistir a ordem em estado `PENDING` e publicar `ReserveFundsCommand` no RabbitMQ
- ✅ Consumir eventos `FundsReservedEvent` / `FundsReservationFailedEvent` do wallet-service
- ✅ Executar o Motor de Match atômico via Script Lua no Redis Sorted Set
- ✅ Consumir eventos `REGISTER` do Keycloak (plugin aznamier) via `amq.topic` e popular `tb_user_registry`
- ✅ Rotear mensagens falhas para Dead Letter Queue (`order.dead-letter`) após retry esgotado

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
│   ├── RabbitMQConfig.java                           # Topologia: exchanges, filas, bindings, DLQ
│   └── SecurityConfig.java                           # JWT Resource Server (Keycloak)
├── domain/
│   ├── model/
│   │   ├── Order.java                                # Entidade com @Version (optimistic lock)
│   │   └── UserRegistry.java                        # Registro local de usuários Keycloak
│   └── repository/
│       ├── OrderRepository.java
│       └── UserRegistryRepository.java
└── web/
    ├── controller/
    │   └── OrderCommandController.java               # POST /api/v1/orders
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

| Fila                        | Exchange             | Routing Key              |
|-----------------------------|----------------------|--------------------------|
| `wallet.commands.reserve-funds` | `vibranium.commands` | `wallet.commands.reserve-funds` |

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

## 🔄 Fluxo da Saga de Ordens

```
POST /api/v1/orders
   → verifica tb_user_registry (403 se ausente)
   → persiste Order{PENDING} em tb_orders
   → publica ReserveFundsCommand em wallet.commands.reserve-funds
      ↓
   wallet-service processa ReserveFundsCommand
      ├── Fundos OK → publica FundsReservedEvent
      │      → FundsReservedEventConsumer
      │            → RedisMatchEngineAdapter (Lua atômico)
      │                ├── match encontrado → Order{FILLED}, publica MatchExecutedEvent
      │                └── sem match       → Order{OPEN}, publica OrderAddedToBookEvent
      └── Fundos insuficientes → publica FundsReservationFailedEvent
             → FundsReservationFailedEventConsumer → Order{CANCELLED}
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

| Método | Path               | Auth      | Descrição                            |
|--------|--------------------|-----------|--------------------------------------|
| POST   | `/api/v1/orders`   | JWT (USER) | Aceitar ordem de compra ou venda    |

### Respostas de erro padronizadas

O `GlobalExceptionHandler` retorna `ResponseEntity<Map<String, Object>>` com campos no root do JSON
(não RFC 7807 / `ProblemDetail`) para garantir compatibilidade com JSONPath nos testes e clientes:

| Exceção                       | Status | Corpo                                       |
|-------------------------------|--------|---------------------------------------------|
| `UserNotRegisteredException`  | 403    | `{"error": "USER_NOT_REGISTERED", ...}`     |
| `MethodArgumentNotValidException` | 400 | `{"errors": [{"field": "...", "message": "..."}], ...}` |

## 📦 Dependências principais

| Biblioteca               | Uso                                        |
|--------------------------|--------------------------------------------|
| Spring Boot Web          | REST API                                   |
| Spring Data JPA          | PostgreSQL / Hibernate                     |
| Spring AMQP              | RabbitMQ (producers + consumers)           |
| Spring Data Redis        | StringRedisTemplate para script Lua        |
| Spring Security OAuth2   | JWT Resource Server                        |
| Flyway                   | Migrations (`V1__user_registry`, `V2__orders`) |
| common-contracts         | DTOs/Events compartilhados entre serviços  |

---

**Service ID**: order-service  
**Port**: 8080 (produção) / aleatória em testes (`RANDOM_PORT`)  
**Perfis**: `dev`, `test`
