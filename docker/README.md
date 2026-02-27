# Docker Compose Environments

Diferentes configurações Docker para cada estágio do desenvolvimento.

## 🐳 Ambientes

### **docker-compose.yml** (Production Infrastructure)
Configuração de infraestrutura robusta: **PostgreSQL 16 + Kong 3.4 + Keycloak 22**

```bash
# Iniciar
docker-compose -f docker/docker-compose.yml up -d

# Aguardar healthchecks
docker-compose ps

# Validar infraestrutura
../scripts/verify-infra.sh  # Linux/Mac
../scripts/verify-infra.ps1 # Windows
```

**Serviços:**
- 🐘 **PostgreSQL 16** (port 5432) - Database centralizado
- 🔌 **Kong 3.4** (port 8000 proxy, 8001 admin interno)
- 🔐 **Keycloak 22** (port 8080) - Autenticação centralizada

**Recursos limitados para determinismo**:
- PostgreSQL: 1.0 CPU, 1024 MB RAM
- Kong: 0.75 CPU, 512 MB RAM
- Keycloak: 1.0 CPU, 1024 MB RAM

**7 Validações Automáticas** (verify-infra.sh):
1. ✅ Kong rodando e saudável
2. ✅ Kong conectado ao PostgreSQL
3. ✅ Tabelas Kong criadas (services, routes, plugins)
4. ✅ Keycloak rodando e saudável
5. ✅ Keycloak conectado ao PostgreSQL
6. ✅ Tabelas Keycloak criadas (user_entity, realm, client, role_entity)
7. ✅ Docker Compose testes validado

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

**Serviços:**
- Order Service (port 8080, debug: 5005)
- Wallet Service (port 8081, debug: 5006)
- PostgreSQL, MongoDB, Redis, RabbitMQ

---

### **docker-compose.test.yml** (Testing)
Ambiente isolado para testes com infraestrutura completa.

```bash
# Testes com infraestrutura Kong + Keycloak
docker-compose -f docker/docker-compose.test.yml up

# Ou com auto-stop:
docker-compose -f docker/docker-compose.test.yml up --exit-code-from test-runner
```

**Features:**
- 🧪 Testes com infraestrutura real
- 📊 Reports gerados
- Health checks sequenciais
- 🔌 Kong + Keycloak isolados (portas diferentes)

**Serviços:**
- PostgreSQL-test (port 5433)
- Kong-test (port 8000, 8001)
- Keycloak-test (port 8081)
- MongoDB, Redis, RabbitMQ
- Test-runner (Maven)

**Saída:**
- Testes: stdout
- Relatórios: `test-results/`

---

## 🚀 Quick Start

```bash
# 1. Infraestrutura (Kong + Keycloak)
cd docker
docker-compose up -d
docker-compose ps  # Aguardar healthchecks

# 2. Validar (7 requisitos)
../scripts/verify-infra.sh  # ou .ps1 no Windows

# 3. Desenvolvimento com hotreload
docker-compose -f docker-compose.dev.yml up

# 4. Testes
docker-compose -f docker-compose.test.yml up --exit-code-from test-runner
```

---

## 📊 Arquiotura de Rede

```
┌─────────────────────────────────────────┐
│   Docker Networks (Isolamento)          │
├─────────────────────────────────────────┤
│                                         │
│  vibranium-infra                        │
│  ├─ PostgreSQL (porta 5432)            │
│  ├─ Kong (portas 8000, 8001, 8002)     │
│  └─ Keycloak (porta 8080)              │
│                                         │
│  vibranium-network-dev                  │
│  ├─ Order Service (8080)               │
│  ├─ Wallet Service (8081)              │
│  └─ Database + Cache                    │
│                                         │
│  vibranium-network-test                 │
│  ├─ Kong-test (8000, 8001)             │
│  ├─ Keycloak-test (8081)               │
│  ├─ PostgreSQL-test (5433)             │
│  └─ Test Runner                        │
│                                         │
└─────────────────────────────────────────┘
```

---

## 🔧 Arquivos de Inicialização

### **init-db.sql**
Script SQL idempotente que:
- ✅ Cria schema Kong (se não existir)
- ✅ Cria schema Keycloak (if não existir)
- ✅ Cria tabelas críticas (com IF NOT EXISTS)
- ✅ Cria índices para performance
- ✅ Define permissões

**Rodado por**: PostgreSQL no startup (via docker-entrypoint-initdb.d/)

---

## 🔍 Verificação de Saúde

### Verificar Containers

```bash
# Status geral
docker-compose ps

# Logs específicos
docker-compose logs -f kong
docker-compose logs -f keycloak
docker-compose logs -f postgresql
```

### Testar Conectividade

```bash
# Kong Admin API
curl http://localhost:8001/status

# Kong Proxy
curl http://localhost:8000/

# Keycloak
curl http://localhost:8080/auth/health/live
curl http://localhost:8080/auth/health/ready

# PostgreSQL
PGPASSWORD=postgres123 psql -h localhost -U postgres -d vibranium_infra -c "SELECT 1"
```

---

## 🧹 Cleanup

```bash
# Parar containers
docker-compose down

# Remover volumes (⚠️ Deleta dados!)
docker-compose down -v

# Remover networks
docker network prune

# Limpar tudo
docker system prune -a
```

---

## 🔐 Segurança (Produção)

⚠️ **IMPORTANTE**: Antes de deploy em produção:

```yaml
# 1. Mudar credenciais
POSTGRES_PASSWORD: postgres123  → Usar secrets
KEYCLOAK_ADMIN_PASSWORD: admin123  → Usar secrets

# 2. Configurar secrets Docker
docker secret create pg_password -
docker secret create kc_password -

# 3. Restringir portas
- Kong admin: localhost apenas (já configurado)
- Keycloak admin: ativar autenticação

# 4. Ativar TLS/SSL
KC_HTTPS_CERTIFICATE_FILE: /path/to/cert
KC_HTTPS_CERTIFICATE_KEY_FILE: /path/to/key
```

---

## 📚 Documentação Completa

Para decisões arquiteturais em profundidade, consulte:
- [INFRASTRUCTURE_DECISIONS.md](../docs/INFRASTRUCTURE_DECISIONS.md)
- [Scripts de validação](../scripts/README.md)
- [README principal](../README.md)
