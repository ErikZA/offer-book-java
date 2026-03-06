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
# STEP 3 — Route: POST /api/v1/orders (Commands — escrita)
# PUT /services/{svcName}/routes/{routeName} é idempotente no Kong 3.x
# ==============================================================================
echo ""
echo "--- [2/4] Configurando Route place-order-route ---"
http_call PUT "${KONG_ADMIN_URL}/services/order-service/routes/place-order-route" \
    '{"name":"place-order-route","paths":["/api/v1/orders"],"methods":["POST","OPTIONS"],"strip_path":false,"preserve_host":false}' \
    "Route place-order-route"

# ==============================================================================
# STEP 3b — Route: GET /api/v1/orders (Query Side CQRS — lista paginada)
# ==============================================================================
echo ""
echo "--- [2b/4] Configurando Route list-orders-route ---"
http_call PUT "${KONG_ADMIN_URL}/services/order-service/routes/list-orders-route" \
    '{"name":"list-orders-route","paths":["/api/v1/orders"],"methods":["GET","OPTIONS"],"strip_path":false,"preserve_host":false}' \
    "Route list-orders-route"

# ==============================================================================
# STEP 3c — Route: GET /api/v1/orders/{orderId} (Query Side CQRS — detalhe)
# Regex path: ~/api/v1/orders/[^/]+$ captura UUID após /api/v1/orders/
# ==============================================================================
echo ""
echo "--- [2c/4] Configurando Route get-order-by-id-route ---"
http_call PUT "${KONG_ADMIN_URL}/services/order-service/routes/get-order-by-id-route" \
    '{"name":"get-order-by-id-route","paths":["~/api/v1/orders/[^/]+$"],"methods":["GET","OPTIONS"],"strip_path":false,"preserve_host":false}' \
    "Route get-order-by-id-route"

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

# Plugin Rate-Limiting: 100 req/s por IP.
# policy=redis: contador global compartilhado via Redis (redis-kong, db=1).
# Em cluster Kong, garante que o limite seja efetivo independente do nó que recebe a req.
http_call POST "${KONG_ADMIN_URL}/routes/${ROUTE_ID}/plugins" \
    '{"name":"rate-limiting","config":{"second":100,"minute":5000,"policy":"redis","redis_host":"redis-kong","redis_port":6379,"redis_database":1,"limit_by":"ip","hide_client_headers":false,"fault_tolerant":true}}' \
    "Plugin rate-limiting"

# Plugin CORS — permissivo em dev; restringir origins em produção
http_call POST "${KONG_ADMIN_URL}/routes/${ROUTE_ID}/plugins" \
    '{"name":"cors","config":{"origins":["*"],"methods":["GET","POST","OPTIONS"],"headers":["Accept","Authorization","Content-Type","X-Requested-With","X-Correlation-ID"],"exposed_headers":["X-Correlation-ID"],"credentials":false,"max_age":3600,"preflight_continue":false}}' \
    "Plugin cors"

# ==============================================================================
# STEP 4a — Plugins na Route list-orders-route (jwt + rate-limiting 200 + cors)
# Rate-limiting de leitura: 200 req/s (mais permissivo que escrita)
# ==============================================================================
echo ""
echo "--- [3a/4] Configurando Plugins list-orders-route ---"

LIST_ROUTE_ID=$(curl -s "${KONG_ADMIN_URL}/services/order-service/routes/list-orders-route" | \
    grep -o '"id":"[^"]*"' | head -1 | sed 's/"id":"//;s/"//')
echo "[kong] list-orders-route ID: ${LIST_ROUTE_ID}"

for PNAME in jwt rate-limiting cors; do
    OLD_PID=$(curl -s "${KONG_ADMIN_URL}/routes/${LIST_ROUTE_ID}/plugins" | \
        grep -B2 "\"name\":\"${PNAME}\"" | grep '"id"' | head -1 | \
        sed 's/.*"id":"\([^"]*\)".*/\1/')
    [ -n "$OLD_PID" ] && curl -s -o /dev/null \
        -X DELETE "${KONG_ADMIN_URL}/plugins/${OLD_PID}"
done

http_call POST "${KONG_ADMIN_URL}/routes/${LIST_ROUTE_ID}/plugins" \
    '{"name":"jwt","config":{"uri_param_names":[],"cookie_names":[],"header_names":["Authorization"],"claims_to_verify":["exp"],"key_claim_name":"iss","maximum_expiration":3600,"secret_is_base64":false,"run_on_preflight":false}}' \
    "Plugin jwt (list-orders)"

