#!/usr/bin/env bash
# =============================================================================
# AT-5.1.3-pg-streaming-replication-validation.sh
# Critérios de aceite — PostgreSQL Streaming Replication (Task 5.1.3)
# =============================================================================
# Verifica:
#   ✓ Primary mostra 2 réplicas em pg_stat_replication (streaming)
#   ✓ Réplicas operam em hot_standby (somente leitura)
#   ✓ wallet-service-1/2/3 conectados ao postgres-primary (não às réplicas)
#
# Pré-requisitos:
#   - docker compose -f infra/docker-compose.staging.yml up -d em execução
#   - POSTGRES_PASSWORD definido no ambiente ou .env carregado
#
# Uso:
#   bash tests/AT-5.1.3-pg-streaming-replication-validation.sh
# =============================================================================
set -e

# Cores para output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

PASS=0
FAIL=0

pass() { echo -e "${GREEN}[PASS]${NC} $1"; ((PASS++)); }
fail() { echo -e "${RED}[FAIL]${NC} $1"; ((FAIL++)); }
info() { echo -e "${YELLOW}[INFO]${NC} $1"; }

PG_PRIMARY_CONTAINER="vibranium-postgres-primary"
PG_REPLICA1_CONTAINER="vibranium-postgres-replica-1"
PG_REPLICA2_CONTAINER="vibranium-postgres-replica-2"

# Carrega .env se existir na raiz do projeto
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [ -f "$SCRIPT_DIR/.env" ]; then
    # shellcheck disable=SC1090
    set -a; source "$SCRIPT_DIR/.env"; set +a
fi

PGPASSWORD="${POSTGRES_PASSWORD:?Defina POSTGRES_PASSWORD no .env ou no ambiente}"

pg_exec() {
    local container="$1"
    local query="$2"
    docker exec -e PGPASSWORD="$PGPASSWORD" "$container" \
        psql -U postgres -t -c "$query" 2>/dev/null | tr -d '[:space:]'
}

echo ""
echo "============================================================"
echo "  AT-5.1.3 — PostgreSQL Streaming Replication Validation"
echo "============================================================"
echo ""

# --------------------------------------------------------------------------
# TC-1: Primary deve ter wal_level=replica
# --------------------------------------------------------------------------
info "TC-1: Verificando wal_level no primary..."
WAL_LEVEL=$(pg_exec "$PG_PRIMARY_CONTAINER" "SHOW wal_level;")
if [ "$WAL_LEVEL" = "replica" ]; then
    pass "TC-1: wal_level=$WAL_LEVEL"
else
    fail "TC-1: wal_level='$WAL_LEVEL' (esperado: replica)"
fi

# --------------------------------------------------------------------------
# TC-2: Primary deve mostrar 2 réplicas em pg_stat_replication com state=streaming
# --------------------------------------------------------------------------
info "TC-2: Verificando pg_stat_replication no primary..."
STREAMING_COUNT=$(pg_exec "$PG_PRIMARY_CONTAINER" \
    "SELECT COUNT(*) FROM pg_stat_replication WHERE state = 'streaming';")
if [ "$STREAMING_COUNT" = "2" ]; then
    pass "TC-2: $STREAMING_COUNT réplicas em estado streaming"
else
    fail "TC-2: $STREAMING_COUNT réplica(s) em streaming (esperado: 2)"
fi

# Exibe detalhes para diagnóstico
info "TC-2: Detalhes de pg_stat_replication:"
docker exec -e PGPASSWORD="$PGPASSWORD" "$PG_PRIMARY_CONTAINER" \
    psql -U postgres -c \
    "SELECT client_addr, application_name, state, sync_state, sent_lsn, replay_lsn FROM pg_stat_replication;" \
    2>/dev/null || true

