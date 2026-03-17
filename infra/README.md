# Infrastructure

Configurações de infraestrutura centralizada da plataforma Vibranium.
Os arquivos Docker Compose foram reorganizados do diretório legado `docker/` para cá.

## 📁 Estrutura

```
infra/
├── docker-compose.yml          # Infra-only (Kong + Keycloak + PostgreSQL + RabbitMQ + Redis-Kong + jwks-rotator)
├── docker-compose.dev.yml      # Dev completo (infra + order-service + wallet-service hot-reload)
├── docker-compose.staging.yml  # Staging com réplicas (3× MongoDB rs0, PostgreSQL, Redis, RabbitMQ + Redis-Kong + kong-init)
├── docker-compose.redis-cluster.yml  # ⭐ AT-15: Redis Cluster HA (6 nodes: 3M+3R) para Order Book
├── redis/
│   └── redis-cluster.conf        # ⭐ AT-15: Config base compartilhada por todos os nós do cluster
├── docker/
│   ├── Dockerfile              # Imagem base para apps (build multi-stage Maven)
│   ├── Dockerfile.keycloak     # Keycloak 22 + plugin keycloak-to-rabbit-3.0.5.jar
│   │                           # Build com: --db=postgres --health-enabled=true
│   ├── Dockerfile.kong-init    # Kong-init: deck sync de configuração declarativa
│   └── Dockerfile.jwks-rotator # ⭐ Sidecar JWKS rotator (AT-13.1): Alpine + curl + jq
├── keycloak/
│   ├── realm-export.json       # Realm "orderbook-realm" (clientes, roles, mappers)
│   ├── keycloak-setup.sh       # Provisionamento pós-subida (usuários, grupos)
│   └── keycloak-to-rabbit-3.0.5.jar  # Plugin: eventos Keycloak → RabbitMQ
├── kong/
│   ├── kong-init.yml               # Configuração declarativa (services, routes, plugins)
│   ├── jwks-rotation.sh            # ⭐ Script idempotente de rotação JWKS (AT-13.1)
│   ├── jwks-rotator-entrypoint.sh  # ⭐ Entrypoint loop 6h do sidecar (AT-13.1)
│   └── kong-config.md              # Documentação das rotas configuradas
├── mongo/
│   ├── docker-entrypoint-override.sh          # Dev: gera keyFile aleatório por boot (single-node)
│   ├── docker-entrypoint-override-staging.sh  # ⭐ Staging: escreve MONGO_REPLICA_KEY como keyFile
│   │                                          # fixo e compartilhado pelos 3 nós (AT-1.3.2)
│   ├── init-mongo.js                          # Collections iniciais do MongoDB
│   ├── init-replica-set.sh                    # Dev: rs.initiate() single-node rs0 (AT-1.3.1)
│   └── init-replica-set-staging.sh            # ⭐ Staging: rs.initiate() 3-node rs0 (AT-1.3.2)
└── postgres/
    ├── init-app-databases.sh      # Dev: cria vibranium_orders + vibranium_wallet no 1º boot
    ├── init-infra-db.sql          # Cria schemas kong + keycloak em vibranium_infra
    ├── init-postgres.sql          # Schema vibranium_wallet (tabelas wallets + wallet_transactions)
    ├── pg-primary-init.sh         # ⭐ Staging (AT-5.1.3): executado pelo initdb do primary;
    │                              #    cria usuário `replicator` e configura pg_hba.conf para
    │                              #    aceitar conexões de streaming replication dos standbys
    └── pg-replica-entrypoint.sh   # ⭐ Staging (AT-5.1.3): substitui o entrypoint padrão das
                                   #    réplicas; executa pg_basebackup no primary, cria
                                   #    standby.signal e configura primary_conninfo em
                                   #    postgresql.auto.conf (hot_standby=on)
```

## 🚀 Comandos

### Desenvolvimento (infra + aplicações com hot-reload)
```bash
# Subir tudo
docker compose --env-file .env -f infra/docker-compose.dev.yml up -d

# Subir apenas infraestrutura (sem apps)
# mongo-rs-init é obrigatório: inicializa o replica set rs0 antes que os serviços conectem
docker compose --env-file .env -f infra/docker-compose.dev.yml up -d postgres redis rabbitmq mongodb mongo-rs-init keycloak-db keycloak kong jaeger
```

> **Jaeger UI** disponível em `http://localhost:16686` após subir o ambiente dev.
> Selecione o serviço `order-service` ou `wallet-service` para ver os traces end-to-end da Saga.

### Infra isolada (sem aplicações)
```bash
docker compose --env-file .env -f infra/docker-compose.yml up -d
```

### Redis Cluster HA (AT-15)
```bash
# Cluster Redis com 6 nós (3 masters + 3 replicas) para HA do Order Book
export REDIS_PASSWORD=sua_senha_segura
docker compose -f infra/docker-compose.redis-cluster.yml up -d

# Verificar status do cluster
docker exec vibranium-redis-node-1 redis-cli -a $REDIS_PASSWORD cluster info
docker exec vibranium-redis-node-1 redis-cli -a $REDIS_PASSWORD cluster nodes
```

### Staging (réplicas)
```bash
# Subir apenas os nós MongoDB e aguardar PRIMARY (recomendado para validação)
# mongo-rs-init executa rs.initiate() com os 3 membros e sai com código 0
docker compose -f infra/docker-compose.staging.yml up mongodb-1 mongodb-2 mongodb-3 -d
docker compose -f infra/docker-compose.staging.yml up mongo-rs-init

# Apenas infra base (recomendado para validação sem apps)
docker compose -f infra/docker-compose.staging.yml up -d \
  mongodb-1 mongodb-2 mongodb-3 mongo-rs-init \
|---------|-------|-----|
| PostgreSQL | 5432 | Banco de dados principal |
| MongoDB | 27017 | Audit/cache (opcional) |
| Redis | 6379 | Cache |
| RabbitMQ | 5672 | Message broker |
| Kong | 8000/8001 | API Gateway |
| Keycloak | 8180 | Auth server |

## 🔐 Segurança

- Credenciais em `.env` (nunca commit)
- Volumes nomeados para persistência
- Health checks em todos os serviços
- Redes isoladas por ambiente
- **Redis autenticado** (`requirepass`) — todos os containers Redis exigem senha via env var

### Redis Authentication (AT-04)

Todos os containers Redis da plataforma estão protegidos com `requirepass`:

| Variável | Uso | Escopo |
|----------|-----|--------|
| `REDIS_PASSWORD` | Redis de aplicação (order-service match engine) | dev, staging, infra |
| `REDIS_KONG_PASSWORD` | Redis dedicado ao Kong rate-limiting | dev, staging, infra |

As senhas são **obrigatórias** nos compose files (`${REDIS_PASSWORD:?REDIS_PASSWORD is required}`).
Defina-as no arquivo `.env` antes de subir os ambientes.

Healthchecks incluem `-a $PASSWORD` para garantir que a autenticação funcione.
O `application.yaml` do order-service lê `${REDIS_PASSWORD:}` (fallback vazio para testes locais sem Docker).

Script de validação: `tests/AT-04-redis-auth-validation.sh`

---

**Nota**: Docker Compose dev/test já pré-configura tudo. Não modifique diretamente exceto para staging/prod.
