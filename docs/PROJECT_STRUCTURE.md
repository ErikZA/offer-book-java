# 📁 Estrutura do Projeto - Visão Geral

> **Monorepo com Microsserviços, DDD e CQRS — A Linguagem Comum é Tudo!**

Para suportar nossa arquitetura de microsserviços orientada a eventos e manter o código organizado à medida que o projeto cresce, adotamos uma estrutura de **Monorepo**.

**A regra de ouro aqui é:** _Microsserviços (`apps`) não se comunicam diretamente pelo código, eles apenas compartilham contratos (`libs`) e se falam via eventos (RabbitMQ)._

---

## 🌳 A Árvore Raiz do Projeto

```
vibranium-orderbook/
│
├── 📄 README.md                    ⭐ LEIA PRIMEIRO (sucinto, 64 linhas)
├── 📄 pom.xml                      # POM raiz (multi-módulo Maven)
├── 📄 Makefile                     # Build tasks (Linux/macOS)
├── 📄 init.ps1                     # ⭐ Setup inicial (Windows)
├── 📄 .env.example                 # ⭐ Template de variáveis de ambiente (commit)
├── 📄 .env                         # 🔐 Credenciais locais (NÃO commit — gitignore)
│
├── 📁 docs/                        # 📚 Documentação Detalhada
│   ├── README.md                   # 📖 Índice de documentação (comece aqui!)
│   ├── SETUP_COMPLETE.md           # Resumo do que foi implementado
│   │
│   ├── 🧪 testing/                 # Tudo sobre testes e setup
│   │   ├── SETUP_MAVEN.md          # ⭐ Como instalar Java/Maven
│   │   ├── TESTING_GUIDE.md        # Guia completo de testes (300+ linhas)
│   │   ├── TEST_PATTERNS.md        # Padrões prontos para copiar/colar
│   │   └── FINAL_CHECKLIST.md      # Validação da reorganização
│   │
│   ├── 🏗️ architecture/            # Arquitetura & Design
│   │   ├── order-book-mvp.md       # Visão geral
│   │   ├── order-book-mvp-flow.md  # Fluxos de caso de uso
│   │   ├── order-book-mvp-sequence.md
│   │   ├── ddd-cqrs-event-source.md # Padrões (DDD/CQRS/Event Sourcing)
│   │   └── tools-stack.md          # Stack de ferramentas
│   │
│   └── 📋 PROJECT_STRUCTURE.md     # Este arquivo (você está aqui!)
│
│
├── 🐳 docker/                      # 🐳 Orquestração Local
│   ├── README.md                   # Como usar cada compose
│   ├── docker-compose.yml          # Production
│   ├── docker-compose.dev.yml      # Development (hotreload)
│   └── docker-compose.test.yml     # Testing (TDD)
│
├── 🔧 scripts/                     # 🛠️ Scripts Utilitários
│   ├── README.md                   # Como usar cada script
│   └── build.ps1                   # Build tasks (PowerShell/Windows)
│
├── 🚀 apps/                        # Microsserviços Executáveis (Spring Boot)
│   ├── order-service/              # O Motor do Livro de Ofertas
│   └── wallet-service/             # O Guardião de Saldos e Carteiras
│
├── 🧩 libs/                        # Bibliotecas Compartilhadas
│   ├── common-contracts/           # 💬 A Linguagem Comum: Eventos, Comandos, DTOs
│   └── common-utils/               # 🔨 Caixa de Ferramentas: Exceções, Segurança, Configs
│
├── ☁️ infra/                       # Infraestrutura como Código
│   ├── README.md                   # Como usar cada serviço
│   ├── docker-compose.yml          # Infra geral (Kong, Keycloak, Postgres, jwks-rotator)
│   ├── docker-compose.dev.yml      # Desenvolvimento local (microsserviços + hotreload)
│   ├── docker-compose.staging.yml  # Staging com réplicas
│   ├── docker-compose.redis-cluster.yml  # ⭐ AT-15: Redis Cluster 6 nodes HA
│   ├── docker/
│   │   ├── Dockerfile              # Imagem do test-runner Maven
│   │   ├── Dockerfile.keycloak     # Keycloak com plugin RabbitMQ
│   │   ├── Dockerfile.kong-init    # Init one-shot (kong-setup.sh)
│   │   └── Dockerfile.jwks-rotator # ⭐ Sidecar rotação JWKS (AT-13.1)
│   ├── postgres/
│   │   └── init-postgres.sql
│   ├── kong/
│   │   ├── kong-config.md
│   │   ├── kong-setup.sh           # Provisionamento inicial (one-shot)
│   │   ├── jwks-rotation.sh        # ⭐ Script idempotente de rotação JWKS (AT-13.1)
│   │   └── jwks-rotator-entrypoint.sh  # ⭐ Loop 6h do sidecar rotator (AT-13.1)
│   ├── redis/                        # ⭐ AT-15 — Redis Cluster HA
│   │   └── redis-cluster.conf       # Config base: cluster-enabled, AOF, timeout 5000ms
│   ├── prometheus/                  # ⭐ AT-12 — Configuração Prometheus
│   │   └── prometheus.yml           # Scrape config: order-service + wallet-service (15s)
│   ├── grafana/                     # ⭐ AT-12 — Dashboards e Provisioning Grafana
│   │   ├── provisioning/
│   │   │   ├── datasources/
│   │   │   │   └── prometheus.yml   # Datasource Prometheus (auto-default)
│   │   │   ├── dashboards/
│   │   │   │   └── dashboard.yml    # Provider de dashboards via filesystem
│   │   │   └── alerting/
│   │   │       └── alerting.yml     # 3 alertas: outbox depth, error rate, circuit breaker
│   │   └── dashboards/
│   │       ├── order-flow.json      # Orders/s, matches/s, cancels/s, outbox depth
│   │       ├── wallet-health.json   # Reserves/s, settles/s, releases/s, errors
│   │       ├── infrastructure.json  # Redis, PG, JVM heap, circuit breaker state
│   │       └── sla.json             # Latência p50/p95/p99, error rate, saga duration
│   └── keycloak/
│       └── keycloak-setup.sh
│
└── 🔐 .github/
    ├── copilot-instructions.md
    ├── skills/
    │   ├── create-spring-boot-java-project/
    │   ├── java-docs/
    │   └── java-springboot/
    └── workflows/
        └── (CI/CD when configured)
```

