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
│   ├── kong-setup.sh               # Provisiona consumer JWT + credential Keycloak JWKS (one-shot)
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
docker compose -f infra/docker-compose.dev.yml up -d

# Subir apenas infraestrutura (sem apps)
# mongo-rs-init é obrigatório: inicializa o replica set rs0 antes que os serviços conectem
docker compose -f infra/docker-compose.dev.yml up -d postgres redis rabbitmq mongodb mongo-rs-init keycloak-db keycloak kong jaeger
```

> **Jaeger UI** disponível em `http://localhost:16686` após subir o ambiente dev.
> Selecione o serviço `order-service` ou `wallet-service` para ver os traces end-to-end da Saga.

### Infra isolada (sem aplicações)
```bash
docker compose -f infra/docker-compose.yml up -d
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
  postgres-primary redis-1 rabbitmq-1 keycloak-db keycloak kong-database kong-migration redis-kong kong kong-init

# Stack completo (requer imagens pré-buildadas: order-service:latest, wallet-service:latest)
docker compose -f infra/docker-compose.staging.yml up -d
```

> **Kong Init (AT-5.1.4)** — Em staging, o serviço `kong-init` (container de curta duração) provisiona
> automaticamente 2 services (`order-service`, `wallet-service`), 3 routes e 9 plugins
> (`jwt` + `rate-limiting` + `cors` × 3 rotas) via `kong-setup.sh` (idempotente via PUT).
> Aguarda `kong`, `keycloak` e `redis-kong` estarem `healthy` antes de executar.
> Rate-limiting usa `redis-kong:6379 db=1`. Consumer `keycloak-realm-consumer` registrado
> com credencial JWT RS256 (JWKS do Keycloak). Após subir: `curl -s -o /dev/null -w "%{http_code}" http://localhost:8000/api/v1/orders` deve retornar `401` (não `404`).
>
> **PostgreSQL Streaming Replication (AT-5.1.3)** — Em staging, `postgres-primary` opera com
> `wal_level=replica` e cria o usuário `replicator`. As réplicas (`postgres-replica-1/2`) usam
> `pg-replica-entrypoint.sh` para clonar o primary via `pg_basebackup` e iniciar como
> `hot_standby` (somente leitura). Todos os `wallet-service` (1, 2 e 3) apontam para
> `postgres-primary` para escrita — a `condition: service_healthy` no `depends_on` garante
> que o `initdb` + scripts de init + schema `vibranium` estejam prontos antes do Flyway conectar.
> Para validar: `bash tests/AT-5.1.3-pg-streaming-replication-validation.sh`.
>
> **⚠️ Primeiro boot:** apague os volumes das réplicas antes de subir:
> ```bash
> docker volume rm infra_postgres_replica_1_data infra_postgres_replica_2_data
> # ou use: docker compose -f infra/docker-compose.staging.yml down -v
```

## 🐳 Portas expostas (dev)

| Serviço          | Porta                        |
|------------------|------------------------------|
| PostgreSQL       | 5432                         |
| MongoDB          | 27017                        |
| Redis            | 6379                         |
| RabbitMQ         | 5672 / 15672 (UI)            |
| Keycloak         | 8080                         |
| Kong Proxy       | 8000 / 8443                  |
| Kong Admin       | 8001 / 8444                  |
| Jaeger UI        | 16686                        |
| Jaeger OTLP HTTP | 4318 (apps → Jaeger spans)   |
| Jaeger OTLP gRPC | 4317                         |
| Redis Cluster    | 6379–6384 (AT-15, redis-cluster) |

> **Redis-Kong** (`redis-kong` / `vibranium-redis-kong`) **não expõe porta pública**.
> Acessível somente internamente via `vibranium-infra`. Serve exclusivamente ao plugin
> `rate-limiting` do Kong com `policy: redis` — contadores de rate-limiting globais (db=1).

## ⚙️ Rate Limiting Distribuído (Kong + Redis)

