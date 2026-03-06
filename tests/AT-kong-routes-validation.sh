#!/bin/bash
# ==============================================================================
# AT-kong-routes-validation.sh — Validação de rotas Kong (Admin API)
#
# Verifica que TODAS as rotas esperadas estão configuradas no Kong com os
# plugins corretos (jwt, rate-limiting, cors) e rate-limits adequados.
#
# Uso:
#   bash tests/AT-kong-routes-validation.sh
#   KONG_ADMIN_URL=http://kong:8001 bash tests/AT-kong-routes-validation.sh
#   KONG_PROXY_URL=http://localhost:8000 bash tests/AT-kong-routes-validation.sh
#
# Saída: PASS/FAIL por rota e plugin
# ==============================================================================

set -u

KONG_ADMIN_URL="${KONG_ADMIN_URL:-http://localhost:8001}"
KONG_PROXY_URL="${KONG_PROXY_URL:-http://localhost:8000}"

PASS_COUNT=0
FAIL_COUNT=0

pass() {
    echo "  [PASS] $1"
    PASS_COUNT=$((PASS_COUNT + 1))
}

fail() {
    echo "  [FAIL] $1"
    FAIL_COUNT=$((FAIL_COUNT + 1))
}

check_route_exists() {
    local SERVICE=$1
    local ROUTE_NAME=$2
    local EXPECTED_METHODS=$3  # comma-separated, e.g. "GET,OPTIONS"

    ROUTE_JSON=$(curl -s "${KONG_ADMIN_URL}/services/${SERVICE}/routes/${ROUTE_NAME}" 2>/dev/null)
    ROUTE_ID=$(echo "$ROUTE_JSON" | grep -o '"id":"[^"]*"' | head -1 | sed 's/"id":"//;s/"//')

    if [ -z "$ROUTE_ID" ]; then
        fail "Route '${ROUTE_NAME}' on service '${SERVICE}' — NOT FOUND"
        return 1
    fi

    pass "Route '${ROUTE_NAME}' exists (id: ${ROUTE_ID})"

    # Verifica strip_path: false
    STRIP=$(echo "$ROUTE_JSON" | grep -o '"strip_path":[a-z]*' | head -1 | sed 's/"strip_path"://')
    if [ "$STRIP" = "false" ]; then
        pass "Route '${ROUTE_NAME}' strip_path=false"
    else
        fail "Route '${ROUTE_NAME}' strip_path expected false, got '${STRIP}'"
    fi

    # Verifica métodos
    for METHOD in $(echo "$EXPECTED_METHODS" | tr ',' ' '); do
        if echo "$ROUTE_JSON" | grep -q "\"${METHOD}\""; then
            pass "Route '${ROUTE_NAME}' has method ${METHOD}"
        else
            fail "Route '${ROUTE_NAME}' missing method ${METHOD}"
        fi
    done

    echo "$ROUTE_ID"
}

check_plugin_on_route() {
    local ROUTE_ID=$1
    local ROUTE_NAME=$2
    local PLUGIN_NAME=$3

    PLUGINS_JSON=$(curl -s "${KONG_ADMIN_URL}/routes/${ROUTE_ID}/plugins" 2>/dev/null)

    if echo "$PLUGINS_JSON" | grep -q "\"name\":\"${PLUGIN_NAME}\""; then
        pass "Route '${ROUTE_NAME}' has plugin '${PLUGIN_NAME}'"
        return 0
    else
        fail "Route '${ROUTE_NAME}' missing plugin '${PLUGIN_NAME}'"
        return 1
    fi
}

