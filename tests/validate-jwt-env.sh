#!/bin/bash
# ==============================================================================
# Validação de variáveis de ambiente JWT nos docker-compose de performance.
# Garante que SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI e
# SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI estão presentes
# nos serviços order-service e wallet-service.
#
# Uso:
#   bash tests/validate-jwt-env.sh tests/performance/docker-compose.perf.yml
#   bash tests/validate-jwt-env.sh tests/performance/docker-compose.perf.flat.yml
#
# Exit code 0 = todas as variáveis presentes. Exit code 1 = variável ausente.
# ==============================================================================

set -euo pipefail

COMPOSE_FILE="${1:?Uso: $0 <path-para-docker-compose.yml>}"

if [ ! -f "$COMPOSE_FILE" ]; then
    echo "FAIL: Arquivo não encontrado: $COMPOSE_FILE"
    exit 1
fi

ERRORS=0

check_env() {
    local var="$1"
    local file="$2"
    if ! grep -q "$var" "$file"; then
        echo "FAIL: $var ausente no arquivo $file"
        ERRORS=$((ERRORS + 1))
    fi
}

echo "=== Validação JWT env vars: $COMPOSE_FILE ==="

check_env "SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI" "$COMPOSE_FILE"
check_env "SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI" "$COMPOSE_FILE"

# Verificar que não há tokens hardcoded
if grep -qiE '(eyJ[A-Za-z0-9_-]{10,}\.)' "$COMPOSE_FILE"; then
    echo "FAIL: Token JWT hardcoded detectado em $COMPOSE_FILE"
    ERRORS=$((ERRORS + 1))
fi

if [ "$ERRORS" -gt 0 ]; then
    echo ""
    echo "RESULTADO: $ERRORS erro(s) encontrado(s)"
    exit 1
fi

echo "PASS: Todas as variáveis JWT presentes e nenhum token hardcoded"
exit 0
