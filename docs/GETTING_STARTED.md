# Getting Started

## Pré-requisitos

- **Java 21+**
- **Maven 3.8.1+**
- **Docker & Docker Compose 2.0+**
- **Git**

## Verificar Instalações

```bash
java -version     # Java 21.x.x
mvn -version      # Apache Maven 3.8.1+
docker -version   # Docker 24.x.x+
docker-compose -version  # Docker Compose 2.x.x+
```

## 1️⃣ Clonar Repositório

```bash
git clone https://github.com/vibranium/orderbook.git
cd orderbook
```

## 2️⃣ Build do Monorepo

```bash
# Build completo com testes
mvn clean install

# Build sem testes (mais rápido)
mvn clean package -DskipTests

# Verificar artefatos gerados
ls apps/order-service/target/*.jar
ls apps/wallet-service/target/*.jar
```

## 3️⃣ Iniciar Infraestrutura

### Opção A: Desenvolvimento (Recomendado para começar)

```bash
cd infra/

# Subir toda a stack (MongoDB, PostgreSQL, Redis, RabbitMQ, Keycloak, Kong)
docker-compose -f docker-compose.dev.yml up -d

# Aguardar healthchecks (2-3 minutos)
docker-compose -f docker-compose.dev.yml ps

# Verificar que todos mostram "healthy"
```

### Opção B: Staging (Para testes de carga)

```bash
cd infra/

# Build das imagens dos serviços
cd ..
docker build -t order-service:latest -f apps/order-service/Dockerfile .
docker build -t wallet-service:latest -f apps/wallet-service/Dockerfile .

# Subir stack staging
docker-compose -f infra/docker-compose.staging.yml up -d

# Verificar
docker-compose -f infra/docker-compose.staging.yml ps
```

## 4️⃣ Executar Serviços Localmente

### Via Maven

```bash
# Terminal 1: Order Service
mvn -pl apps/order-service spring-boot:run

# Terminal 2: Wallet Service
mvn -pl apps/wallet-service spring-boot:run

# Aguardar: "Started OrderServiceApplication in 5.123s"
```

### Via IDE

- IntelliJ IDEA / VS Code: Clique com botão direito em `OrderServiceApplication.java` → "Run"
- Repita para `WalletServiceApplication.java`

## 5️⃣ Testar Endpoints

### Health Checks

```bash
# Order Service
curl http://localhost:8080/api/v1/orders/health
# Response: Order Service OK

# Wallet Service
curl http://localhost:8081/api/v1/wallets/health
# Response: Wallet Service OK
```

### Acessar UIs

- **Keycloak:** http://localhost:8180 (admin/admin123)
- **RabbitMQ:** http://localhost:15672 (guest/guest)
- **Kong Admin:** http://localhost:8001/status

## 6️⃣ Primeiro Teste End-to-End

### 1. Criar Carteira

```bash
curl -X POST http://localhost:8081/api/v1/wallets \
  -H 'Content-Type: application/json' \
  -d 'userId=trader1&currency=EUR&initialBalance=10000'

# Response:
# {
#   "success": true,
#   "data": {
#     "id": 1,
#     "userId": "trader1",
#     "currency": "EUR",
#     "balance": 10000.00000000,
#     "availableBalance": 10000.00000000
#   }
# }
```

### 2. Criar Ordem de Compra

```bash
curl -X POST http://localhost:8080/api/v1/orders \
  -H 'Content-Type: application/json' \
  -d '{
    "userId": "trader1",
    "symbol": "EUR/USD",
    "side": "BUY",
    "quantity": 100,
    "price": 1.10,
    "orderType": "LIMIT"
  }'

# Response:
# {
#   "success": true,
#   "data": "550e8400-e29b-41d4-a716-446655440000",
#   "message": "Ordem criada com sucesso"
# }
```

### 3. Verificar Saldo Atualizado

```bash
curl http://localhost:8081/api/v1/wallets/trader1/EUR

# Response:
# {
#   "success": true,
#   "data": {
#     "userId": "trader1",
#     "currency": "EUR",
#     "balance": 10000.00000000,
#     "availableBalance": 8890.00000000,    # 10000 - 1100 (100 * 1.10)
#     "reservedBalance": 1100.00000000
#   }
# }
```

### 4. Consultar Ordem

```bash
curl http://localhost:8080/api/v1/orders/550e8400-e29b-41d4-a716-446655440000

# Response:
# {
#   "success": true,
#   "data": {
#     "id": "550e8400-e29b-41d4-a716-446655440000",
#     "userId": "trader1",
#     "symbol": "EUR/USD",
#     "side": "BUY",
#     "quantity": 100.00000000,
#     "price": 1.10000000,
#     "status": "PENDING",
#     "filledQuantity": 0.00000000,
#     "createdAt": "2026-02-26T12:00:00Z"
#   }
# }
```

## 📚 Próximos Passos

1. **Ler Arquitetura:** [docs/ARCHITECTURE.md](./ARCHITECTURE.md)
2. **Build & Deploy:** [docs/BUILD_AND_DEPLOYMENT.md](./BUILD_AND_DEPLOYMENT.md)
3. **Explorar Código:**
   - Lógica de CQRS: `apps/order-service/command/`, `apps/order-service/query/`
   - Matching Engine: `apps/order-service/matching/OrderMatchingEngine.java`
   - Idempotência: `apps/wallet-service/service/WalletService.java`
   - Eventos: `libs/common-contracts/events/`

## 🆘 Troubleshooting

### "Port already in use"

```bash
# Liberar porta (ex: 8080)
lsof -i :8080
kill -9 {PID}

# Ou mudar porta em application.yaml
# server.port: 8090
```

### "Connection refused" ao conectar em banco

```bash
# Verificar se containers estão saudáveis
docker-compose -f infra/docker-compose.dev.yml ps

# Checar logs
docker-compose -f infra/docker-compose.dev.yml logs postgres
docker-compose -f infra/docker-compose.dev.yml logs mongodb
```

### "Timed out waiting for connection"

```bash
# Incrementar wait-for em application.yaml
spring.jpa.properties.hibernate.jdbc.batch_size: 20

# Ou aumentar timeout do banco
# jdbc:postgresql://localhost:5432/vibranium_wallet?connectTimeout=30000
```

## 📞 Suporte

- **Issues:** GitHub Issues
- **Docs:** Ver pasta `/docs`
- **Chat:** Slack #vibranium-dev

---

**Bem-vindo ao Vibranium Order Book Platform! 🚀**
