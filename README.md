# Vibranium Order Book Platform

Plataforma de trading com microsserviços em Spring Boot com **ambiente TDD completo** e best practices de testes.

**❗ IMPORTANTE**: Todos os trabalhos (build, testes, desenvolvimento) devem ser executados via **Docker**. Não instale Java ou Maven na sua máquina.

## 🚀 Quick Start

### Setup Inicial (Primeiro uso)
```powershell
# 1. Valide Docker
.\init.ps1

# 2. Inicie ambiente de desenvolvimento
.\build.ps1 docker-dev-up

# Ou no Linux/Mac
make docker-dev-up
```

### Comandos Principais
```bash
# Windows PowerShell
.\build.ps1 docker-dev-up       # Iniciar dev (hotreload)
.\build.ps1 docker-test         # Executar testes
.\build.ps1 docker-prod-up      # Iniciar produção
.\build.ps1 docker-dev-logs -Service order-service

# Linux / macOS
make docker-dev-up
make docker-test
make docker-prod-up
make docker-dev-logs SERVICE=order-service

# Docker Compose direto
docker compose -f infra/docker-compose.dev.yml up
docker compose -f tests/docker-compose.test.yml up
docker compose -f infra/docker-compose.yml up
```

## 📁 Estrutura

```
.
├── apps/                    # Microsserviços
│   ├── order-service/       # Trading orders
│   └── wallet-service/      # Wallet & transactions
├── libs/                    # Bibliotecas compartilhadas
│   └── common-contracts/    # Eventos e DTOs
├── infra/                   # Docker Compose + configs de infra (dev, staging, prod)
├── tests/                   # Docker Compose de testes de integração
├── scripts/                 # Utilitários
├── docs/                    # Documentação detalhada
├── init.ps1                 # Validação Docker (Windows)
├── Makefile                 # Tasks Docker (Linux/Mac)
└── pom.xml                  # POM raiz (Maven - uso interno)
```

## 📚 Documentação

- **[docs/testing/COMPREHENSIVE_TESTING.md](docs/testing/COMPREHENSIVE_TESTING.md)** - Guia completo com padrões de teste
- **[infra/README.md](infra/README.md)** - Como usar cada ambiente Docker
- **[tests/README.md](tests/README.md)** - Ambientes de testes isolados
- **[docs/architecture/order-book-mvp.md](docs/architecture/order-book-mvp.md)** - Arquitetura
- **[docs/README.md](docs/README.md)** - Índice completo de documentação
- **[scripts/README.md](scripts/README.md)** - Scripts disponíveis

## 📦 Stack

| Componente | Versão | Local |
|-----------|--------|-------|
| Java | 21 | Container |
| Spring Boot | 3.2.3 | Container |
| Maven | 3.9+ | Container |
| JUnit 5 | Por Spring Boot | Container |
| AssertJ | 3.x | Container |
| REST Assured | 5.x | Container |
| Docker | Compose | Máquina Host |

### Infraestrutura de Segurança e Mensageria

| Componente | Versão | Função |
|-----------|--------|---------|
| Keycloak | 22.0.5 | Autenticação e autorização OAuth2/OIDC |
| PostgreSQL | 16 | Banco compartilhado (Kong + Keycloak) |
| Kong | 3.4 | API Gateway |
| RabbitMQ | 3.13 | Message broker AMQP |
| keycloak-event-listener-rabbitmq | 3.0.5 | Plugin de eventos de auth |

## 🔐 Autenticação e Plugin de Eventos

### O que foi criado

Foi adicionada uma **camada de segurança completa** baseada em Keycloak 22, com importação automática de realm ao subir o ambiente. Todo o estado inicial é declarativo via [`docker/realm-export.json`](docker/realm-export.json):

- **Realm** `orderbook-realm` com roles `USER` e `ADMIN`
- **Cliente** `order-client` (public, direct access grant habilitado)
- **Usuário de teste** `tester@vibranium.com` com role `USER` e senha `test-password`

A imagem do Keycloak é **customizada** (`infra/docker/Dockerfile.keycloak`) — ela parte da imagem oficial e instala o plugin **`keycloak-to-rabbit-3.0.5.jar`** em `/opt/keycloak/providers/`, executando `kc.sh build` para registrar o provider via Quarkus Service Loader.

### Como funciona