O plugin `rate-limiting` do Kong está configurado com `policy: redis` em todas as rotas.
Isso garante que, em cluster Kong com múltiplos nós, o contador de requisições seja
**compartilhado globalmente** — sem isso, cada nó manteria contador independente,
multiplicando o limite efetivo pelo número de nós.

| Configuração | Valor | Motivo |
|---|---|---|
| `policy` | `redis` | contador global entre todos os nós Kong |
| `redis_host` | `redis-kong` | Redis dedicado (não o Redis de aplicação) |
| `redis_port` | `6379` | porta padrão Redis |
| `redis_database` | `1` | namespace isolado (app usa db=0) |
| `redis_password` | `${REDIS_KONG_PASSWORD}` | autenticação `requirepass` (AT-04) |
| `fault_tolerant` | `true` | Kong não bloqueia se Redis cair |

Script de validação: `tests/AT-12.1-rate-limiting-redis-validation.sh`

```bash
KONG_ADMIN_URL=http://localhost:8001 ./tests/AT-12.1-rate-limiting-redis-validation.sh
```

## ⚠️ Observações técnicas

- **Keycloak**: imagem customizada com `keycloak-to-rabbit-3.0.5.jar` compilada via `kc.sh build --db=postgres --health-enabled=true`. O modo `start --optimized` (staging) exige essas flags no build-time.
- **Kong 3.4**: não possui `curl` na imagem — healthcheck usa `kong health`.
- **Redis-Kong**: Redis standalone dedicado ao Kong rate-limiting. Os redis de aplicação (`redis-1/2/3`) usam cluster mode — incompatível com o plugin rate-limiting do Kong 3.x (requer standalone ou Sentinel). Dados efêmeros por design (`appendonly no`).
- **Redis Cluster HA (AT-15)**: `docker-compose.redis-cluster.yml` provisiona 6 nós (3 masters + 3 replicas) com failover automático em < 10s (`cluster-node-timeout 5000`, `cluster-require-full-coverage no`). Todas as keys do Order Book usam hash tag `{vibranium}` para garantir mesmo slot CRC16. O profile Spring `cluster` ativa `spring.data.redis.cluster.nodes` com Lettuce adaptive topology refresh. Documentação completa: `docs/architecture/redis-cluster-setup.md`.
- **MongoDB 7.0 — Replica Set Staging (AT-1.3.2)**: `docker-compose.staging.yml` usa 3 nós (`mongodb-1/2/3`) com `--replSet rs0 --keyFile /etc/mongod-keyfile`. Diferente do dev (keyFile aleatório por boot, safe para single-node), em staging o `docker-entrypoint-override-staging.sh` grava `MONGO_REPLICA_KEY` (env var FIXA e IDÊNTICA nos 3 nós) como keyFile — necessário para intra-cluster auth. O serviço `mongo-rs-init` executa `rs.initiate({_id:'rs0', members:[mongodb-1,mongodb-2,mongodb-3]})` após `service_healthy` nos 3 nós e aguarda eleicão do PRIMARY antes de sair com código 0. Os `order-services` usam `service_completed_successfully` em `mongo-rs-init`.
- **MongoDB 7.0 — Replica Set (AT-1.3.1)**: `MongoTransactionManager` exige replica set para criar sessões de transação. O serviço `mongodb` sobe com `--replSet rs0 --keyFile /etc/mongod-keyfile` (MongoDB 7 exige keyFile quando `--auth + --replSet` estão ativos). O `docker-entrypoint-override.sh` gera o keyFile via `openssl rand -base64 756` em cada boot (seguro para single-node dev). O serviço `mongo-rs-init` executa `rs.initiate()` após o healthcheck pass e aguarda `stateStr === 'PRIMARY'` antes de sair com código 0. O `order-service` usa `depends_on: mongo-rs-init: service_completed_successfully`.
- **init-app-databases.sh**: usa heredoc para passar `\gexec` ao `psql` (metacomando não funciona com `--command`).


| Serviço | Porta | Uso |
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
