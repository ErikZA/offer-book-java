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
- ✅ Validar autenticação JWT via OAuth2 Resource Server (AT-10.1)
- ✅ Verificar propriedade de recurso (resource ownership) — `jwt.sub == wallet.userId` (AT-10.2)
- ✅ Cobrir regressões silenciosas do `SecurityFilterChain` com testes dedicados (AT-10.3)
- ✅ Propagação W3C TraceContext em mensagens AMQP e enriquecimento de spans com `saga.correlation_id` e `order.id` (AT-14.1)
- ✅ Exportação de spans via OTLP HTTP para Jaeger (`management.otlp.tracing.endpoint`) (AT-14.1)

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
│   │   ├── config/                                       # RabbitMQ, Outbox, Jackson, SecurityConfig
│   │   └── exception/                                    # InsufficientFundsException, etc.
│   └── resources/
│       ├── application.yaml
│       └── db/migration/                                 # V1…V5 (Flyway)
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
    │       ├── OutboxPublisherIntegrationTest.java
│       ├── ReserveFundsDlqIntegrationTest.java       # AT-07.1 — DLQ routing para reserve-funds
│       ├── KeycloakDlqIntegrationTest.java           # AT-2.2.2 — DLQ routing para wallet.keycloak.events
│   └── unit/
│       ├── WalletOutboxCleanupJobTest.java            # AT-2.3.1 — TC-OCJ-1/2: Clock.fixed valida janela retenção outbox
│       └── WalletIdempotencyCleanupJobTest.java       # AT-2.3.1 — TC-IKJ-1/2: Clock.fixed valida janela retenção idempotência
│       ├── DebeziumJdbcOffsetMigrationTest.java      # AT-08.1 RED — tabela wallet_outbox_offset
│       └── DebeziumRestartIdempotencyTest.java       # AT-08.1 RED→GREEN — idempotência pós-restart
│   └── security/
│       ├── SecurityUnauthorizedTest.java             # AT-10.1 — 401 sem token, 200 com token
│       ├── WalletOwnershipTest.java                  # AT-10.2 — 403 acesso cruzado, 200 owner/admin
│       └── WalletSecurityIntegrationTest.java        # AT-10.3 — 4 cenários: sem token, expirado, outro usuário, owner
│   └── integration/
│       └── WalletTracingPropagationIntegrationTest.java  # AT-14.1 RED→GREEN — W3C traceparent em AMQP

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

### Topologia RabbitMQ

```
Exchange: keycloak.events (topic)
  └─ KK.EVENT.CLIENT.# → Queue: wallet.keycloak.events
       └─ x-dead-letter-exchange: vibranium.dlq
       └─ x-dead-letter-routing-key: wallet.keycloak.events.dlq

Exchange: wallet.commands (topic)
  ├─ wallet.command.reserve-funds → Queue: wallet.commands.reserve-funds
  │    └─ x-dead-letter-exchange: vibranium.dlq
  │    └─ x-dead-letter-routing-key: wallet.commands.reserve-funds.dlq
  ├─ wallet.command.release-funds → Queue: wallet.commands.release-funds
  │    └─ x-dead-letter-exchange: vibranium.dlq
  │    └─ x-dead-letter-routing-key: wallet.commands.release-funds.dlq
  └─ wallet.command.settle-funds  → Queue: wallet.commands

Exchange: vibranium.dlq (direct) — Dead Letter Exchange
  ├─ wallet.keycloak.events.dlq        → Queue: wallet.keycloak.events.dlq
  ├─ wallet.commands.reserve-funds.dlq → Queue: wallet.commands.reserve-funds.dlq
  └─ wallet.commands.release-funds.dlq → Queue: wallet.commands.release-funds.dlq
```

> **AT-2.3.1 — Cleanup Jobs:** `OutboxCleanupJob` (domingos 03:00 UTC) e `IdempotencyKeyCleanupJob` (domingos 04:00 UTC) removem
> registros expirados das tabelas `outbox_message` (processed=true) e `idempotency_key` via DELETE em lote com janela de retenção de 7 dias.
> `Clock` injetável via `TimeConfig` garante testes determinísticos sem `Thread.sleep`.