---

## 🔍 Entendendo os Módulos Principais

Se você precisa atuar no projeto, este guia explica onde cada pedaço de código deve morar.

### 1. 💬 `libs/common-contracts` — A Linguagem Comum

Esta é a biblioteca mais importante do monorepo. É a **única** coisa que o `order-service` e o `wallet-service` conhecem em comum.

**O que colocar aqui:**
- Classes simples (Records ou POJOs imutáveis) que representam uma ordem para fazer algo (**Commands**)
- Um aviso de que algo aconteceu (**Events**, como `OrderCreatedEvent`, `WalletCreditedEvent`)
- Um pedido de dados (**Queries**)

**Regra de Ouro:** ⚠️ Não coloque regras de negócio, validações complexas ou dependências de banco de dados aqui. Apenas **contratos limpos**.

**Resultado esperado:**
```
libs/common-contracts/src/main/java/com/vibranium/contracts/
├── events/
│   ├── OrderCreatedEvent.java
│   ├── OrderMatchedEvent.java
│   ├── WalletCreditedEvent.java
│   └── WalletDebitedEvent.java
├── commands/
│   └── (quando necessário)
└── queries/
    └── (quando necessário)
```

### 2. 🔨 `libs/common-utils` — A Caixa de Ferramentas

Tudo que não é regra de negócio, mas você não quer copiar e colar em todo microsserviço.

