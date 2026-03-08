#!/bin/bash
# =============================================================================
# REC-5.1 — Validação do docker-compose.perf.flat.yml
# Verifica que o compose de escala horizontal atende a todos os critérios
# de aceite ANTES de executar o benchmark.
#
# Uso: bash tests/performance/validate-perf-flat.sh
# =============================================================================

set -e

COMPOSE="tests/performance/docker-compose.perf.flat.yml"
PASS=0
FAIL=0

check() {
    local desc="$1"
    local result="$2"
    if [ "$result" -eq 0 ]; then
        echo "  [PASS] $desc"
        PASS=$((PASS + 1))
    else
        echo "  [FAIL] $desc"
        FAIL=$((FAIL + 1))
    fi
}

echo "=== Validação docker-compose.perf.flat.yml ==="
echo ""

# 1. Arquivo existe
test -f "$COMPOSE"
check "Arquivo existe" $?

# 2. Sintaxe válida
docker compose -f "$COMPOSE" config > /dev/null 2>&1
check "Sintaxe YAML válida (docker compose config)" $?

# 3. Kong service presente
grep -q "kong:" "$COMPOSE"
check "Kong service presente" $?

# 4. Pelo menos 5 réplicas do order-service
ORDER_COUNT=$(grep -c "order-service-[0-9]:" "$COMPOSE" || true)
[ "$ORDER_COUNT" -ge 5 ]
check "Pelo menos 5 réplicas order-service (encontradas: $ORDER_COUNT)" $?

# 5. Kong upstream configurado para round-robin
grep -q "round-robin" "$COMPOSE"
check "Algoritmo round-robin configurado" $?

# 6. Redis-Kong para rate-limiting distribuído
grep -q "redis-kong" "$COMPOSE"
check "Redis-Kong dedicado presente" $?

# 7. Gatling aponta para Kong (não diretamente ao order-service)
grep -q "TARGET_BASE_URL.*kong:8000" "$COMPOSE"
check "Gatling aponta para Kong (TARGET_BASE_URL)" $?

# 8. Active healthchecks no upstream Kong
grep -q "healthchecks" "$COMPOSE"
check "Active healthchecks configurados no upstream" $?

# 9. JWT env vars presentes no anchor (order-service)
grep -q "SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI" "$COMPOSE"
check "JWT JWK_SET_URI presente" $?

grep -q "SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI" "$COMPOSE"
check "JWT ISSUER_URI presente" $?

# 10. PostgreSQL max_connections >= 300
if grep -qE "max_connections=[3-9][0-9][0-9]" "$COMPOSE"; then
    check "PostgreSQL max_connections >= 300" 0
else
    check "PostgreSQL max_connections >= 300" 1
fi

# 11. Nenhuma porta do order-service exposta ao host
# (order-service-N NÃO deve ter seção "ports:")
PORT_LEAK=$(awk '/order-service-[0-9]:/{found=1} found && /ports:/{print "LEAK"; found=0} found && /^[[:space:]]*[a-z]/{found=0}' "$COMPOSE" | head -1)
[ -z "$PORT_LEAK" ]
check "Order-services NÃO expõem portas ao host" $?

# 12. Rate-limiting policy=redis (não local)
grep -q '"policy":"redis"' "$COMPOSE"
check "Rate-limiting policy=redis (distribuído)" $?

# 13. YAML anchors para evitar duplicação
grep -q "x-order-service-common" "$COMPOSE"
check "YAML anchor x-order-service-common presente" $?

# 14. Resource limits definidos
grep -q "memory: 2G" "$COMPOSE"
check "Resource limits de memória definidos" $?

# 15. Volumes com sufixo -flat
grep -q "_perf_flat_" "$COMPOSE"
check "Volumes com sufixo -flat (isolamento)" $?

# 16. Imagens de produção (Dockerfile, não Dockerfile.dev)
grep -q "dockerfile: apps/order-service/docker/Dockerfile" "$COMPOSE"
check "Imagem de produção order-service (Dockerfile)" $?

echo ""
echo "=== Resultado: $PASS passed, $FAIL failed ==="

if [ "$FAIL" -gt 0 ]; then
    echo "FAIL: $FAIL validações falharam"
    exit 1
else
    echo "PASS: Todas as validações passaram"
    exit 0
fi
