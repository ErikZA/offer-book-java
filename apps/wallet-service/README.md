# Wallet Service

Microsserviço responsável pela gestão de carteiras de usuários e transações.

## 📋 Responsabilidades

- ✅ Criar carteira para novo usuário
- ✅ Depositar fundos
- ✅ Sacar fundos
- ✅ Manter saldo consistente
- ✅ Registrar histórico de transações
- ✅ Consumir eventos de ordem para débito/crédito
- ✅ Publicar eventos de transação

## 🏗️ Estrutura

```
src/
├── main/
│   ├── java/com/vibranium/walletservice/
│   │   ├── WalletServiceApplication.java
│   │   ├── WalletController.java                         # Endpoints REST
│   │   ├── application/
│   │   │   ├── dto/                                      # WalletResponse, KeycloakEventDto
│   │   │   └── service/WalletService.java                # Orquestra casos de uso
│   │   ├── domain/
│   │   │   ├── model/
│   │   │   │   ├── Wallet.java                           # Aggregate Root (US-005)
│   │   │   │   ├── OutboxMessage.java
│   │   │   │   └── IdempotencyKey.java
│   │   │   └── repository/                               # Interfaces JPA
│   │   ├── infrastructure/
│   │   │   ├── messaging/                                # Listeners RabbitMQ
│   │   │   └── outbox/                                   # Debezium + OutboxPublisher
│   │   ├── config/                                       # RabbitMQ, Outbox, Jackson
│   │   └── exception/                                    # InsufficientFundsException, etc.
│   └── resources/
│       ├── application.yaml
│       └── db/migration/                                 # V1…V4 (Flyway)
└── test/
    ├── java/com/vibranium/walletservice/
    │   ├── unit/
    │   │   ├── WalletDomainTest.java                     # Testes de domínio puro (US-005)
    │   │   └── EventRouteTest.java
    │   ├── unit/
    │   │   ├── WalletDomainTest.java                     # Testes de domínio puro (US-005)
    │   │   ├── WalletServiceLockOrderTest.java           # TDD lock ordering ABBA (AT-03.1)
    │   │   └── EventRouteTest.java
    │   └── integration/
    │       ├── WalletReserveFundsIntegrationTest.java
    │       ├── WalletSettleFundsIntegrationTest.java
    │       ├── WalletConcurrentDeadlockTest.java         # AT-03.2 — Ausência de deadlock ABBA (PostgreSQL real)
    │       ├── WalletIdempotencyIntegrationTest.java
    │       ├── WalletBalanceUpdateIntegrationTest.java
    │       └── OutboxPublisherIntegrationTest.java
    └── resources/application-test.yaml

docker/
├── Dockerfile                # Build production
└── Dockerfile.dev            # Build desenvolvimento
```

## 🚀 Desenvolvimento

### Executar via Docker (Recomendado)
```bash
# Inicie ambiente de desenvolvimento com hotreload
.\build.ps1 docker-dev-up       # Windows
make docker-dev-up              # Linux/Mac

# Ou direto com Docker Compose
docker compose -f infra/docker-compose.dev.yml up wallet-service
```

### Testes via Docker
```bash
# Testes em container
.\build.ps1 docker-test         # Windows  
make docker-test                # Linux/Mac

# Ou direto
docker compose -f tests/docker-compose.test.yml up
```

### Debug Remoto
```bash
# Conecte debugger na porta 5006
# Veja: docs/testing/COMPREHENSIVE_TESTING.md#debug-remoto
```

## 🔗 Integração

### Endpoints
```
POST   /api/wallets              # Criar carteira
GET    /api/wallets/{id}         # Buscar carteira
PUT    /api/wallets/{id}/deposit # Depositar
PUT    /api/wallets/{id}/withdraw # Sacar
GET    /api/wallets/{id}/transactions # Histórico
```

### Eventos Consumidos
- `OrderCreatedEvent` - Para validar fundos
- `OrderMatchedEvent` - Para atualizar saldo
- `OrderCancelledEvent` - Para reembolsar

#### Eventos de Autenticação (Keycloak → RabbitMQ)

O `wallet-service` deve consumir eventos publicados pelo **plugin `keycloak-event-listener-rabbitmq`** no exchange `amq.topic` do RabbitMQ. Esses eventos permitem reagir de forma reativa a mudanças de ciclo de vida do usuário, sem acoplamento direto ao Keycloak.

| Evento Keycloak | Routing Key AMQP | Ação esperada no wallet-service |
|---|---|---|
| `REGISTER` | `KK.EVENT.REALM.orderbook-realm.REGISTER` | **Criar carteira automaticamente** para o novo usuário com saldo zero |
| `LOGIN` | `KK.EVENT.REALM.orderbook-realm.LOGIN` | (Opcional) Registrar último acesso na carteira para auditoria |
| `LOGOUT` | `KK.EVENT.REALM.orderbook-realm.LOGOUT` | (Opcional) Invalidar cache local de sessão |
| `DELETE_ACCOUNT` | `KK.EVENT.REALM.orderbook-realm.DELETE_ACCOUNT` | Iniciar processo de encerramento de conta / bloqueio de carteira |

O payload publicado no broker segue o formato:

```json
{
  "id": "<uuid-do-evento>",
  "time": 1709041200000,
  "realmId": "orderbook-realm",
  "type": "REGISTER",
  "userId": "<keycloak-user-id>",
  "clientId": "order-client",
  "ipAddress": "192.168.1.1",
  "details": {
    "username": "tester",
    "email": "tester@vibranium.com"
  }
}
```