> **AT-2.2.2 — DLQ Keycloak:** Mensagens de registro Keycloak NACKed com `requeue=false`
> (ausência de `messageId` ou falha inesperada em `createWallet()`) são roteadas para
> `wallet.keycloak.events.dlq` via `vibranium.dlq`. Nenhum evento de criação de wallet é perdido silenciosamente.

> **AT-07.1 — DLQ Reserve/Release:** Mensagens de `ReserveFundsCommand` e `ReleaseFundsCommand`
> NACKed com `requeue=false` (falha permanente: erro de deserialização, estado inválido, NPE)
> são automaticamente roteadas para as respectivas DLQs via `vibranium.dlq`.
> Nenhum comando financeiro é perdido silenciosamente.

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

### Resiliência de Mensagens — Dead Letter Queue (AT-07.1)

| Classe | Tipo | O que prova |
|--------|------|-------------|
| `ReserveFundsDlqIntegrationTest` (Teste 1) | Integração (RabbitMQ) | Consulta Management API e valida que `wallet.commands.reserve-funds` possui `x-dead-letter-exchange=vibranium.dlq` e `x-dead-letter-routing-key` configurados |
| `ReserveFundsDlqIntegrationTest` (Teste 2) | Integração (RabbitMQ) | Simula poison pill via `@MockBean`, verifica que mensagem NACKed não fica na fila principal e aparece em `wallet.commands.reserve-funds.dlq` com header `x-death` |

### Persistência de Offset Debezium — JdbcOffsetBackingStore (AT-08.1)

O `FileOffsetBackingStore` armazenava o offset do WAL em `/tmp/wallet-outbox-offset.dat`, um caminho efêmero em containers Docker. Após restart, o Debezium perdia o LSN confirmado e podia reprocessar eventos já publicados no RabbitMQ (duplicatas).

**Solução — `JdbcOffsetBackingStore`:** o offset é persistido na tabela `wallet_outbox_offset` do próprio PostgreSQL (migration V5). O banco sobrevive ao restart do container, garantindo continuidade exata do WAL.

Benefícios:
- Offset sobrevive ao restart do container
- Nenhuma duplicata por perda de offset
- `FileSchemaHistory` em `/tmp` também eliminado — schema reconstruído do WAL no startup
- Alinha o sistema com exactly-once semantics (combinado com `claimAndPublish` atômico)

| Classe | Tipo | O que prova |
|--------|------|-------------|
| `DebeziumJdbcOffsetMigrationTest` | Integração (RED→GREEN) | Valida existência, estrutura e PK da tabela `wallet_outbox_offset` após migration V5 |
| `DebeziumRestartIdempotencyTest` | Integração (RED→GREEN) | Confirma que offset persiste no banco e que restart controlado não republica eventos já processados |

---

## ⚠️ Debezium Embedded Limitation

> **O `wallet-service` com Debezium Embedded suporta apenas deployment single-instance.**
> Escalonar horizontalmente este serviço sem remover o `DebeziumOutboxEngine` causa
> falha imediata ou WAL bloat no PostgreSQL.

### Por que a limitação existe

O Debezium Embedded cria **um replication slot por instância** no PostgreSQL e não
possui coordenação distribuída. Com múltiplas instâncias:

| Cenário                          | Resultado                                                                 |
|----------------------------------|---------------------------------------------------------------------------|
| Mesmo slot (`APP_OUTBOX_SLOT_NAME` igual) | PostgreSQL rejeita a 2ª conexão: `slot already active` (SQLState 55006) |
| Slots distintos por instância    | Todas as instâncias recebem os mesmos eventos (fan-out). **WAL bloat:** o banco retém WAL até que *todos* os slots confirmem o LSN. Uma instância lenta pode esgotar o disco. |

