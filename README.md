# Vibranium Order Book Platform

Uma plataforma de high-performance para matching de ordens com arquitetura baseada em eventos e integração de carteira digital.

## 🏗️ Arquitetura

```
vibranium-orderbook/
├── apps/
│   ├── order-service          # Serviço de Order Book (MongoDB + Redis)
│   └── wallet-service         # Serviço de Carteira (PostgreSQL)
├── libs/
│   ├── common-contracts       # Contratos de eventos compartilhados
│   └── common-utils           # Utilitários comuns
├── infra/
│   ├── docker-compose.dev.yml      # Desenvolvimento (instâncias únicas)
│   ├── docker-compose.staging.yml  # Staging (3 réplicas, limites)
│   ├── kong/                       # Configuração do API Gateway
│   └── keycloak/                   # Autenticação JWT
└── docs/                           # Documentação
```

## 🚀 Pré-requisitos

- **Java 21**
- **Maven 3.8.1+**
- **Docker & Docker Compose**
- **Git**

## 📦 Build

### Build completo do monorepo
```bash
mvn clean package
```

### Build de um módulo específico
```bash
# Order Service
mvn clean package -pl apps/order-service

# Wallet Service
mvn clean package -pl apps/wallet-service
```

### Build com skip tests
```bash
mvn clean package -DskipTests
```

## 🐳 Ambientes

### Desenvolvimento
```bash
docker-compose -f infra/docker-compose.dev.yml up -d
```

**Serviços:**
- Order Service: http://localhost:8080
- Wallet Service: http://localhost:8081
- Kong Gateway: http://localhost:8000
- Keycloak: http://localhost:8180
- MongoDB: localhost:27017
- PostgreSQL: localhost:5432
- RabbitMQ: http://localhost:15672 (guest/guest)
- Redis: localhost:6379

### Staging
```bash
docker-compose -f infra/docker-compose.staging.yml up -d
```

**Features:**
- 3 réplicas por serviço
- Limites de memória: 512MB
- Limites de CPU: 0.5 cores
- Healthchecks configurados

## 📝 Eventos

### Contratos em `libs/common-contracts/`

- `OrderCreatedEvent`: Notifica criação de ordem
- `OrderMatchedEvent`: Notifica matching de ordem
- `WalletDebitedEvent`: Notifica débito de carteira
- `WalletCreditedEvent`: Notifica crédito de carteira

## 🔐 Autenticação

JWT validado via Keycloak (Kong como gateway).

## 📚 Documentação

Veja [docs/](./docs/) para arquitetura detalhada, diagramas e guia de deployment.

## 🛠️ Desenvolvimento Local

1. **Build dos módulos**:
   ```bash
   mvn clean package
   ```

2. **Subir infraestrutura**:
   ```bash
   docker-compose -f infra/docker-compose.dev.yml up -d
   ```

3. **Executar serviços** (via IDE ou Maven):
   ```bash
   mvn -pl apps/order-service spring-boot:run
   mvn -pl apps/wallet-service spring-boot:run
   ```

4. **Verificar saúde**:
   ```bash
   curl http://localhost:8080/actuator/health
   curl http://localhost:8081/actuator/health
   ```

## 📌 Commits Estruturados

Utilizamos conventional commits:
- `feat(module)`: Nova feature
- `fix(module)`: Correção de bug
- `docs(module)`: Documentação
- `infra(docker)`: Mudanças de infraestrutura
- `refactor(module)`: Refatoração sem mudança de comportamento

## 📚 Documentação Completa

- [🚀 Quick Reference](./QUICK_REFERENCE.md) - Comandos essenciais (copiar-colar)
- [🏗️ Architecture](./docs/ARCHITECTURE.md) - Design patterns e fluxos
- [🔨 Build & Deployment](./docs/BUILD_AND_DEPLOYMENT.md) - Guia passo-a-passo
- [🎯 Getting Started](./docs/GETTING_STARTED.md) - Primeiros passos

---

**Mantido por:** Vibranium DevOps Team | **Última atualização:** 2026-02-26