**O que está implementado hoje:**

```
libs/common-utils/src/main/java/com/vibranium/utils/
├── jackson/
│   └── VibraniumJacksonConfig.java     # Configuração central do ObjectMapper (ISO-8601)
├── correlation/
│   └── CorrelationIdGenerator.java     # Geração de UUID v4 para rastreabilidade
├── messaging/
│   └── AmqpHeaderExtractor.java        # Extração de correlation-ID de headers AMQP
├── outbox/
│   ├── AbstractOutboxPublisher.java    # Template Method base do Transactional Outbox (AT-10)
│   └── OutboxConfigProperties.java     # Record (batchSize, pollingIntervalMs) com validação
└── secret/
    ├── SecretFileReader.java           # Leitura de Docker Secrets com fallback para env vars (AT-13)
    ├── SecretReadException.java        # RuntimeException para falhas de leitura de secrets
    └── DockerSecretEnvironmentPostProcessor.java  # Auto-injeta secrets no Spring Environment (AT-13)
```

**Utilitários disponíveis:**

| Classe | Método-chave | Finalidade |
|--------|-------------|------------|
| `VibraniumJacksonConfig` | `configure(ObjectMapper)` | Aplica ISO-8601, desabilita `FAIL_ON_UNKNOWN_PROPERTIES` |
| `CorrelationIdGenerator` | `generate()` / `generateAsString()` | UUID v4 para correlation de requests |
| `AmqpHeaderExtractor` | `extractCorrelationId(MessageProperties)` | Prioridade: `message-id` → `X-Correlation-ID` |
| `AbstractOutboxPublisher<T>` | `pollAndPublish()` | Template Method: polling → dispatch → publish → recover |
| `OutboxConfigProperties` | `batchSize()` / `pollingIntervalMs()` | Configuração base do Outbox (imutável, com validação) |
| `SecretFileReader` | `readSecretFile(Path)` / `readSecretWithFallback(...)` | Leitura de Docker Secrets com fallback para env vars |
| `DockerSecretEnvironmentPostProcessor` | `postProcessEnvironment(...)` | Injeta Docker Secrets como Spring properties antes do contexto |

> **⚠️ BREAKING CHANGE (US-007):** O `order-service` era configurado com `WRITE_DATES_AS_TIMESTAMPS=true` (epoch-millis). Após a unificação via `VibraniumJacksonConfig`, ambos os serviços serializam datas como **ISO-8601**. Campos que precisam de epoch-millis devem usar `@JsonFormat(shape = NUMBER_INT)` individualmente.

**O que colocar aqui (futuro):**
- Configurações genéricas do Spring Security (validação de JWT do Keycloak)
- Manipuladores de erro globais (`@ControllerAdvice`)
- Utilitários para logs (OpenTelemetry)
- ~~Classes base para o padrão *Transactional Outbox*~~ ✅ Implementado (AT-10): `AbstractOutboxPublisher<T>` + `OutboxConfigProperties`
- ~~Utilitários para gestão de secrets~~ ✅ Implementado (AT-13): `SecretFileReader` + `DockerSecretEnvironmentPostProcessor`
- Exceções customizadas e respostas de erro padrão

### 3. 🚀 `apps/` — Os Microsserviços

Cada microsserviço é uma aplicação Spring Boot independente que aplica **Arquitetura Hexagonal + DDD + CQRS**. Ambos os serviços compartilham exatamente a mesma organização de pacotes.