### Configuração atual

```yaml
# application.yaml
app.outbox.debezium.slot-name: ${APP_OUTBOX_SLOT_NAME:wallet_outbox_slot}
```

O nome é **fixo por padrão**. Nenhum mecanismo gera automaticamente um sufixo único
por pod/hostname.

### Caminhos evolutivos para alta disponibilidade

| Fase | Estratégia | Trade-off |
|------|-----------|-----------|
| **Imediato** | Single-instance (status quo) | Sem escala horizontal do publisher |
| **Curto prazo** | Desabilitar Debezium; usar Polling Scheduler com `SELECT FOR UPDATE SKIP LOCKED` | Latência maior (~polling interval); sem WAL bloat |
| **Médio prazo** | Migrar para Debezium Server dedicado + Kafka | Kafka como dependência; CDC desacoplado da JVM da app |

### Monitoramento de WAL bloat

```sql
-- Verificar lag de cada replication slot (bytes retidos no WAL)
SELECT slot_name, active,
       pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn) AS lag_bytes
FROM pg_replication_slots;
```

> Slot com `active = false` e `lag_bytes` crescente indica instância travada.
> Ação: `SELECT pg_drop_replication_slot('wallet_outbox_slot');` (IRREVERSÍVEL — Debezium
> reiniciará do LSN corrente).

### Documentação arquitetural

Decisão versionada em: [`docs/architecture/adr-001-debezium-single-instance.md`](../../docs/architecture/adr-001-debezium-single-instance.md)

Teste de garantia: [`DebeziumSingleInstanceConstraintTest`](src/test/java/com/vibranium/walletservice/architecture/DebeziumSingleInstanceConstraintTest.java) (AT-08.2)

---

### Schema Flyway

| Migration | Descrição |
|-----------|-----------|
| `V1__create_wallet.sql` | Tabela `tb_wallet` com constraints CHECK de saldo ≥ 0 |
| `V2__create_outbox.sql` | Tabela `outbox_message` para Transactional Outbox |
| `V3__create_idempotency_key.sql` | Tabela `idempotency_key` para deduplicação de mensagens |
| `V4__add_wallet_version.sql` | Coluna `version BIGINT` para optimistic locking (US-005) || `V5__create_wallet_outbox_offset.sql` | Tabela `wallet_outbox_offset` para `JdbcOffsetBackingStore` (AT-08.1) |
## � Segurança — OAuth2 Resource Server (AT-10.1)

O `wallet-service` protege todos os endpoints REST com validação de JWT emitido pelo Keycloak.
A autenticação é **stateless** (sem sessão HTTP) e **independente do Kong** (defesa em profundidade).

### Fluxo de autenticação

```
Cliente → [Bearer Token] → BearerTokenAuthenticationFilter
                                     ↓
                             JwtDecoder (JWKS via Keycloak)
                                     ↓
                          SecurityContext populado → Controller
```

### Endpoints públicos

| Endpoint | Autenticação |
|----------|-------------|
| `GET /actuator/health` | Pública (sem token) |
| `GET /actuator/info` | Pública (sem token) |
| Qualquer outro | JWT obrigatório (HTTP 401 se ausente/inválido) |

### Variáveis de ambiente

| Variável | Padrão | Descrição |
|----------|--------|-----------|
| `KEYCLOAK_ISSUER_URI` | `http://keycloak:8080/realms/orderbook-realm` | URL do issuer Keycloak (OIDC Discovery) |

### Estratégia de testes

- `AbstractIntegrationTest` — `@WithMockUser` garante que testes existentes não quebrem após ativação do `SecurityConfig`
- `SecurityUnauthorizedTest` — valida 401 para qualquer endpoint sem token (`@WithAnonymousUser`) e 200 com token
- `WalletSecurityIntegrationTest` — 4 cenários de segurança focados no `SecurityFilterChain` (AT-10.3):
  - Sem token → 401 (`@WithAnonymousUser`)
  - Token expirado → 401 (`@MockBean JwtDecoder` + `JwtValidationException`)
  - Token de outro usuário → 403 (`jwt()` post-processor com `sub` diferente do owner)
  - Token do owner → 200 (acesso legítimo sem regressão)
