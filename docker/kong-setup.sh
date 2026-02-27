#!/bin/sh
# ==============================================================================
# kong-setup.sh — Provisionamento completo do Kong via Admin API
#
# Responsabilidades:
#   1. Aguardar Kong Admin API estar disponível
#   2. Aguardar Keycloak estar disponível
#   3. Aplicar configuração declarativa via Admin API (serviços, rotas, plugins)
#   4. Buscar a chave pública RSA do Keycloak via JWKS endpoint
#   5. Criar consumer `keycloak-realm-consumer` e credencial JWT com a chave pública
#
# Dependências: curl, awk, sed, tr, grep (disponíveis na imagem alpine:3.19)
#
# Variáveis de ambiente esperadas (injetadas pelo docker-compose):
#   KONG_ADMIN_URL     — ex: http://kong:8001
#   KEYCLOAK_URL       — ex: http://keycloak:8080
#   KEYCLOAK_REALM     — ex: orderbook-realm
#   KONG_CONSUMER_NAME — ex: keycloak-realm-consumer
# ==============================================================================

set -e

KONG_ADMIN_URL="${KONG_ADMIN_URL:-http://kong:8001}"
KEYCLOAK_URL="${KEYCLOAK_URL:-http://keycloak:8080}"
KEYCLOAK_REALM="${KEYCLOAK_REALM:-orderbook-realm}"
KONG_CONSUMER_NAME="${KONG_CONSUMER_NAME:-keycloak-realm-consumer}"
# O iss do JWT deve bater com a URL que o cliente usa para obter o token.
# Em dev o cliente acessa Keycloak via localhost:8080 (mapeado 8080:8080 no compose).
KEYCLOAK_ISSUER="${KEYCLOAK_ISSUER:-http://localhost:8080/realms/${KEYCLOAK_REALM}}"

JWKS_URL="${KEYCLOAK_URL}/realms/${KEYCLOAK_REALM}/protocol/openid-connect/certs"

echo "=============================================="
echo " Vibranium Kong Setup"
echo " Kong Admin : ${KONG_ADMIN_URL}"
echo " Keycloak   : ${KEYCLOAK_URL} | Realm: ${KEYCLOAK_REALM}"
echo " Issuer JWT : ${KEYCLOAK_ISSUER}"
echo "=============================================="

# ------------------------------------------------------------------------------
# helper: espera URL responder HTTP 200 (máx 60 tentativas × 5s = 5 min)
# ------------------------------------------------------------------------------
wait_for() {
    URL=$1; NAME=$2; MAX=60; N=0
    echo "[wait] Aguardando ${NAME}..."
    while [ $N -lt $MAX ]; do
        CODE=$(curl -s -o /dev/null -w "%{http_code}" "${URL}" 2>/dev/null || echo "000")
        [ "$CODE" = "200" ] && echo "[ok]   ${NAME} pronto (HTTP ${CODE})" && return 0
        N=$((N+1)); echo "[wait] (${N}/${MAX}) HTTP ${CODE} — retry em 5s"; sleep 5
    done
    echo "[erro] ${NAME} indisponível após ${MAX} tentativas"; exit 1
}

# helper: chamada Admin API com log do status HTTP
http_call() {
    METHOD=$1; URL=$2; DATA=$3; LABEL=$4
    CODE=$(curl -s -o /tmp/kong_out.txt -w "%{http_code}" \
        -X "$METHOD" "$URL" -H "Content-Type: application/json" -d "$DATA")
    echo "[kong] ${LABEL}: HTTP ${CODE}"
}

# ==============================================================================
# STEP 1 — Aguardar dependências
# ==============================================================================
wait_for "${KONG_ADMIN_URL}/status"    "Kong Admin API"
wait_for "${KEYCLOAK_URL}/health/live" "Keycloak Health"