```
Usuário faz login/logout
        ↓
    Keycloak 22
        ↓  plugin keycloak-event-listener-rabbitmq
    RabbitMQ (exchange: amq.topic)
        ↓  routing key: KK.EVENT.REALM.<realm>.<event_type>
    Microsserviços (consumers)
        ↓
  wallet-service, order-service (a implementar)
```

O plugin publica no exchange `amq.topic` usando routing keys no formato `KK.EVENT.REALM.orderbook-realm.LOGIN`, `KK.EVENT.REALM.orderbook-realm.REGISTER`, etc. Isso permite que qualquer microsserviço **reaja de forma reativa** a eventos de autenticação sem acoplamento direto ao Keycloak.

### Por que o plugin foi adicion ado

Em plataformas de trading, certos processos de negócio precisam ser disparados imediatamente no momento em que um usuário se autentica:

- **Criação automática de carteira** no `wallet-service` quando um novo usuário registra (`REGISTER` event)
- **Auditoria de sessões** — rastrear logins/logouts para conformidade regulatória
- **Invalidação de cache** — limpar caches locais de sessão no `order-service` em logout
- **Notificações** — alertar sobre logins suspeitos ou de novos dispositivos

Sem o plugin, os serviços teriam que fazer polling na API do Keycloak (ineficiente) ou implementar webhooks customizados (frágil). Com o plugin, o Keycloak publica os eventos no broker que já é a espinha dorsal da plataforma, mantendo consistência arquitetural.

### Arquivos criados/modificados

| Arquivo | Descrição |
|---|---|
| [`infra/docker/Dockerfile.keycloak`](infra/docker/Dockerfile.keycloak) | Imagem customizada com plugin JAR e `kc.sh build` |
| [`infra/keycloak/realm-export.json`](infra/keycloak/realm-export.json) | Realm declarativo importado via `--import-realm` |
| [`infra/keycloak/realm-export.json`](infra/keycloak/realm-export.json) | Cópia do realm para o ambiente staging |
| [`infra/docker-compose.yml`](infra/docker-compose.yml) | Infra-only: Kong + Keycloak + RabbitMQ + PostgreSQL |
| [`infra/docker-compose.dev.yml`](infra/docker-compose.dev.yml) | Dev completo com hot-reload (infra + order-service + wallet-service) |
| [`infra/docker-compose.staging.yml`](infra/docker-compose.staging.yml) | Staging com réplicas (3× MongoDB, PostgreSQL, Redis, RabbitMQ) |
| [`tests/docker-compose.test.yml`](tests/docker-compose.test.yml) | Stack de integração isolada + test-runner Maven |
| [`infra/docker-compose.staging.yml`](infra/docker-compose.staging.yml) | Adicionados `keycloak-db`, `keycloak` com `start --optimized` |

## 🧪 Testes

✅ **Todos os testes executam no Docker**

```bash
# Windows
.\build.ps1 docker-test

# Linux/Mac
make docker-test

# Direto com Docker Compose
docker compose -f tests/docker-compose.test.yml up
```

Ver logs detalhados:
```bash
docker compose -f tests/docker-compose.test.yml logs -f test-runner
```

## 🐳 Docker Environments

### Desenvolvimento (com hotreload)
```bash
.\build.ps1 docker-dev-up

# Acesse: http://localhost:8080 (Order Service)
# Debug:  localhost:5005
```

### Testes Automatizados
```bash
.\build.ps1 docker-test

# Todos os testes rodam em containers
```

### Produção
```bash
.\build.ps1 docker-prod-up

# Serviços otimizados para produção
```

## ✅ Pré-requisitos

O projeto requer **apenas Docker** na sua máquina:
- ✅ Docker Desktop (Windows/Mac) ou Docker Engine (Linux)
- ✅ Docker Compose (incluído no Docker Desktop)

**NÃO precisa instalar**:
- ❌ Java/JDK
- ❌ Maven
- ❌ Node.js ou outra Tools

## 💡 Recursos

- **Hotreload**: Spring Boot DevTools + Docker volumes
- **Testing**: JUnit 5, Mockito, AssertJ, REST Assured, WireMock (todos em container)
- **Debug**: Remote debugging via porta 5005 (dev) / 5006 (wallet)
- **Debug**: Portas 5005 (Order) e 5006 (Wallet)
- **Cobertura**: Jacoco integration

---

Para mais detalhes, veja: [docs/](docs/)
