# Docker Compose Environments

Diferentes configurações Docker para cada estágio do desenvolvimento.

## 🐳 Ambientes

### **docker-compose.yml** (Production)
Configuração para deploy em produção com multi-stage builds otimizados.

```bash
docker-compose -f docker/docker-compose.yml up -d
```

**Serviços:**
- Order Service (port 8080)
- Wallet Service (port 8081)
- PostgreSQL (produção)
- MongoDB (produção)
- Redis
- RabbitMQ

---

### **docker-compose.dev.yml** (Development)
Ambiente com **hotreload automático**, volumes montados e debug ports.

```bash
docker-compose -f docker/docker-compose.dev.yml up
```

**Features:**
- ✅ Hotreload com Spring Boot DevTools
- 🔍 Debug remoto (ports 5005, 5006)
- 📁 Volumes para código-fonte
- 🔄 Auto-restart em containers
- 📦 Cache de dependências persistente

**Portas:**
- Order Service: 8080 (debug: 5005)
- Wallet Service: 8081 (debug: 5006)
- PostgreSQL: 5432
- MongoDB: 27017

---

### **docker-compose.test.yml** (Testing)
Ambiente isolado para testes automatizados com TDD.

```bash
docker-compose -f docker/docker-compose.test.yml up
# Ou com auto-stop:
docker-compose -f docker/docker-compose.test.yml up --exit-code-from test-runner
```

**Features:**
- 🧪 Testes automatizados
- 📊 Reports gerados
- Health checks antes de testes
- Cleanup automático após execução

**Saída:**
- Testes: stdout
- Relatórios: `test-results/`

---

## 🚀 Quick Start

```bash
# Development (hotreload)
docker-compose -f docker/docker-compose.dev.yml up

# Testes (TDD)
docker-compose -f docker/docker-compose.test.yml up

# Produção
docker-compose -f docker/docker-compose.yml up -d
```

## 🔗 Networks

Todos os ambientes usam redes nomeadas:
- `vibranium-network-dev` (desenvolvimento)
- `vibranium-network-test` (testes)
- `vibranium-network` (produção)

Permite isolamento entre ambientes.

---

Volte ao [README principal](../README.md) para mais informações.
