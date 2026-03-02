# Tests — Ambientes de Teste Isolados

Contém os arquivos Docker Compose para execução de testes de integração da plataforma Vibranium.
Os composes foram movidos do diretório legado `docker/` para cá na reestruturação da infra.

## 📁 Estrutura

```
tests/
├── docker-compose.test.yml    # Stack completa isolada (infra + test-runner Maven)
├── docker-compose-test.yml    # Stack mínima (PostgreSQL infra apenas)
└── test-results/              # Relatórios Surefire gerados pelo test-runner
```

## 🧪 Composes disponíveis

### `docker-compose.test.yml` — Stack completa de integração

Sobe toda a infra de dependência em modo isolado (sufixo `-test` em todos os containers/redes/volumes) e executa o `test-runner` (Maven) ao final.

**Serviços:**
| Container | Imagem | Porta |
|-----------|--------|-------|
| `postgresql-test` | postgres:16-alpine | 5433 |
| `redis-test` | redis:7-alpine | 6380 |
| `rabbitmq-test` | rabbitmq:3.13-management-alpine | 5673 / 15673 |
| `mongodb-test` | mongo:7.0 | 27018 |
| `kong-migration-test` | kong:3.4 | — |
| `kong-test` | kong:3.4 | 8101 / 8102 |
| `keycloak-test` | vibranium-keycloak:22.0.5 | 8180 || `kong-init-test` | (build Dockerfile.kong-init) | — |
| `jwks-rotator-test` | vibranium-jwks-rotator:latest | — | ⭐ AT-13.1: sidecar JWKS 30s || `test-runner` | (build do projeto) | — |

**Uso:**
```bash
# Subir apenas a infra (sem o test-runner)
docker compose -f tests/docker-compose.test.yml up -d \
  postgresql-test redis-test rabbitmq-test mongodb-test \
  kong-migration-test kong-test keycloak-test

# Executar teste completo (infra + test-runner)
docker compose -f tests/docker-compose.test.yml up --abort-on-container-exit

# Ver relatórios
ls tests/test-results/
```

### `docker-compose-test.yml` — Stack mínima (PostgreSQL + schemas de infra)

Útil para validar migrações de infra (`kong` e `keycloak` schemas) sem subir toda a stack.

**Uso:**
```bash
docker compose -f tests/docker-compose-test.yml up -d
# Verificar schemas:
docker exec vibranium-postgresql-test psql -U postgres -d vibranium_infra_test \
  -c "\dn"
docker compose -f tests/docker-compose-test.yml down -v
```

## 📝 Scripts de validação manuais

Alternativa ao test-runner Maven para validações de infra:

| Script | Descrição |
|--------|-----------|
| `AT-12.1-rate-limiting-redis-validation.sh` | Valida `policy=redis` em todos os plugins rate-limiting |
| `AT-13.1-jwks-rotation-validation.sh` | ⭐ Valida rotação automática JWKS: artefatos, 401→200, idempotência (10 testes) |

```bash
# Executar validação JWKS rotation (requer infra de teste ativa)
chmod +x tests/AT-13.1-jwks-rotation-validation.sh
./tests/AT-13.1-jwks-rotation-validation.sh

# Override de variáveis caso necessário:
KONG_ADMIN_URL=http://localhost:8001 \
KEYCLOAK_URL=http://localhost:8180 \
./tests/AT-13.1-jwks-rotation-validation.sh
```

## ⚠️ Observações técnicas

- Todos os recursos são **completamente isolados** (rede, volumes, portas diferentes) — podem coexistir com `infra/docker-compose.dev.yml`.
- **MongoDB 7.0**: requer 512M de memória; healthcheck usa `authSource=admin`.
- **Keycloak**: healthcheck usa `bash /dev/tcp` (sem `curl` na imagem Keycloak 22).
- **Kong 3.4**: healthcheck usa `kong health` (sem `curl` na imagem).
- Os relatórios Maven ficam em `tests/test-results/` (montado como volume no `test-runner`).