```
{orderservice | walletservice}/
├── domain/
│   ├── model/              # Aggregates e Entities do domínio (Order, Wallet, etc.)
│   └── repository/         # Interfaces de repositório — domain ports (sem deps de infra)
│
├── application/
│   ├── service/            # Application services / use cases (lógica de negócio)
│   ├── dto/                # Request e Response DTOs (PlaceOrderRequest, WalletResponse, etc.)
│   └── query/              # ➔ CQRS — READ SIDE (apenas order-service; wallet se aplicável)
│       ├── model/          # Projeções MongoDB (ex: OrderDocument)
│       ├── repository/     # Repositórios de leitura (MongoRepository)
│       └── consumer/       # Projeção de eventos → Read Model
│           service/        # Escritores atômicos (ex: OrderAtomicHistoryWriter)
│                           # ⭐ ProjectionRebuildService — rebuild MongoDB a partir do PG (AT-08)
│
├── infrastructure/         # Adapters técnicos — driven side (detalhes de implementação)
│   ├── messaging/          # RabbitMQ listeners/consumers/publishers
│   ├── outbox/             # Transactional Outbox publisher (wallet-service)
│   └── redis/              # Redis adapters — match engine (order-service)
│
├── eventstore/             # ⭐ Event Store imutável — auditoria e replay (AT-14)
│   ├── model/              # EventStoreEntry (append-only, TRIGGER protegido)
│   ├── repository/         # EventStoreRepository (JPA — replay por aggregate + temporal)
│   └── service/            # EventStoreService (append na mesma TX do Outbox)
│
├── web/                    # Adapter HTTP — driving side
│   ├── controller/         # REST controllers (OrderCommandController, WalletController, etc.)
│   └── exception/          # GlobalExceptionHandler + exceções de domínio customizadas
│
├── security/               # SecurityConfig (produção; E2eSecurityConfig movido para src/test)
├── config/                 # Demais @Configuration (RabbitMQ, Jackson, Mongo, Time, Outbox)
└── e2e/                    # (vazio em src/main; E2eDataSeederController está em src/test)
```

**Fluxo CQRS na prática:**

1. **Escrita (Command Side):** `web/controller/` recebe HTTP → delega para `application/service/` → regras aplicadas no `domain/model/` → evento publicado via `infrastructure/messaging/` + Outbox
2. **Leitura (Query Side):** `web/controller/` recebe HTTP → busca direto em `application/query/repository/` (MongoDB/Redis) → resposta rápida sem touching no Command Side
3. **Projeção de eventos:** `infrastructure/messaging/` consome evento → `application/query/consumer/` atualiza o Read Model via `application/query/service/`
4. **Integração entre serviços:** exclusivamente via RabbitMQ usando contratos de `libs/common-contracts` — nenhum serviço importa código interno do outro

### 4. 🐳 `docker/` e ☁️ `infra/` — O Ambiente

- **`infra/`:** Docker Compose e configs de infraestrutura centralizada. Requer o arquivo `.env` na raiz (copie `.env.example`). Execute `docker compose -f infra/docker-compose.dev.yml up -d` para subir RabbitMQ, PostgreSQL, MongoDB, Redis, Keycloak, Kong, Jaeger, **Prometheus**, **Grafana** e os dois microsserviços.
- **`infra/prometheus/`:** Configuração de scrape do Prometheus (targets e intervalo).
- **`infra/grafana/`:** Dashboards JSON, provisioning de datasources e alertas do Grafana.
- **`infra/secrets/`:** Templates (`.txt.example`) para Docker Secrets. Secrets reais (`.txt`) são ignorados pelo `.gitignore`. Ver [SECRETS_MANAGEMENT.md](./SECRETS_MANAGEMENT.md).
- **`tests/`:** Docker Compose isolado para testes de integração. Execute `docker compose -f tests/docker-compose.test.yml up` para rodar a suite completa.

---

## 🎯 Fluxo de Navegação

### 1️⃣ **Primeiro Setup**
```
README.md 
  ↓
infra/README.md
  ↓
.\init.ps1
  ↓
.\build.ps1 docker-test
```

### 2️⃣ **Desenvolvimento**
```
README.md
  ↓
apps/order-service/README.md (ou wallet-service/)
  ↓
infra/README.md
  ↓
docker compose -f infra/docker-compose.dev.yml up
```