# --------------------------------------------------------------------------
# TC-3: Réplica-1 deve ser hot_standby (recusar writes)
# --------------------------------------------------------------------------
info "TC-3: Verificando hot_standby na réplica-1..."
HOT_STANDBY_R1=$(pg_exec "$PG_REPLICA1_CONTAINER" "SHOW hot_standby;")
if [ "$HOT_STANDBY_R1" = "on" ]; then
    pass "TC-3: hot_standby=$HOT_STANDBY_R1 em postgres-replica-1"
else
    fail "TC-3: hot_standby='$HOT_STANDBY_R1' na réplica-1 (esperado: on)"
fi

# Tenta um write na réplica-1 — deve falhar com erro de read-only
info "TC-3: Testando que réplica-1 rejeita writes..."
WRITE_ERROR=$(docker exec -e PGPASSWORD="$PGPASSWORD" "$PG_REPLICA1_CONTAINER" \
    psql -U postgres -c "CREATE TABLE _rep_test (id int);" 2>&1 | grep -i "read-only" || echo "")
if [ -n "$WRITE_ERROR" ]; then
    pass "TC-3: réplica-1 rejeitou write corretamente (read-only transaction)"
else
    fail "TC-3: réplica-1 aceitou write — hot_standby pode não estar ativo"
fi

# --------------------------------------------------------------------------
# TC-4: Réplica-2 deve ser hot_standby
# --------------------------------------------------------------------------
info "TC-4: Verificando hot_standby na réplica-2..."
HOT_STANDBY_R2=$(pg_exec "$PG_REPLICA2_CONTAINER" "SHOW hot_standby;")
if [ "$HOT_STANDBY_R2" = "on" ]; then
    pass "TC-4: hot_standby=$HOT_STANDBY_R2 em postgres-replica-2"
else
    fail "TC-4: hot_standby='$HOT_STANDBY_R2' na réplica-2 (esperado: on)"
fi

WRITE_ERROR_R2=$(docker exec -e PGPASSWORD="$PGPASSWORD" "$PG_REPLICA2_CONTAINER" \
    psql -U postgres -c "CREATE TABLE _rep_test (id int);" 2>&1 | grep -i "read-only" || echo "")
if [ -n "$WRITE_ERROR_R2" ]; then
    pass "TC-4: réplica-2 rejeitou write corretamente (read-only transaction)"
else
    fail "TC-4: réplica-2 aceitou write — hot_standby pode não estar ativo"
fi

# --------------------------------------------------------------------------
# TC-5: wallet-service-1/2/3 devem apontar para o primary (não réplicas)
# --------------------------------------------------------------------------
info "TC-5: Verificando connection strings dos wallet-services..."
for SVC in wallet-service-1 wallet-service-2 wallet-service-3; do
    CONTAINER="vibranium-$SVC"
    # Extrai SPRING_DATASOURCE_URL da env do container em execução
    DS_URL=$(docker inspect "$CONTAINER" \
        --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null \
        | grep "SPRING_DATASOURCE_URL" | cut -d= -f2-) || DS_URL=""

    if echo "$DS_URL" | grep -q "postgres-primary"; then
        pass "TC-5: $SVC → $DS_URL"
    elif [ -z "$DS_URL" ]; then
        fail "TC-5: $SVC — container não encontrado ou não está rodando"
    else
        fail "TC-5: $SVC → $DS_URL (esperado: postgres-primary)"
    fi
done

# --------------------------------------------------------------------------
# Resumo
# --------------------------------------------------------------------------
echo ""
echo "============================================================"
echo -e "  Resultado: ${GREEN}$PASS PASS${NC} / ${RED}$FAIL FAIL${NC}"
echo "============================================================"
echo ""

if [ "$FAIL" -gt 0 ]; then
    echo -e "${RED}Validação falhou. Verifique os itens [FAIL] acima.${NC}"
    exit 1
else
    echo -e "${GREEN}Todos os critérios de aceite da task 5.1.3 foram satisfeitos.${NC}"
    exit 0
fi