check_rate_limit_config() {
    local ROUTE_ID=$1
    local ROUTE_NAME=$2
    local EXPECTED_SECOND=$3
    local EXPECTED_MINUTE=$4

    PLUGINS_JSON=$(curl -s "${KONG_ADMIN_URL}/routes/${ROUTE_ID}/plugins" 2>/dev/null)

    # Extract rate-limiting plugin config
    # Use a simple approach: find the line with rate-limiting and extract second/minute
    RL_SECTION=$(echo "$PLUGINS_JSON" | tr ',' '\n' | grep -A50 '"name":"rate-limiting"')

    ACTUAL_SECOND=$(echo "$RL_SECTION" | grep -o '"second":[0-9]*' | head -1 | sed 's/"second"://')
    ACTUAL_MINUTE=$(echo "$RL_SECTION" | grep -o '"minute":[0-9]*' | head -1 | sed 's/"minute"://')

    if [ "$ACTUAL_SECOND" = "$EXPECTED_SECOND" ]; then
        pass "Route '${ROUTE_NAME}' rate-limit second=${ACTUAL_SECOND}"
    else
        fail "Route '${ROUTE_NAME}' rate-limit second expected ${EXPECTED_SECOND}, got '${ACTUAL_SECOND}'"
    fi

    if [ "$ACTUAL_MINUTE" = "$EXPECTED_MINUTE" ]; then
        pass "Route '${ROUTE_NAME}' rate-limit minute=${ACTUAL_MINUTE}"
    else
        fail "Route '${ROUTE_NAME}' rate-limit minute expected ${EXPECTED_MINUTE}, got '${ACTUAL_MINUTE}'"
    fi
}

check_jwt_run_on_preflight() {
    local ROUTE_ID=$1
    local ROUTE_NAME=$2

    PLUGINS_JSON=$(curl -s "${KONG_ADMIN_URL}/routes/${ROUTE_ID}/plugins" 2>/dev/null)
    JWT_SECTION=$(echo "$PLUGINS_JSON" | tr ',' '\n' | grep -A30 '"name":"jwt"')

    if echo "$JWT_SECTION" | grep -q '"run_on_preflight":false'; then
        pass "Route '${ROUTE_NAME}' jwt run_on_preflight=false"
    else
        fail "Route '${ROUTE_NAME}' jwt run_on_preflight expected false"
    fi
}

check_proxy_returns_401() {
    local PATH=$1
    local METHOD=$2
    local LABEL=$3

    CODE=$(curl -s -o /dev/null -w "%{http_code}" -X "$METHOD" "${KONG_PROXY_URL}${PATH}" 2>/dev/null || echo "000")

    if [ "$CODE" = "401" ]; then
        pass "Proxy ${METHOD} ${PATH} → HTTP 401 (JWT required) [${LABEL}]"
    elif [ "$CODE" = "000" ]; then
        fail "Proxy ${METHOD} ${PATH} → connection refused (Kong proxy not reachable) [${LABEL}]"
    else
        fail "Proxy ${METHOD} ${PATH} → HTTP ${CODE} (expected 401) [${LABEL}]"
    fi
}

# ==============================================================================
echo "=============================================="
echo " Kong Routes Validation"
echo " Admin API : ${KONG_ADMIN_URL}"
echo " Proxy URL : ${KONG_PROXY_URL}"
echo "=============================================="

# --- Verificar que o Kong Admin está disponível ---
echo ""
echo "--- Verificando Kong Admin API ---"
ADMIN_CODE=$(curl -s -o /dev/null -w "%{http_code}" "${KONG_ADMIN_URL}/status" 2>/dev/null || echo "000")
if [ "$ADMIN_CODE" = "200" ]; then
    pass "Kong Admin API disponível (HTTP ${ADMIN_CODE})"
else
    fail "Kong Admin API indisponível (HTTP ${ADMIN_CODE}) — abortando validação de Admin API"
    echo ""
    echo "  Pulando verificações via Admin API. Executando apenas testes de proxy..."
    SKIP_ADMIN=true
fi