- Perfil `test` usa `jwk-set-uri` (lazy) em vez de `issuer-uri` para evitar OIDC Discovery no startup dos testes

---

## 📦 Dependências

| Biblioteca | Versão | Uso |
|-----------|--------|-----|
| Spring Boot Web | 3.2.3 | REST APIs |
| Spring Data JPA | 3.2.3 | Persistência |
| Spring Data MongoDB | 3.2.3 | Audit logs |
| PostgreSQL | 16 | Banco de dados |
| Spring Security | Por Spring Boot | Filtro de autenticação JWT (AT-10.1) |
| Spring OAuth2 Resource Server | Por Spring Boot | Decoder JWT via JWKS do Keycloak (AT-10.1) |
| JUnit 5 | Por Spring | Testes |
| AssertJ | 3.x | Assertions |
| REST Assured | 5.x | Testes de API |
| Spring Security Test | Por Spring | `@WithMockUser`, `@WithAnonymousUser`, `.jwt()` PostProcessor (AT-10.1, AT-10.2) |

### Autorização de Recurso — Resource Ownership (AT-10.2)

Além da autenticação (valida *quem* acessa), o `WalletController` aplica **autorização horizontal**:
valida *qual recurso* o usuário autenticado pode acessar, prevenindo IDOR (Insecure Direct Object Reference).

#### Regra de ownership

```
jwt.sub == wallet.userId  →  200 OK
jwt.sub != wallet.userId  e  sem ROLE_ADMIN  →  403 Forbidden
jwt.sub != wallet.userId  e  com ROLE_ADMIN  →  200 OK
```

#### Endpoints protegidos

| Endpoint | Verificação |  Claim JWT consultado |
|----------|-------------|----------------------|
| `GET /api/v1/wallets/{userId}` | `userId` (path) == `jwt.sub` | `sub` |
| `PATCH /api/v1/wallets/{walletId}/balance` | `wallet.userId` (banco) == `jwt.sub` | `sub`, `roles` |
| `GET /api/v1/wallets` | Nenhuma (admin/debug) | — |

> **Por que PATCH precisa buscar o banco antes da verificação?**
> O path `/{walletId}` identifica a carteira (PK), não o usuário. É necessário
> buscar `wallet.userId` para comparar com `jwt.sub`. A busca usa `findById` (read-only,
> sem lock pessimista) — separada da mutação `adjustBalance` (que usa `findByIdForUpdate`).

#### Estratégia de testes — `WalletOwnershipTest`

Usa `SecurityMockMvcRequestPostProcessors.jwt()` para injetar `JwtAuthenticationToken` real
no `SecurityContext`, permitindo que o controller receba `@AuthenticationPrincipal Jwt jwt`
corretamente populado (diferente de `@WithMockUser`, que injeta `UsernamePasswordAuthenticationToken`).

| Teste | Cenário | Resultado esperado |
|-------|---------|-------------------|
| `adjustBalance_jwtDeOutroUsuario_deveRetornar403` | User A acessa wallet de B via PATCH | **403** |
| `getByUserId_jwtDeOutroUsuario_deveRetornar403` | User A consulta wallet de B via GET | **403** |
| `adjustBalance_jwtDoOwner_deveRetornar200` | Owner acessa sua própria wallet via PATCH | **200** |
| `getByUserId_jwtDoOwner_deveRetornar200` | Owner consulta sua própria wallet via GET | **200** |
| `adjustBalance_jwtAdmin_deveRetornar200` | Admin acessa wallet de qualquer usuário via PATCH | **200** |
| `getByUserId_jwtAdmin_deveRetornar200` | Admin consulta wallet de qualquer usuário via GET | **200** |

---

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
