# 🎯 VIBRANIUM ORDER BOOK - QUICK REFERENCE

## ⚡ Comandos Essenciais

> **Copie e cole direto no terminal**

### 🔨 Build Completo

```bash
# Build apenas Backend (sem Docker)
mvn clean install -DskipTests

# Build com Docker das aplicações
mvn clean package -DskipTests
docker build -t order-service:latest -f apps/order-service/Dockerfile .
docker build -t wallet-service:latest -f apps/wallet-service/Dockerfile .
```

### 🐳 Subir Ambientes

```bash
# DEV (Recomendado para começar)
cd infra/
docker-compose -f docker-compose.dev.yml up -d
# Aguarde 2-3 minutos para healthchecks

# STAGING (Para testes de alta carga)
docker-compose -f docker-compose.staging.yml up -d

# Parar ambiente
docker-compose -f docker-compose.dev.yml down
docker-compose -f docker-compose.staging.yml down
```

### ▶️ Rodar Microsserviços Localmente

```bash
# Terminal 1 - Order Service
mvn -pl apps/order-service spring-boot:run

# Terminal 2 - Wallet Service
mvn -pl apps/wallet-service spring-boot:run

# Aguarde: "Started XxxApplication in X.XXXs"
```

### 🧪 Testar Endpoints

```bash
# Health checks
curl http://localhost:8080/api/v1/orders/health
curl http://localhost:8081/api/v1/wallets/health

# Criar carteira
curl -X POST http://localhost:8081/api/v1/wallets \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'userId=trader1&currency=EUR&initialBalance=10000'

# Criar ordem
curl -X POST http://localhost:8080/api/v1/orders \
  -H 'Content-Type: application/json' \
  -d '{
    "userId":"trader1",
    "symbol":"EUR/USD",
    "side":"BUY",
    "quantity":100,
    "price":1.10,
    "orderType":"LIMIT"
  }'
```

### 📊 Acessar Dashboards

| Serviço | URL | Credenciais |
|---------|-----|-----------|
| Keycloak | http://localhost:8180 | admin/admin123 |
| RabbitMQ | http://localhost:15672 | guest/guest |
| Kong Admin | http://localhost:8001/status | — |
| Konga UI | http://localhost:1337 | — |

### 📝 Gerenciar Git

```bash
# Ver commits
git log --oneline -10

# Commits estruturados (conventional commits)
git add .
git commit -m "feat(order-service): add websocket support"
git commit -m "fix(wallet): prevent double debit"
git commit -m "infra(docker): upgrade to Alpine 3.19"

# Push
git push origin main
```

### 🔍 Diagnosticar Problemas

```bash
# Ver logs em tempo real
docker-compose -f infra/docker-compose.dev.yml logs -f order-service
docker-compose -f infra/docker-compose.dev.yml logs -f wallet-service

# Status dos containers
docker-compose -f infra/docker-compose.dev.yml ps

# Executar comando em container
docker exec vibranium-postgres psql -U postgres -c "SELECT 1;"
docker exec vibranium-mongodb mongosh -u admin -p admin123

# Inspecionar rede
docker network inspect vibranium-network

# Limpar volumes (cuidado! deleta dados)
docker volume prune
```

### 📦 Test & Coverage

```bash
# Todos os testes
mvn clean test

# Testes específicos
mvn test -Dtest=OrderCommandServiceTest

# Com coverage
mvn clean test jacoco:report
# Abrir: target/site/jacoco/index.html
```

---

## 📊 Estrutura de Commits

```
chore(init): initialize monorepo structure with project metadata
    ↓
feat(order-service): implement CQRS order book with MongoDB and Redis matching engine
    ↓
feat(wallet-service): implement idempotent balance management with PostgreSQL transactions
    ↓
infra(docker): add docker-compose for dev and staging with Kong + Keycloak
    ↓
docs(final): add architecture, deployment guides and build scripts
```

## 🏗️ Estrutura do Projeto