# ==============================================================================
# SEÇÃO 1: Validação via Admin API (rotas + plugins)
# ==============================================================================
if [ "${SKIP_ADMIN:-false}" = "false" ]; then

    # --- order-service routes ---
    echo ""
    echo "--- [1/5] Route: place-order-route (POST /api/v1/orders) ---"
    ROUTE_ID=$(check_route_exists "order-service" "place-order-route" "POST,OPTIONS")
    if [ -n "$ROUTE_ID" ] && [ "$ROUTE_ID" != "1" ]; then
        check_plugin_on_route "$ROUTE_ID" "place-order-route" "jwt"
        check_plugin_on_route "$ROUTE_ID" "place-order-route" "rate-limiting"
        check_plugin_on_route "$ROUTE_ID" "place-order-route" "cors"
        check_rate_limit_config "$ROUTE_ID" "place-order-route" "100" "5000"
        check_jwt_run_on_preflight "$ROUTE_ID" "place-order-route"
    fi

    echo ""
    echo "--- [2/5] Route: list-orders-route (GET /api/v1/orders) ---"
    ROUTE_ID=$(check_route_exists "order-service" "list-orders-route" "GET,OPTIONS")
    if [ -n "$ROUTE_ID" ] && [ "$ROUTE_ID" != "1" ]; then
        check_plugin_on_route "$ROUTE_ID" "list-orders-route" "jwt"
        check_plugin_on_route "$ROUTE_ID" "list-orders-route" "rate-limiting"
        check_plugin_on_route "$ROUTE_ID" "list-orders-route" "cors"
        check_rate_limit_config "$ROUTE_ID" "list-orders-route" "200" "10000"
        check_jwt_run_on_preflight "$ROUTE_ID" "list-orders-route"
    fi

    echo ""
    echo "--- [3/5] Route: get-order-by-id-route (GET /api/v1/orders/{orderId}) ---"
    ROUTE_ID=$(check_route_exists "order-service" "get-order-by-id-route" "GET,OPTIONS")
    if [ -n "$ROUTE_ID" ] && [ "$ROUTE_ID" != "1" ]; then
        check_plugin_on_route "$ROUTE_ID" "get-order-by-id-route" "jwt"
        check_plugin_on_route "$ROUTE_ID" "get-order-by-id-route" "rate-limiting"
        check_plugin_on_route "$ROUTE_ID" "get-order-by-id-route" "cors"
        check_rate_limit_config "$ROUTE_ID" "get-order-by-id-route" "200" "10000"
        check_jwt_run_on_preflight "$ROUTE_ID" "get-order-by-id-route"
    fi

    echo ""
    echo "--- [4/5] Route: get-wallet-route (GET /api/v1/wallets) ---"
    ROUTE_ID=$(check_route_exists "wallet-service" "get-wallet-route" "GET,OPTIONS")
    if [ -n "$ROUTE_ID" ] && [ "$ROUTE_ID" != "1" ]; then
        check_plugin_on_route "$ROUTE_ID" "get-wallet-route" "jwt"
        check_plugin_on_route "$ROUTE_ID" "get-wallet-route" "rate-limiting"
        check_plugin_on_route "$ROUTE_ID" "get-wallet-route" "cors"
        check_rate_limit_config "$ROUTE_ID" "get-wallet-route" "200" "10000"
        check_jwt_run_on_preflight "$ROUTE_ID" "get-wallet-route"
    fi

    echo ""
    echo "--- [5/5] Route: update-wallet-balance-route (PATCH /api/v1/wallets/{id}/balance) ---"
    ROUTE_ID=$(check_route_exists "wallet-service" "update-wallet-balance-route" "PATCH,OPTIONS")
    if [ -n "$ROUTE_ID" ] && [ "$ROUTE_ID" != "1" ]; then
        check_plugin_on_route "$ROUTE_ID" "update-wallet-balance-route" "jwt"
        check_plugin_on_route "$ROUTE_ID" "update-wallet-balance-route" "rate-limiting"
        check_plugin_on_route "$ROUTE_ID" "update-wallet-balance-route" "cors"
        check_rate_limit_config "$ROUTE_ID" "update-wallet-balance-route" "50" "2000"
        check_jwt_run_on_preflight "$ROUTE_ID" "update-wallet-balance-route"
    fi

fi

# ==============================================================================
# SEÇÃO 2: Validação via Proxy (deve retornar 401 sem JWT)
# ==============================================================================
echo ""
echo "--- Validação via Proxy (sem JWT → espera 401) ---"
check_proxy_returns_401 "/api/v1/orders" "GET" "list-orders"
check_proxy_returns_401 "/api/v1/orders/550e8400-e29b-41d4-a716-446655440000" "GET" "get-order-by-id"
check_proxy_returns_401 "/api/v1/orders" "POST" "place-order"
check_proxy_returns_401 "/api/v1/wallets/550e8400-e29b-41d4-a716-446655440000" "GET" "get-wallet"
check_proxy_returns_401 "/api/v1/wallets/550e8400-e29b-41d4-a716-446655440000/balance" "PATCH" "update-wallet-balance"

# ==============================================================================
# Sumário
# ==============================================================================
echo ""
echo "=============================================="
echo " Resultado: ${PASS_COUNT} PASS | ${FAIL_COUNT} FAIL"
echo "=============================================="

if [ "$FAIL_COUNT" -gt 0 ]; then
    exit 1
else
    echo " Todas as validações passaram!"
    exit 0
fi