**Exemplo de listener Spring AMQP** (a implementar):

```java
// Bind na fila ao routing key de registro do realm
@RabbitListener(bindings = @QueueBinding(
    value = @Queue("wallet.keycloak.register"),
    exchange = @Exchange(value = "amq.topic", type = "topic"),
    key = "KK.EVENT.REALM.orderbook-realm.REGISTER"
))
public void onUserRegistered(KeycloakEvent event) {
    // Criar carteira para o novo usuário com userId = event.getUserId()
    walletService.createWallet(event.getUserId());
}
```

### Eventos Publicados
- `WalletCreatedEvent` — carteira criada via onboarding Keycloak
- `FundsReservedEvent` — reserva de saldo bem-sucedida
- `FundsReservationFailedEvent` — saldo insuficiente para reserva
- `FundsSettledEvent` — liquidação pós-match executada com sucesso
- `FundsSettlementFailedEvent` — erro na liquidação (carteira não encontrada ou saldo insuficiente)

---

## 🧩 Domínio — Agregado `Wallet`

O `Wallet` é o **Aggregate Root** do contexto de carteira. Toda mutação de saldo deve ocorrer exclusivamente via seus métodos de comportamento — setters públicos de saldo foram removidos (US-005).

### Métodos de domínio

| Método | Descrição | Lança se violado |
|--------|-----------|-----------------|
| `reserveFunds(AssetType, amount)` | Move `amount` de `available` → `locked` | `InsufficientFundsException` |
| `applyBuySettlement(brl, vib)` | Liquida comprador: libera `brlLocked`, credita `vibAvailable` | `InsufficientFundsException` |
| `applySellSettlement(vib, brl)` | Liquida vendedor: libera `vibLocked`, credita `brlAvailable` | `InsufficientFundsException` |
| `adjustBalance(brlDelta, vibDelta)` | Ajuste administrativo via delta (crédito/débito) | `InsufficientFundsException` |

### Invariantes

```
- Nenhum saldo pode ser negativo
- Locked nunca pode exceder o available anterior à reserva
- Toda operação preserva consistência interna
- Wallet é aggregate root e controla seu próprio estado
- Optimistic locking via @Version (coluna: version — migration V4)
```
### Prevenção de Deadlock ABBA — `settleFunds` (AT-03.1)

O método `WalletService.settleFunds()` adquire locks pessimistas em **duas** carteiras por
transação. Sem ordenação, dois settlements concorrentes sobre as mesmas carteiras em ordens
opostas criam uma espera circular (**deadlock ABBA**).

**Solução — Lock Ordering Determinístico:** locks são sempre adquiridos em **ordem crescente
de UUID** (`UUID.compareTo`), garantindo sequência global única. A semântica buyer/seller é
restaurada por mapeamento após a aquisição dos locks, sem alterar contratos públicos.

```
  Thread 1: settleFunds(buyer=A, seller=B) → lock min(A,B) → lock max(A,B) → commit
  Thread 2: settleFunds(buyer=B, seller=A) → lock min(A,B) → espera → lock max(A,B) → commit
  ↑ Serialização correta — zero espera circular
```

#### Cobertura de testes (AT-03.1 / AT-03.2)

| Classe | Tipo | O que prova |
|--------|------|-------------|
| `WalletServiceLockOrderTest` | Unitário (Mockito) | Captura a ordem real de calls a `findByIdForUpdate` e asserta que menor UUID é sempre primeiro |
| `WalletConcurrentDeadlockTest.FaseRed` | Integração (PostgreSQL) | Usa `TransactionTemplate` sem lock ordering para **provocar** um deadlock real (SQLState 40P01) |
| `WalletConcurrentDeadlockTest.FaseGreen` | Integração (PostgreSQL) | 20 iterações × 2 threads com buyer/seller invertidos via `WalletService` — zero deadlocks |
| `WalletConcurrentDeadlockTest.AltaContencao` | Integração (PostgreSQL) | 10 carteiras × 50 settlements simultâneos (pool fixo) — zero deadlocks, conservação global de valor |
### Schema Flyway

| Migration | Descrição |
|-----------|-----------|
| `V1__create_wallet.sql` | Tabela `tb_wallet` com constraints CHECK de saldo ≥ 0 |
| `V2__create_outbox.sql` | Tabela `outbox_message` para Transactional Outbox |
| `V3__create_idempotency_key.sql` | Tabela `idempotency_key` para deduplicação de mensagens |
| `V4__add_wallet_version.sql` | Coluna `version BIGINT` para optimistic locking (US-005) |

## 📦 Dependências

| Biblioteca | Versão | Uso |
|-----------|--------|-----|
| Spring Boot Web | 3.2.3 | REST APIs |
| Spring Data JPA | 3.2.3 | Persistência |
| Spring Data MongoDB | 3.2.3 | Audit logs |
| PostgreSQL | 16 | Banco de dados |
| JUnit 5 | Por Spring | Testes |
| AssertJ | 3.x | Assertions |
| REST Assured | 5.x | Testes de API |

## 🔍 Debugging

```bash
# Debug remoto na porta 5006
# Conecte seu IDE (IntelliJ IDEA, VS Code, etc)
# Veja: docs/testing/COMPREHENSIVE_TESTING.md#debug-remoto

# Iniciar com variações de debug
docker compose -f infra/docker-compose.dev.yml up wallet-service
```

---

**Service ID**: wallet-service  
**Port**: 8081  
**Debug Port**: 5006