http_call POST "${KONG_ADMIN_URL}/routes/${LIST_ROUTE_ID}/plugins" \
    '{"name":"rate-limiting","config":{"second":200,"minute":10000,"policy":"redis","redis_host":"redis-kong","redis_port":6379,"redis_database":1,"limit_by":"ip","hide_client_headers":false,"fault_tolerant":true}}' \
    "Plugin rate-limiting (list-orders)"

http_call POST "${KONG_ADMIN_URL}/routes/${LIST_ROUTE_ID}/plugins" \
    '{"name":"cors","config":{"origins":["*"],"methods":["GET","OPTIONS"],"headers":["Accept","Authorization","Content-Type","X-Requested-With","X-Correlation-ID"],"exposed_headers":["X-Correlation-ID"],"credentials":false,"max_age":3600,"preflight_continue":false}}' \
    "Plugin cors (list-orders)"

# ==============================================================================
# STEP 4a2 — Plugins na Route get-order-by-id-route (jwt + rate-limiting 200 + cors)
# ==============================================================================
echo ""
echo "--- [3a2/4] Configurando Plugins get-order-by-id-route ---"

GETBYID_ROUTE_ID=$(curl -s "${KONG_ADMIN_URL}/services/order-service/routes/get-order-by-id-route" | \
    grep -o '"id":"[^"]*"' | head -1 | sed 's/"id":"//;s/"//')
echo "[kong] get-order-by-id-route ID: ${GETBYID_ROUTE_ID}"

for PNAME in jwt rate-limiting cors; do
    OLD_PID=$(curl -s "${KONG_ADMIN_URL}/routes/${GETBYID_ROUTE_ID}/plugins" | \
        grep -B2 "\"name\":\"${PNAME}\"" | grep '"id"' | head -1 | \
        sed 's/.*"id":"\([^"]*\)".*/\1/')
    [ -n "$OLD_PID" ] && curl -s -o /dev/null \
        -X DELETE "${KONG_ADMIN_URL}/plugins/${OLD_PID}"
done

http_call POST "${KONG_ADMIN_URL}/routes/${GETBYID_ROUTE_ID}/plugins" \
    '{"name":"jwt","config":{"uri_param_names":[],"cookie_names":[],"header_names":["Authorization"],"claims_to_verify":["exp"],"key_claim_name":"iss","maximum_expiration":3600,"secret_is_base64":false,"run_on_preflight":false}}' \
    "Plugin jwt (get-order-by-id)"

http_call POST "${KONG_ADMIN_URL}/routes/${GETBYID_ROUTE_ID}/plugins" \
    '{"name":"rate-limiting","config":{"second":200,"minute":10000,"policy":"redis","redis_host":"redis-kong","redis_port":6379,"redis_database":1,"limit_by":"ip","hide_client_headers":false,"fault_tolerant":true}}' \
    "Plugin rate-limiting (get-order-by-id)"

http_call POST "${KONG_ADMIN_URL}/routes/${GETBYID_ROUTE_ID}/plugins" \
    '{"name":"cors","config":{"origins":["*"],"methods":["GET","OPTIONS"],"headers":["Accept","Authorization","Content-Type","X-Requested-With","X-Correlation-ID"],"exposed_headers":["X-Correlation-ID"],"credentials":false,"max_age":3600,"preflight_continue":false}}' \
    "Plugin cors (get-order-by-id)"

# ==============================================================================
# STEP 4b — Service + Routes: wallet-service
# ==============================================================================
echo ""
echo "--- [3b/4] Configurando wallet-service ---"

# Service: wallet-service
http_call PUT "${KONG_ADMIN_URL}/services/wallet-service" \
    '{"name":"wallet-service","url":"http://wallet-service:8081","connect_timeout":5000,"read_timeout":10000,"write_timeout":10000,"retries":0}' \
    "Service wallet-service"

# Route: GET /api/v1/wallets (listagem/consulta de carteira por userId)
# Aceita GET e OPTIONS (preflight CORS). O userId é passado como path param
# ou query param dependendo do design da API.
http_call PUT "${KONG_ADMIN_URL}/services/wallet-service/routes/get-wallet-route" \
    '{"name":"get-wallet-route","paths":["/api/v1/wallets"],"methods":["GET","OPTIONS"],"strip_path":false,"preserve_host":false}' \
    "Route get-wallet-route"

# Route: PATCH /api/v1/wallets/{walletId}/balance (atualização parcial de saldo)
# Regex path: ~/api/v1/wallets/[^/]+/balance
# PATCH → atualização parcial (pode enviar só brlAvailable ou só vibAvailable)
http_call PUT "${KONG_ADMIN_URL}/services/wallet-service/routes/update-wallet-balance-route" \
    '{"name":"update-wallet-balance-route","paths":["~/api/v1/wallets/[^/]+/balance"],"methods":["PATCH","OPTIONS"],"strip_path":false,"preserve_host":false}' \
    "Route update-wallet-balance-route"