### 3️⃣ **Entender Testes**
```
docs/README.md
  ↓
docs/testing/TESTING_GUIDE.md
  ↓
docs/testing/TEST_PATTERNS.md
```

### 4️⃣ **Entender Arquitetura**
```
docs/README.md
  ↓
docs/architecture/order-book-mvp.md
  ↓
docs/architecture/ddd-cqrs-event-source.md
```

### 5️⃣ **Scripts & Automação**
```
scripts/README.md
  ↓
.\scripts\build.ps1 (Windows)
  ↓
make ... (Linux/macOS)
```

## 📊 Arquivos por Categoria

### 🎯 Pontos de Entrada
| Arquivo | Uso | Onde |
|---------|-----|------|
| `README.md` | Visão geral rápida | **Raiz** |
| `init.ps1` | Setup ambiente | **Raiz** |
| `Makefile` | Build tasks | **Raiz** |
| `pom.xml` | Maven multi-módulo | **Raiz** |
| `.env.example` | Template de credenciais | **Raiz** |

### 📚 Documentação
| Arquivo | Propósito | Onde |
|---------|----------|------|
| `README.md` | Índice e navegação | **docs/** |
| `SETUP_MAVEN.md` | Instalar Java/Maven | **docs/testing/** |
| `TESTING_GUIDE.md` | Guia de testes | **docs/testing/** |
| `TEST_PATTERNS.md` | Padrões prontos | **docs/testing/** |
| `order-book-mvp.md` | Visão geral | **docs/architecture/** |
| `ddd-cqrs-event-source.md` | Padrões de design | **docs/architecture/** |

### 🐳 Containers
| Arquivo | Ambiente | Onde |
|---------|----------|------|
| `docker-compose.yml` | Production | **docker/** |
| `docker-compose.dev.yml` | Development | **docker/** |
| `docker-compose.test.yml` | Testing | **docker/** |

### 🔧 Scripts
| Arquivo | Plataforma | Onde |
|---------|-----------|------|
| `build.ps1` | Windows | **scripts/** |
| `Makefile` | Linux/macOS | **Raiz** |

### 📦 Código
| Arquivo | Tipo | Onde |
|---------|------|------|
| `OrderService*` | Service | **apps/order-service/** |
| `WalletService*` | Service | **apps/wallet-service/** |
| `common-contracts` | Library | **libs/** |
| `VibraniumJacksonConfig` | Utility | **libs/common-utils/** |
| `CorrelationIdGenerator` | Utility | **libs/common-utils/** |
| `AmqpHeaderExtractor` | Utility | **libs/common-utils/** |
| `AbstractOutboxPublisher` | Base Class | **libs/common-utils/** |
| `OutboxConfigProperties` | Config Record | **libs/common-utils/** |
| `SecretFileReader` | Utility | **libs/common-utils/** |
| `DockerSecretEnvironmentPostProcessor` | EnvironmentPostProcessor | **libs/common-utils/** |

## ✨ Destaques da Novo Organização

✅ **README na Raiz**: Sucinto (64 linhas, 5 minutos de leitura)  
✅ **Documentação Organizada**: docs/testing/ e docs/architecture/  
✅ **Docker Centralizado**: Todos os compose em docker/  
✅ **Scripts Organizados**: build.ps1 em scripts/ com README  
✅ **Cada Serviço Documentado**: order-service, wallet-service com READMEs
✅ **READMEs por Componente**: Cada serviço tem seu README  
✅ **Fácil de Navegar**: Estrutura lógica e intuitiva

---

**Última Atualização**: 07/03/2026 (Atividade 13 — Migração de credenciais para Docker Secrets; SecretFileReader + DockerSecretEnvironmentPostProcessor em common-utils; infra/secrets/ com templates)
**Status**: ✅ Completo e Funcional