# Aguarda realm importado (JWKS disponível)
N=0
while [ $N -lt 30 ]; do
    CODE=$(curl -s -o /dev/null -w "%{http_code}" "${JWKS_URL}" 2>/dev/null || echo "000")
    [ "$CODE" = "200" ] && echo "[ok]   JWKS disponível" && break
    N=$((N+1)); echo "[wait] JWKS não pronto (HTTP ${CODE}) — ${N}/30"; sleep 5
done

# ==============================================================================
# STEP 2 — Service: order-service
# PUT /services/{name} é idempotente no Kong 3.x
# ==============================================================================
echo ""
echo "--- [1/4] Configurando Service order-service ---"
http_call PUT "${KONG_ADMIN_URL}/services/order-service" \
    '{"name":"order-service","url":"http://order-service:8080","connect_timeout":5000,"read_timeout":10000,"write_timeout":10000,"retries":0}' \
    "Service order-service"

# ==============================================================================
# STEP 3 — Route: POST /api/v1/orders
# PUT /services/{svcName}/routes/{routeName} é idempotente no Kong 3.x
# ==============================================================================
echo ""
echo "--- [2/4] Configurando Route place-order-route ---"
http_call PUT "${KONG_ADMIN_URL}/services/order-service/routes/place-order-route" \
    '{"name":"place-order-route","paths":["/api/v1/orders"],"methods":["POST","OPTIONS"],"strip_path":false,"preserve_host":false}' \
    "Route place-order-route"

# ==============================================================================
# STEP 4 — Plugins na Route (jwt + rate-limiting + cors)
# ==============================================================================
echo ""
echo "--- [3/4] Configurando Plugins ---"

# Obtém o ID da route para adicionar plugins
ROUTE_ID=$(curl -s "${KONG_ADMIN_URL}/services/order-service/routes/place-order-route" | \
    grep -o '"id":"[^"]*"' | head -1 | sed 's/"id":"//;s/"//')
echo "[kong] Route ID: ${ROUTE_ID}"

# Remove plugins antigos na route para garantir idempotência
for PNAME in jwt rate-limiting cors; do
    OLD_PID=$(curl -s "${KONG_ADMIN_URL}/routes/${ROUTE_ID}/plugins" | \
        grep -B2 "\"name\":\"${PNAME}\"" | grep '"id"' | head -1 | \
        sed 's/.*"id":"\([^"]*\)".*/\1/')
    if [ -n "$OLD_PID" ]; then
        curl -s -o /dev/null -w "[kong] Remove plugin ${PNAME} antigo: %{http_code}\n" \
            -X DELETE "${KONG_ADMIN_URL}/plugins/${OLD_PID}"
    fi
done

# Plugin JWT
# key_claim_name=iss → Kong localiza o consumer pelo campo `iss` do JWT payload.
# A credencial RSA do consumer é provisionada no STEP 5.
# run_on_preflight=false → permite OPTIONS sem token (CORS preflight não precisa de auth).
http_call POST "${KONG_ADMIN_URL}/routes/${ROUTE_ID}/plugins" \
    '{"name":"jwt","config":{"uri_param_names":[],"cookie_names":[],"header_names":["Authorization"],"claims_to_verify":["exp"],"key_claim_name":"iss","maximum_expiration":3600,"secret_is_base64":false,"run_on_preflight":false}}' \
    "Plugin jwt"

# Plugin Rate-Limiting: 100 req/s por IP, single-node (policy=local).
# Em produção multi-nó, usar policy=redis com cluster Redis compartilhado.
http_call POST "${KONG_ADMIN_URL}/routes/${ROUTE_ID}/plugins" \
    '{"name":"rate-limiting","config":{"second":100,"minute":5000,"policy":"local","limit_by":"ip","hide_client_headers":false,"fault_tolerant":true}}' \
    "Plugin rate-limiting"

