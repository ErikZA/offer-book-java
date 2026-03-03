# Tests — Validação da Plataforma Vibranium

Contém scripts de validação de infra e o módulo Maven de testes E2E cross-service.

## Estrutura

```
tests/
├── e2e/                                              # Módulo Maven — suíte E2E (AT-5.3.1)
│   ├── pom.xml                                       # Dependências: Testcontainers, RestAssured, Awaitility
│   ├── docker-compose.e2e.yml                        # Ambiente E2E completo (sem Keycloak/Kong/Jaeger)
│   └── src/test/java/com/vibranium/e2e/
│       └── SagaEndToEndIT.java                       # Happy path + Timeout path da Saga
├── AT-12.1-rate-limiting-redis-validation.sh         # AT-12.1: Rate Limiting via Redis no Kong
├── AT-13.1-jwks-rotation-validation.sh              # AT-13.1: Rotação JWKS do Keycloak → Kong
├── AT-5.1.3-pg-streaming-replication-validation.sh  # AT-5.1.3: PostgreSQL Streaming Replication
└── README.md
```

---

## Suíte E2E — `tests/e2e/` (AT-5.3.1)

### O que valida

| Teste | Fluxo | Resultado esperado |
|-------|-------|--------------------|
| `happyPath_buyAndSellFilled` | BUY + SELL ao mesmo preço/quantidade | Ambas as ordens `FILLED` |
| `timeoutPath_cancelledByCleanupJob` | BUY sem contraparte | Ordem `CANCELLED` após 1 min (Saga timeout) |

### Como funciona

O `SagaEndToEndIT.java` usa `DockerComposeContainer` (Testcontainers) para subir o
`docker-compose.e2e.yml`, que orquestra toda a infra + ambos os serviços com
`SPRING_PROFILES_ACTIVE=e2e`.

O perfil `e2e` ativa:
- **`E2eSecurityConfig`** — `JwtDecoder` que parseia JWTs sem validar assinatura
  (sem necessidade de Keycloak nos testes)
- **`E2eDataSeederController`** — endpoints `/e2e/setup/users` e `/e2e/setup/wallets`
  para pré-configurar dados de teste via `@BeforeAll`

### Pré-requisitos

- Docker Engine acessível (`docker info` sem erro)
- Porta 8080 (order-service) e 8081 (wallet-service) livres ou mapeamento automático via Testcontainers
- Primeira execução: ~5–8 min (build das imagens); subsequentes: ~2–3 min (cache Docker)

### Execução

```bash
# 1. Instalar todos os artefatos no ~/.m2 (sem rodar testes)
mvn clean install -DskipTests

# 2. Executar suíte E2E completa
mvn verify -pl tests/e2e --no-transfer-progress

# Apenas compilar (verificar erros de sintaxe sem subir Docker)
mvn test-compile -pl tests/e2e --no-transfer-progress
```

### Ambiente Docker (`docker-compose.e2e.yml`)

| Serviço | Imagem | Função |
|---------|--------|--------|
| `postgres-orders-e2e` | postgres:16-alpine | Banco do order-service |
| `postgres-wallet-e2e` | postgres:16-alpine | Banco do wallet-service |
| `mongodb-e2e` | mongo:7.0 | Event Store + Read Model (replica set rs0, sem auth) |
| `mongo-rs-init-e2e` | mongo:7.0 | Inicializa `rs.initiate()` e encerra |
| `redis-e2e` | redis:7-alpine | Motor de Match (script Lua) |
| `rabbitmq-e2e` | rabbitmq:3.13-management-alpine | Message broker da Saga |
| `order-service-e2e` | build local | Order Service com perfil `e2e` |
| `wallet-service-e2e` | build local | Wallet Service com perfil `e2e` |

Subir manualmente (sem os testes):
```bash
docker compose -f tests/e2e/docker-compose.e2e.yml up -d
docker compose -f tests/e2e/docker-compose.e2e.yml down -v
```

---

## Scripts de validação manual

Alternativa ao módulo Maven para validações de infra isoladas (requerem infra ativa):

| Script | Descrição |
|--------|-----------|
| `AT-12.1-rate-limiting-redis-validation.sh` | Valida `policy=redis` em todos os plugins rate-limiting do Kong |
| `AT-13.1-jwks-rotation-validation.sh` | Valida rotação automática JWKS: artefatos, 401→200, idempotência (10 testes) |
| `AT-5.1.3-pg-streaming-replication-validation.sh` | Valida PostgreSQL Streaming Replication: `wal_level`, `pg_stat_replication`, hot standby (5 testes) |

```bash
chmod +x tests/AT-13.1-jwks-rotation-validation.sh
./tests/AT-13.1-jwks-rotation-validation.sh

# Override de variáveis caso necessário:
KONG_ADMIN_URL=http://localhost:8001 \
KEYCLOAK_URL=http://localhost:8180 \
./tests/AT-13.1-jwks-rotation-validation.sh
```

./tests/AT-13.1-jwks-rotation-validation.sh

# Executar validação PostgreSQL Streaming Replication (requer staging ativo)
# Pré-requisito: docker compose -f infra/docker-compose.staging.yml up -d
chmod +x tests/AT-5.1.3-pg-streaming-replication-validation.sh
./tests/AT-5.1.3-pg-streaming-replication-validation.sh
```

## ⚠️ Observações técnicas

- Todos os recursos são **completamente isolados** (rede, volumes, portas diferentes) — podem coexistir com `infra/docker-compose.dev.yml`.
- **MongoDB 7.0**: requer 512M de memória; healthcheck usa `authSource=admin`.
- **Keycloak**: healthcheck usa `bash /dev/tcp` (sem `curl` na imagem Keycloak 22).
- **Kong 3.4**: healthcheck usa `kong health` (sem `curl` na imagem).
- Os relatórios Maven ficam em `tests/test-results/` (montado como volume no `test-runner`).
