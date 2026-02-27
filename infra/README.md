# Infrastructure

Configurações de infraestrutura centralizada da plataforma Vibranium.
Os arquivos Docker Compose foram reorganizados do diretório legado `docker/` para cá.

## 📁 Estrutura

```
infra/
├── docker-compose.yml          # Infra-only (Kong + Keycloak + PostgreSQL + RabbitMQ)
├── docker-compose.dev.yml      # Dev completo (infra + order-service + wallet-service hot-reload)
├── docker-compose.staging.yml  # Staging com réplicas (3× MongoDB, PostgreSQL, Redis, RabbitMQ)
├── docker/
│   ├── Dockerfile              # Imagem base para apps (build multi-stage Maven)
│   ├── Dockerfile.keycloak     # Keycloak 22 + plugin keycloak-to-rabbit-3.0.5.jar
│   │                           # Build com: --db=postgres --health-enabled=true
│   └── Dockerfile.kong-init    # Kong-init: deck sync de configuração declarativa
├── keycloak/
│   ├── realm-export.json       # Realm "vibranium" (clientes, roles, mappers)
│   ├── keycloak-setup.sh       # Provisionamento pós-subida (usuários, grupos)
│   └── keycloak-to-rabbit-3.0.5.jar  # Plugin: eventos Keycloak → RabbitMQ
├── kong/
│   ├── kong-init.yml           # Configuração declarativa (services, routes, plugins)
│   ├── kong-setup.sh           # Provisiona consumer JWT + credential Keycloak JWKS
│   └── kong-config.md          # Documentação das rotas configuradas
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
docker compose -f infra/docker-compose.dev.yml up -d postgres redis rabbitmq mongodb keycloak-db keycloak kong
```

### Infra isolada (sem aplicações)
```bash
docker compose -f infra/docker-compose.yml up -d
```

### Staging (réplicas)
```bash
# Apenas infra base (recomendado para validação)
docker compose -f infra/docker-compose.staging.yml up -d \
  mongodb-1 postgres-primary redis-1 rabbitmq-1 keycloak-db keycloak kong-database kong-migration kong

# Stack completo (requer imagens pré-buildadas: order-service:latest, wallet-service:latest)
docker compose -f infra/docker-compose.staging.yml up -d
```

## 🐳 Portas expostas (dev)

| Serviço     | Porta |
|-------------|-------|
| PostgreSQL  | 5432  |
| MongoDB     | 27017 |
| Redis       | 6379  |
| RabbitMQ    | 5672 / 15672 (UI) |
| Keycloak    | 8080  |
| Kong Proxy  | 8000 / 8443 |
| Kong Admin  | 8001 / 8444 |

## ⚠️ Observações técnicas

- **Keycloak**: imagem customizada com `keycloak-to-rabbit-3.0.5.jar` compilada via `kc.sh build --db=postgres --health-enabled=true`. O modo `start --optimized` (staging) exige essas flags no build-time.
- **Kong 3.4**: não possui `curl` na imagem — healthcheck usa `kong health`.
- **MongoDB 7.0**: requer mínimo 512M de memória; healthcheck deve incluir `authSource=admin`.
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