# Plugins na Route get-wallet-route
GET_ROUTE_ID=$(curl -s "${KONG_ADMIN_URL}/services/wallet-service/routes/get-wallet-route" | \
    grep -o '"id":"[^"]*"' | head -1 | sed 's/"id":"//;s/"//')
echo "[kong] get-wallet-route ID: ${GET_ROUTE_ID}"

for PNAME in jwt rate-limiting cors; do
    OLD_PID=$(curl -s "${KONG_ADMIN_URL}/routes/${GET_ROUTE_ID}/plugins" | \
        grep -B2 "\"name\":\"${PNAME}\"" | grep '"id"' | head -1 | \
        sed 's/.*"id":"\([^"]*\)".*/\1/')
    [ -n "$OLD_PID" ] && curl -s -o /dev/null \
        -X DELETE "${KONG_ADMIN_URL}/plugins/${OLD_PID}"
done

http_call POST "${KONG_ADMIN_URL}/routes/${GET_ROUTE_ID}/plugins" \
    '{"name":"jwt","config":{"uri_param_names":[],"cookie_names":[],"header_names":["Authorization"],"claims_to_verify":["exp"],"key_claim_name":"iss","maximum_expiration":3600,"secret_is_base64":false,"run_on_preflight":false}}' \
    "Plugin jwt (get-wallet)"

http_call POST "${KONG_ADMIN_URL}/routes/${GET_ROUTE_ID}/plugins" \
    '{"name":"rate-limiting","config":{"second":200,"minute":10000,"policy":"redis","redis_host":"redis-kong","redis_port":6379,"redis_database":1,"limit_by":"ip","hide_client_headers":false,"fault_tolerant":true}}' \
    "Plugin rate-limiting (get-wallet)"

http_call POST "${KONG_ADMIN_URL}/routes/${GET_ROUTE_ID}/plugins" \
    '{"name":"cors","config":{"origins":["*"],"methods":["GET","OPTIONS"],"headers":["Accept","Authorization","Content-Type","X-Requested-With","X-Correlation-ID"],"exposed_headers":["X-Correlation-ID"],"credentials":false,"max_age":3600,"preflight_continue":false}}' \
    "Plugin cors (get-wallet)"

# Plugins na Route update-wallet-balance-route
PATCH_ROUTE_ID=$(curl -s "${KONG_ADMIN_URL}/services/wallet-service/routes/update-wallet-balance-route" | \
    grep -o '"id":"[^"]*"' | head -1 | sed 's/"id":"//;s/"//')
echo "[kong] update-wallet-balance-route ID: ${PATCH_ROUTE_ID}"

for PNAME in jwt rate-limiting cors; do
    OLD_PID=$(curl -s "${KONG_ADMIN_URL}/routes/${PATCH_ROUTE_ID}/plugins" | \
        grep -B2 "\"name\":\"${PNAME}\"" | grep '"id"' | head -1 | \
        sed 's/.*"id":"\([^"]*\)".*/\1/')
    [ -n "$OLD_PID" ] && curl -s -o /dev/null \
        -X DELETE "${KONG_ADMIN_URL}/plugins/${OLD_PID}"
done

http_call POST "${KONG_ADMIN_URL}/routes/${PATCH_ROUTE_ID}/plugins" \
    '{"name":"jwt","config":{"uri_param_names":[],"cookie_names":[],"header_names":["Authorization"],"claims_to_verify":["exp"],"key_claim_name":"iss","maximum_expiration":3600,"secret_is_base64":false,"run_on_preflight":false}}' \
    "Plugin jwt (update-balance)"

http_call POST "${KONG_ADMIN_URL}/routes/${PATCH_ROUTE_ID}/plugins" \
    '{"name":"rate-limiting","config":{"second":50,"minute":2000,"policy":"redis","redis_host":"redis-kong","redis_port":6379,"redis_database":1,"limit_by":"ip","hide_client_headers":false,"fault_tolerant":true}}' \
    "Plugin rate-limiting (update-balance)"

http_call POST "${KONG_ADMIN_URL}/routes/${PATCH_ROUTE_ID}/plugins" \
    '{"name":"cors","config":{"origins":["*"],"methods":["PATCH","OPTIONS"],"headers":["Accept","Authorization","Content-Type","X-Requested-With","X-Correlation-ID"],"exposed_headers":["X-Correlation-ID"],"credentials":false,"max_age":3600,"preflight_continue":false}}' \
    "Plugin cors (update-balance)"


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
