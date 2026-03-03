# Infrastructure

Configurações de infraestrutura centralizada da plataforma Vibranium.
Os arquivos Docker Compose foram reorganizados do diretório legado `docker/` para cá.

## 📁 Estrutura

```
infra/
├── docker-compose.yml          # Infra-only (Kong + Keycloak + PostgreSQL + RabbitMQ + Redis-Kong + jwks-rotator)
├── docker-compose.dev.yml      # Dev completo (infra + order-service + wallet-service hot-reload)
├── docker-compose.staging.yml  # Staging com réplicas (3× MongoDB, PostgreSQL, Redis, RabbitMQ + Redis-Kong)
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
└── postgres/
    ├── init-app-databases.sh   # Cria vibranium_orders + vibranium_wallet no 1º boot
    ├── init-infra-db.sql       # Cria schemas kong + keycloak em vibranium_infra
    ├── init-mongo.js           # Collections iniciais do MongoDB
    └── init-postgres.sql       # Dados iniciais extras (seed opcional)
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

### Staging (réplicas)
```bash
# Apenas infra base (recomendado para validação)
docker compose -f infra/docker-compose.staging.yml up -d \
  mongodb-1 postgres-primary redis-1 rabbitmq-1 keycloak-db keycloak kong-database kong-migration redis-kong kong

# Stack completo (requer imagens pré-buildadas: order-service:latest, wallet-service:latest)
docker compose -f infra/docker-compose.staging.yml up -d
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
| `fault_tolerant` | `true` | Kong não bloqueia se Redis cair |

Script de validação: `tests/AT-12.1-rate-limiting-redis-validation.sh`

```bash
KONG_ADMIN_URL=http://localhost:8001 ./tests/AT-12.1-rate-limiting-redis-validation.sh
```

## ⚠️ Observações técnicas

- **Keycloak**: imagem customizada com `keycloak-to-rabbit-3.0.5.jar` compilada via `kc.sh build --db=postgres --health-enabled=true`. O modo `start --optimized` (staging) exige essas flags no build-time.
- **Kong 3.4**: não possui `curl` na imagem — healthcheck usa `kong health`.
- **Redis-Kong**: Redis standalone dedicado ao Kong rate-limiting. Os redis de aplicação (`redis-1/2/3`) usam cluster mode — incompatível com o plugin rate-limiting do Kong 3.x (requer standalone ou Sentinel). Dados efêmeros por design (`appendonly no`).
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

---

**Nota**: Docker Compose dev/test já pré-configura tudo. Não modifique diretamente exceto para staging/prod.
