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
docker compose -f docker/docker-compose.dev.yml up
docker compose -f docker/docker-compose.test.yml up
docker compose -f docker/docker-compose.yml up
```

## 📁 Estrutura

```
.
├── apps/                    # Microsserviços
│   ├── order-service/       # Trading orders
│   └── wallet-service/      # Wallet & transactions
├── libs/                    # Bibliotecas compartilhadas
│   └── common-contracts/    # Eventos e DTOs
├── docker/                  # Docker Compose files
├── infra/                   # Configurações de infra
├── scripts/                 # Utilitários
├── docs/                    # Documentação detalhada
├── init.ps1                 # Validação Docker (Windows)
├── Makefile                 # Tasks Docker (Linux/Mac)
└── pom.xml                  # POM raiz (Maven - uso interno)
```

## 📚 Documentação

- **[docs/testing/COMPREHENSIVE_TESTING.md](docs/testing/COMPREHENSIVE_TESTING.md)** - Guia completo com padrões de teste
- **[docker/README.md](docker/README.md)** - Como usar cada ambiente Docker
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

## 🧪 Testes

✅ **Todos os testes executam no Docker**

```bash
# Windows
.\build.ps1 docker-test

# Linux/Mac
make docker-test

# Direto com Docker Compose
docker compose -f docker/docker-compose.test.yml up
```

Ver logs detalhados:
```bash
docker compose -f docker/docker-compose.test.yml logs -f test-runner
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
