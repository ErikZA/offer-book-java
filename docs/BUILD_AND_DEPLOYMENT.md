# Build & Deployment Guide

## 📦 Comandos de Build

### 1. Build Completo do Monorepo

```bash
# Clean + Package (gera JARs)
mvn clean package

# Clean + Package + Instalar artifacts locais
mvn clean install

# Build paralelo (mais rápido)
mvn clean package -T 1C

# Build sem testes
mvn clean package -DskipTests
```

### 2. Build de Módulos Específicos

```bash
# Build apenas Order Service
mvn clean package -pl apps/order-service

# Build apenas Wallet Service
mvn clean package -pl apps/wallet-service

# Build de bibliotecas compartilhadas
mvn clean package -pl libs/common-contracts,libs/common-utils
```

### 3. Teste Unitário e Integração

```bash
# Rodar todos os testes
mvn clean test

# Rodar testes apenas de um módulo
mvn test -pl apps/order-service

# Rodar testes específicos
mvn test -Dtest=OrderCommandServiceTest

# Teste de integração apenas
mvn verify -DskipUnitTests
```

### 4. Build Docker (dentro de cada app)

```bash
# No diretório apps/order-service/
docker build -t order-service:latest .

# No diretório apps/wallet-service/
docker build -t wallet-service:latest .

# Ou via Maven plugin (se configurado)
mvn clean package docker:build
```

## 🐳 Ambientes Docker

### Desenvolvimento (Instâncias Únicas)

```bash
cd infra/

# Iniciar stack de dev
docker-compose -f docker-compose.dev.yml up -d

# Logs em tempo real
docker-compose -f docker-compose.dev.yml logs -f

# Parar
docker-compose -f docker-compose.dev.yml down

# Limpar volumes (cuidado! deleta dados)
docker-compose -f docker-compose.dev.yml down -v
```

**Endpoints Dev:**
- Order Service: http://localhost:8080
- Wallet Service: http://localhost:8081
- Kong Gateway: http://localhost:8000
- Keycloak Admin: http://localhost:8180 (admin/admin123)
- RabbitMQ Admin: http://localhost:15672 (guest/guest)
- MongoDB: localhost:27017
- PostgreSQL: localhost:5432

### Staging (3 Replicas, Limites de Memória/CPU)

```bash
cd infra/

# Iniciar stack de staging
docker-compose -f docker-compose.staging.yml up -d

# Logs por serviço
docker-compose -f docker-compose.staging.yml logs -f order-service-1

# Status dos containers
docker-compose -f docker-compose.staging.yml ps

# Parar
docker-compose -f docker-compose.staging.yml down
```

**Endpoints Staging:**
- Order Service (LB): http://localhost:8000 (via Kong)
- Wallet Service (LB): http://localhost:8000 (via Kong)
- Kong Admin: http://localhost:8001

## 🚀 Workflow de Deploy

### 1. Desenvolvimento Local

```bash
# Build monorepo
mvn clean install

# Subir infraestrutura
docker-compose -f infra/docker-compose.dev.yml up -d

# Aguardar healthchecks
docker-compose -f infra/docker-compose.dev.yml ps

# Executar serviços via IDE ou:
mvn -pl apps/order-service spring-boot:run
mvn -pl apps/wallet-service spring-boot:run

# Testar
curl http://localhost:8080/api/v1/orders/health
curl http://localhost:8081/api/v1/wallets/health
```

### 2. Build de Imagens Docker

```bash
# Navegar para cada app e criar Dockerfile se não existir
# apps/order-service/Dockerfile
# apps/wallet-service/Dockerfile

# Build multistage (otimizado)
docker build -t order-service:latest -f apps/order-service/Dockerfile .
docker build -t wallet-service:latest -f apps/wallet-service/Dockerfile .

# Tag para registry
docker tag order-service:latest myregistry.azurecr.io/order-service:latest
docker tag wallet-service:latest myregistry.azurecr.io/wallet-service:latest

# Push
docker push myregistry.azurecr.io/order-service:latest
docker push myregistry.azurecr.io/wallet-service:latest
```

### 3. Deploy em Staging

```bash
# Atualizar docker-compose.staging.yml com as imagens corretas
# Modificar image: order-service:latest → image: myregistry.azurecr.io/order-service:latest

docker-compose -f infra/docker-compose.staging.yml up -d

# Aguardar healthchecks
docker-compose -f infra/docker-compose.staging.yml ps

# Inspecionar logs
docker-compose -f infra/docker-compose.staging.yml logs order-service-1 -f
```

### 4. Configurar Kong Gateway

```bash
# 1. Criar serviço no Kong
curl -X POST http://localhost:8001/services \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "order-service",
    "url": "http://order-service-1:8080"
  }'

# 2. Criar rota
curl -X POST http://localhost:8001/services/order-service/routes \
  -H 'Content-Type: application/json' \
  -d '{
    "paths": ["/api/v1/orders"],
    "strip_path": false
  }'

# 3. Repetir para wallet-service
# ... (similar)

# 4. Adicionar plugin JWT (validação Keycloak)
curl -X POST http://localhost:8001/routes/{route-id}/plugins \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "jwt",
    "config": {
      "key_claim_name": "kid"
    }
  }'
```

### 5. Configurar Keycloak

Acesse http://localhost:8180 (admin/admin123):

1. **Criar Realm:** "vibranium"
2. **Criar Clients:**
   - `api-gateway` (Public)
   - `order-service` (Service Account)
   - `wallet-service` (Service Account)
3. **Criar Usuário de Teste:**
   - Usuario: trader1
   - Email: trader1@vibranium.com
   - Password: Trader@123456

## 📊 Health Checks

```bash
# Order Service
curl http://localhost:8080/actuator/health

# Wallet Service
curl http://localhost:8081/actuator/health

# Kong
curl http://localhost:8001/status

# RabbitMQ
curl http://guest:guest@localhost:15672/api/aliveness-test/%2F
```

## 🔍 Troubleshooting

### Problema: Container não sobe
```bash
# Verificar logs
docker-compose -f infra/docker-compose.dev.yml logs {service-name}

# Inspecionar network
docker network inspect vibranium-network
```

### Problema: Porta já em uso
```bash
# Liberar porta
lsof -i :{port}
kill -9 {PID}

# Ou mudar porta no docker-compose.yml
```

### Problema: Aplicação não conecta ao banco
```bash
# Verificar conectividade
docker exec {container-id} ping {service-name}

# Testar conexão diretamente
docker exec -it vibranium-postgres psql -U postgres -c "SELECT 1;"
```

## 📈 Performance e Limites

**Staging realiza:**
- 3 réplicas por microsserviço
- Limite de memória: 512MB por container
- Limite de CPU: 0.5 cores por container
- Healthchecks a cada 15s

**Para produção, considerar:**
- Auto-scaling horizontal
- Circuit breakers (Resilience4j)
- Distributed caching
- Tracing distribuído (Jaeger/Zipkin)
- Monitoring (Prometheus + Grafana)

---

**Última atualização:** 2026-02-26