# Plugin CORS — permissivo em dev; restringir origins em produção
http_call POST "${KONG_ADMIN_URL}/routes/${ROUTE_ID}/plugins" \
    '{"name":"cors","config":{"origins":["*"],"methods":["GET","POST","OPTIONS"],"headers":["Accept","Authorization","Content-Type","X-Requested-With","X-Correlation-ID"],"exposed_headers":["X-Correlation-ID"],"credentials":false,"max_age":3600,"preflight_continue":false}}' \
    "Plugin cors"

# ==============================================================================
# STEP 5 — Consumer + Credencial JWT RS256 (chave pública Keycloak via JWKS)
# ==============================================================================
echo ""
echo "--- [4/4] Consumer + JWT Credential (Keycloak JWKS) ---"

# Consumer idempotente
http_call PUT "${KONG_ADMIN_URL}/consumers/${KONG_CONSUMER_NAME}" \
    "{\"username\":\"${KONG_CONSUMER_NAME}\",\"tags\":[\"keycloak\",\"rs256\"]}" \
    "Consumer ${KONG_CONSUMER_NAME}"

# Busca campo x5c do JWKS (certificado X.509 base64, suportado pelo Kong JWT plugin)
echo "[keycloak] Buscando JWKS..."
JWKS=$(curl -s "${JWKS_URL}")
X5C=$(echo "$JWKS" | grep -o '"x5c":\["[^"]*"' | head -1 | \
    sed 's|"x5c":\["||;s|"||g')

if [ -z "$X5C" ]; then
    echo "[aviso] Campo x5c ausente no JWKS — configure a chave manualmente:"
    echo "  POST ${KONG_ADMIN_URL}/consumers/${KONG_CONSUMER_NAME}/jwt"
    echo "  { key: \"${KEYCLOAK_ISSUER}\", algorithm: \"RS256\", rsa_public_key: \"<PEM>\" }"
else
    # Remove credencial anterior para evitar conflito de `key` duplicado
    OLD_JWT_ID=$(curl -s "${KONG_ADMIN_URL}/consumers/${KONG_CONSUMER_NAME}/jwt" | \
        grep -o '"id":"[^"]*"' | head -1 | sed 's/"id":"//;s/"//')
    [ -n "$OLD_JWT_ID" ] && \
        curl -s -o /dev/null -w "[kong] Remove JWT antigo: %{http_code}\n" \
            -X DELETE "${KONG_ADMIN_URL}/consumers/${KONG_CONSUMER_NAME}/jwt/${OLD_JWT_ID}"

    # Registra credencial JWT RS256
    # `key` = valor do campo `iss` nos JWTs emitidos pelo Keycloak (deve ser exato)
    PEM="-----BEGIN CERTIFICATE-----\n${X5C}\n-----END CERTIFICATE-----"
    CODE=$(curl -s -o /tmp/jwt_out.txt -w "%{http_code}" \
        -X POST "${KONG_ADMIN_URL}/consumers/${KONG_CONSUMER_NAME}/jwt" \
        -H "Content-Type: application/json" \
        --data-raw "{\"key\":\"${KEYCLOAK_ISSUER}\",\"algorithm\":\"RS256\",\"rsa_public_key\":\"${PEM}\"}")
    if [ "$CODE" = "201" ] || [ "$CODE" = "200" ]; then
        echo "[ok]   Credencial JWT RS256 registrada (HTTP ${CODE})"
    else
        echo "[aviso] JWT credential HTTP ${CODE}:"; cat /tmp/jwt_out.txt; echo ""
    fi
fi

# ==============================================================================
# Sumário final
# ==============================================================================
echo ""
echo "=============================================="
echo " Setup Kong concluído"
printf " Services : "; curl -s "${KONG_ADMIN_URL}/services" | grep -o '"name"' | wc -l | tr -d ' '
printf " Routes   : "; curl -s "${KONG_ADMIN_URL}/routes"   | grep -o '"name"' | wc -l | tr -d ' '
printf " Plugins  : "; curl -s "${KONG_ADMIN_URL}/plugins"  | grep -o '"name"' | wc -l | tr -d ' '
echo "=============================================="
