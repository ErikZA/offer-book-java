#!/bin/bash
# ==============================================================================
# AT-04 — Validação de autenticação Redis
#
# Testa que os containers Redis exigem autenticação (requirepass) e que:
#   1. redis-cli -a $REDIS_PASSWORD ping → PONG
#   2. redis-cli ping (sem senha) → NOAUTH
#   3. redis-cli -a wrong_password ping → ERR invalid password
#
# Uso:
#   ./tests/AT-04-redis-auth-validation.sh
#
# Pré-requisitos:
#   - docker compose -f infra/docker-compose.dev.yml --env-file .env up -d
#   - Variáveis REDIS_PASSWORD e REDIS_KONG_PASSWORD definidas no .env
# ==============================================================================

set -euo pipefail

# Carrega variáveis do .env se existir
if [ -f .env ]; then
    # shellcheck disable=SC1091
    set -a; source .env; set +a
fi

REDIS_PASSWORD="${REDIS_PASSWORD:?Variável REDIS_PASSWORD não definida. Copie .env.example para .env}"
REDIS_KONG_PASSWORD="${REDIS_KONG_PASSWORD:?Variável REDIS_KONG_PASSWORD não definida. Copie .env.example para .env}"

PASS=0
FAIL=0

check() {
    local LABEL=$1; local EXPECTED=$2; local ACTUAL=$3
    if echo "$ACTUAL" | grep -q "$EXPECTED"; then
        echo "[PASS] $LABEL"
        PASS=$((PASS + 1))
    else
        echo "[FAIL] $LABEL — esperado: '$EXPECTED', obtido: '$ACTUAL'"
        FAIL=$((FAIL + 1))
    fi
}

echo "=============================================="
echo " AT-04: Redis Authentication Validation"
echo "=============================================="
echo ""

# --- Redis da aplicação (vibranium-redis) ---
echo "--- Redis Aplicação (vibranium-redis) ---"

RESULT=$(docker exec vibranium-redis redis-cli -a "$REDIS_PASSWORD" ping 2>&1 || true)
check "Redis app: senha correta → PONG" "PONG" "$RESULT"

RESULT=$(docker exec vibranium-redis redis-cli ping 2>&1 || true)
check "Redis app: sem senha → NOAUTH" "NOAUTH" "$RESULT"

RESULT=$(docker exec vibranium-redis redis-cli -a "wrong_password" ping 2>&1 || true)
check "Redis app: senha errada → ERR" "ERR" "$RESULT"

echo ""

# --- Redis do Kong (vibranium-redis-kong-dev) ---
echo "--- Redis Kong (vibranium-redis-kong-dev) ---"

RESULT=$(docker exec vibranium-redis-kong-dev redis-cli -a "$REDIS_KONG_PASSWORD" ping 2>&1 || true)
check "Redis Kong: senha correta → PONG" "PONG" "$RESULT"

RESULT=$(docker exec vibranium-redis-kong-dev redis-cli ping 2>&1 || true)
check "Redis Kong: sem senha → NOAUTH" "NOAUTH" "$RESULT"

RESULT=$(docker exec vibranium-redis-kong-dev redis-cli -a "wrong_password" ping 2>&1 || true)
check "Redis Kong: senha errada → ERR" "ERR" "$RESULT"

echo ""
echo "=============================================="
echo " Resultado: ${PASS} passed, ${FAIL} failed"
echo "=============================================="

[ "$FAIL" -eq 0 ] && exit 0 || exit 1