```
vibranium-orderbook/
├── apps/
│   ├── order-service/
│   │   ├── src/main/java/com/vibranium/orderservice/
│   │   │   ├── command/        (CQRS Write-side)
│   │   │   ├── query/          (CQRS Read-side)
│   │   │   ├── matching/       (Redis order book engine)
│   │   │   └── controller/     (REST endpoints)
│   │   └── Dockerfile
│   │
│   └── wallet-service/
│       ├── src/main/java/com/vibranium/walletservice/
│       │   ├── domain/         (Entities + Repositories)
│       │   ├── service/        (Business logic com idempotência)
│       │   ├── listener/       (RabbitMQ event listeners)
│       │   └── controller/     (REST endpoints)
│       └── Dockerfile
│
├── libs/
│   ├── common-contracts/       (Event DTOs - BaseEvent, OrderCreatedEvent, etc)
│   └── common-utils/           (ApiResponse, CommonUtils)
│
├── infra/
│   ├── docker-compose.dev.yml  (Dev: instâncias únicas)
│   ├── docker-compose.staging.yml (Staging: 3 réplicas, limites 512MB/0.5CPU)
│   ├── kong/                   (API Gateway config)
│   └── keycloak/               (Identity provider config)
│
├── docs/
│   ├── ARCHITECTURE.md         (Diagrama + fluxos)
│   ├── BUILD_AND_DEPLOYMENT.md (Guia completo)
│   └── GETTING_STARTED.md      (First steps)
│
├── scripts/
│   ├── build.sh                (Automa build Maven + Docker)
│   └── deploy.sh               (Gerencia stack Docker)
│
└── pom.xml                     (POM raiz com módulos)
```

## 🚀 Workflow Típico

### Dia 1: Desenvolvimento Local

```bash
# 1. Clonar
git clone https://github.com/vibranium/orderbook.git
cd orderbook

# 2. Build
mvn clean install -DskipTests

# 3. Infraestrutura
docker-compose -f infra/docker-compose.dev.yml up -d

# 4. Rodar serviços
mvn -pl apps/order-service spring-boot:run &
mvn -pl apps/wallet-service spring-boot:run &

# 5. Testar
curl http://localhost:8080/api/v1/orders/health

# 6. Parar
docker-compose -f infra/docker-compose.dev.yml down
```

### Semana 1: Criar Feature

```bash
# 1. Feature branch
git checkout -b feat/websocket-orders

# 2. Código
# ... editar arquivos ...

# 3. Test
mvn test

# 4. Commit
git add .
git commit -m "feat(order-service): add websocket support for order updates"

# 5. Push
git push origin feat/websocket-orders

# 6. Pull Request (GitHub)
# Review + Merge
```

### Release: Deploy em Staging

```bash
# 1. Build completo
mvn clean install

# 2. Docker
docker build -t order-service:v1.0.0 -f apps/order-service/Dockerfile .
docker build -t wallet-service:v1.0.0 -f apps/wallet-service/Dockerfile .

# 3. Tag Git
git tag -a v1.0.0 -m "Release 1.0.0"
git push origin v1.0.0

# 4. Deploy Staging
docker-compose -f infra/docker-compose.staging.yml up -d

# 5. Teste carga
# ... stress tests ...

# 6. Deploy Prod (CI/CD)
# ... automático via GitHub Actions ...
```

## 📈 Métricas de Performance

| Métrica | Dev | Staging | Produção |
|---------|-----|---------|----------|
| Order Latency | <100ms | <100ms | <50ms |
| Throughput | 100 orders/s | 10k orders/s | 100k+ orders/s |
| Wallet Txn | <50ms | <50ms | <20ms |
| Memory/Pod | Unlimited | 512MB | 1GB-2GB |
| CPU/Pod | Unlimited | 0.5 cores | 2-4 cores |
| Replicas | 1 | 3 | 5-50 (autoscale) |
| Storage | 10GB | 50GB | 1TB+ |

## 🔗 Links Úteis

- **Source:** [GitHub Repo](https://github.com/vibranium/orderbook)
- **Docs:** [Architecture](docs/ARCHITECTURE.md)
- **Board:** [JIRA Project](https://jira.vibranium.com/browse/VO)
- **Monitoring:** [Grafana Dashboard](http://monitoring.vibranium.com)
- **Chat:** [#vibranium-dev Slack](https://vibranium.slack.com)

---

**v1.0.0** | 2026-02-26 | Vibranium DevOps Team
