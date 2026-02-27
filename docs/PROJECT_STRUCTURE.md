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
│   ├── docker-compose.staging.yml
│   ├── postgres/
│   │   └── init-postgres.sql
│   ├── mongo/
│   │   └── init-mongo.js
│   ├── kong/
│   │   └── kong-config.md
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

**O que colocar aqui:**
- Configurações genéricas do Spring Security (validação de JWT do Keycloak)
- Manipuladores de erro globais (`@ControllerAdvice`)
- Utilitários para logs (OpenTelemetry)
- Classes base para o padrão *Transactional Outbox*
- Exceções customizadas e respostas de erro padrão

### 3. 🚀 `apps/` — Os Microsserviços

Aqui dentro, cada microsserviço é uma aplicação Spring Boot independente. E é dentro deles que aplicamos o **CQRS** rigorosamente.

Veja como a estrutura fica por dentro do `apps/order-service/src/main/java/.../order/`:

```
apps/order-service/.../order/
├── command/                        # ➔ WRITE SIDE — Escrita e Regras (Onde o bicho pega!)
│   ├── aggregate/                  # O Guardião das regras (Ex: OrderAggregate)
│   └── handlers/                   # Quem executa a ação (Ex: OrderCommandHandler)
│
├── query/                          # ➔ READ SIDE — Leitura Rápida (Focado em velocidade)
│   ├── projections/                # Modelos prontos para a tela (Ex: OrderDocument do MongoDB)
│   ├── handlers/                   # Quem busca os dados (Ex: OrderQueryHandler)
│   └── repositories/               # Acesso ao banco de leitura (Ex: MongoRepository)
│
├── rest/                           # ➔ PORTA DE ENTRADA — APIs HTTP
│   ├── OrderCommandController.java # Recebe os POST/PUT e despacha Commands
│   └── OrderQueryController.java   # Recebe os GET e despacha Queries
│
└── config/                         # Configurações exclusivas deste serviço (ex: Beans do Redis)
```

**Como o CQRS funciona na prática:**

1. **Escrita (Command Side):** `OrderCommandController` → importa um **Command** de `libs/common-contracts` → despacha para `command/handlers/` → regras de negócio são aplicadas → evento é publicado no RabbitMQ
2. **Leitura (Query Side):** `OrderQueryController` → importa uma **Query** de `libs/common-contracts` → consulta `query/repositories/` → retorna dados super rápido do MongoDB ou Redis
3. **Integração:** Quando `wallet-service` quer reagir ao pedido, ele escuta o evento via RabbitMQ (não conhece a implementação interna do order-service, apenas o contrato!)

### 4. 🐳 `docker/` e ☁️ `infra/` — O Ambiente

- **`docker/`:** Arquivos de teste local. Execute `docker-compose -f docker/docker-compose.dev.yml up` para subir RabbitMQ, PostgreSQL, MongoDB, Redis e Keycloak.
- **`infra/`:** Configurações mais densas (arquivos JSON de importação de *Realm* do Keycloak, configurações do Kong, etc.)

---

## 🎯 Fluxo de Navegação

### 1️⃣ **Primeiro Setup**
```
README.md 
  ↓
docker/README.md
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
docker/README.md
  ↓
docker-compose -f docker/docker-compose.dev.yml up
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

## ✨ Destaques da Novo Organização

✅ **README na Raiz**: Sucinto (64 linhas, 5 minutos de leitura)  
✅ **Documentação Organizada**: docs/testing/ e docs/architecture/  
✅ **Docker Centralizado**: Todos os compose em docker/  
✅ **Scripts Organizados**: build.ps1 em scripts/ com README  
✅ **Cada Serviço Documentado**: order-service, wallet-service com READMEs
✅ **READMEs por Componente**: Cada serviço tem seu README  
✅ **Fácil de Navegar**: Estrutura lógica e intuitiva

---

**Última Atualização**: 27/02/2026
**Status**: ✅ Completo e Funcional
