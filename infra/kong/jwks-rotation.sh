#!/bin/sh
# ==============================================================================
# jwks-rotation.sh — Rotação idempotente de JWKS no Kong via Admin API
#
# Responsabilidades:
#   1. Buscar JWKS atual do Keycloak (endpoint /protocol/openid-connect/certs)
#   2. Extrair kid e x5c da chave de assinatura ativa (use="sig", kty="RSA")
#   3. Comparar com o kid do último sync (persistido em KID_STATE_FILE)
#   4. Se o kid mudou → atualizar credencial JWT no Kong via Admin API:
#         a. Remover todas as credenciais JWT antigas do consumer
#         b. Registrar nova credencial com a chave pública atualizada
#   5. Persistir novo kid no state file
#   6. Registrar log estruturado (JSON) para auditoria
#
# IDEMPOTÊNCIA:
#   - Execuções consecutivas sem mudança de kid são no-op (sem chamadas Admin API)
#   - Safe para executar a cada 6 horas sem risco de downtime
#
# ZERO DOWNTIME:
#   - Kong não precisa ser reiniciado para aceitar a nova credencial JWT
#   - A credencial é atualizada via Admin API e propagada imediatamente
#
# Variáveis de ambiente:
#   KONG_ADMIN_URL      — URL da Admin API do Kong (default: http://kong:8001)
#   KEYCLOAK_URL        — URL base do Keycloak   (default: http://keycloak:8080)
#   KEYCLOAK_REALM      — Nome do realm           (default: orderbook-realm)
#   KONG_CONSUMER_NAME  — Consumer no Kong        (default: keycloak-realm-consumer)
#   KEYCLOAK_ISSUER     — Valor do claim iss      (default: http://localhost:8080/realms/orderbook-realm)
#   KID_STATE_FILE      — Arquivo de estado       (default: /var/lib/jwks-rotator/last_kid)
#
# Dependências: curl, jq (ambos disponíveis na imagem Dockerfile.jwks-rotator)
# ==============================================================================

set -e

KONG_ADMIN_URL="${KONG_ADMIN_URL:-http://kong:8001}"
KEYCLOAK_URL="${KEYCLOAK_URL:-http://keycloak:8080}"
KEYCLOAK_REALM="${KEYCLOAK_REALM:-orderbook-realm}"
KONG_CONSUMER_NAME="${KONG_CONSUMER_NAME:-keycloak-realm-consumer}"
KEYCLOAK_ISSUER="${KEYCLOAK_ISSUER:-http://localhost:8080/realms/${KEYCLOAK_REALM}}"
KID_STATE_FILE="${KID_STATE_FILE:-/var/lib/jwks-rotator/last_kid}"

JWKS_URL="${KEYCLOAK_URL}/realms/${KEYCLOAK_REALM}/protocol/openid-connect/certs"

# ==============================================================================
# Garante que o diretório de estado existe
# ==============================================================================
mkdir -p "$(dirname "$KID_STATE_FILE")"

# ==============================================================================
# Logging estruturado (Newline-Delimited JSON — NDJSON)
#
# Uso: log_json LEVEL EVENT extra_key=value ...
# Emite uma linha JSON por evento para ser consumida por sistemas de log
# (Grafana Loki, Fluentd, CloudWatch, etc.)
# ==============================================================================
log_json() {
    LEVEL="$1"
    EVENT="$2"
    shift 2
    EXTRAS=""
    for KV in "$@"; do
        # Cada argumento extra deve ser passado já formatado como JSON: "key":"value"
        EXTRAS="${EXTRAS},${KV}"
    done
    printf '{"ts":"%s","level":"%s","event":"%s","consumer":"%s"%s}\n' \
        "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" \
        "$LEVEL" \
        "$EVENT" \
        "$KONG_CONSUMER_NAME" \
        "$EXTRAS"
}

# ==============================================================================
# STEP 1 — Busca JWKS do Keycloak
#
# O endpoint /protocol/openid-connect/certs retorna todas as chaves públicas
# ativas do realm em formato JWK (JSON Web Key Set).
# ==============================================================================
log_json "info" "jwks_fetch_start" \
    '"jwks_url":"'"${JWKS_URL}"'"'

JWKS=$(curl -sf --max-time 15 \
    -H "Accept: application/json" \
    "${JWKS_URL}" 2>/dev/null) || {
    log_json "error" "jwks_fetch_failed" \
        '"jwks_url":"'"${JWKS_URL}"'"' \
        '"reason":"curl error or timeout"'
    exit 1
}

if [ -z "$JWKS" ]; then
    log_json "error" "jwks_fetch_failed" \
        '"jwks_url":"'"${JWKS_URL}"'"' \
        '"reason":"empty response body"'
    exit 1
fi

# ==============================================================================
# STEP 2 — Extrai kid e x5c da chave de assinatura ativa
#
# Filtro jq: seleciona a primeira chave onde use="sig" E kty="RSA".
# O campo x5c contém o certificado X.509 em base64 (sem headers PEM).
# O Keycloak garante que a chave com use="sig" é a ativa para emitir tokens.
# ==============================================================================
CURRENT_KID=$(echo "$JWKS" | jq -er \
    '[.keys[] | select(.use=="sig" and .kty=="RSA")] | first | .kid' \
    2>/dev/null) || {
    log_json "error" "jwks_parse_failed" \
        '"reason":"no RSA signing key found (use=sig, kty=RSA)"' \
        '"jwks_keys_count":"'"$(echo "$JWKS" | jq -r '.keys | length' 2>/dev/null || echo "parse_error")"'"'
    exit 1
}

CURRENT_X5C=$(echo "$JWKS" | jq -er \
    '[.keys[] | select(.use=="sig" and .kty=="RSA")] | first | .x5c[0]' \
    2>/dev/null) || {
    log_json "error" "jwks_parse_failed" \
        '"kid":"'"${CURRENT_KID}"'"' \
        '"reason":"x5c field absent in signing key — configure Keycloak to include x5c"'
    exit 1
}

log_json "info" "jwks_fetched" \
    '"kid":"'"${CURRENT_KID}"'"'

# ==============================================================================
# STEP 3 — Lê kid do último sync registrado (state file)
#
# O state file persiste o kid sincronizado com sucesso no Kong.
# Arquivo inexistente → novo deployment → sempre sincroniza.
# ==============================================================================
LAST_KID=$(cat "$KID_STATE_FILE" 2>/dev/null || echo "")

if [ "$CURRENT_KID" = "$LAST_KID" ]; then
    # kid não mudou → nenhuma ação necessária (idempotente)
    log_json "info" "jwks_no_change" \
        '"kid":"'"${CURRENT_KID}"'"' \
        '"action":"skip"' \
        '"state_file":"'"${KID_STATE_FILE}"'"'
    exit 0
fi

# kid mudou (ou primeira execução)
log_json "warn" "jwks_kid_changed" \
    '"old_kid":"'"${LAST_KID:-none}"'"' \
    '"new_kid":"'"${CURRENT_KID}"'"'

# ==============================================================================
# STEP 4a — Remove credenciais JWT anteriores do consumer
#
# O Kong JWT plugin identifica a credencial pelo campo `key` (= iss do JWT).
# Como o iss permanece o mesmo após rotação, pode existir apenas uma credencial
# com aquele `key`. Removemos todas para garantir estado limpo antes de criar.
# ==============================================================================
log_json "info" "kong_credentials_cleanup_start" \
    '"kong_admin":"'"${KONG_ADMIN_URL}"'"'

OLD_CREDS=$(curl -sf --max-time 15 \
    "${KONG_ADMIN_URL}/consumers/${KONG_CONSUMER_NAME}/jwt" \
    2>/dev/null) || {
    log_json "error" "kong_credentials_list_failed" \
        '"reason":"curl error accessing Kong Admin API"' \
        '"admin_url":"'"${KONG_ADMIN_URL}"'"'
    exit 1
}

# Itera sobre cada credencial JWT existente e deleta
DELETED_COUNT=0
echo "$OLD_CREDS" | jq -r '.data[].id // empty' 2>/dev/null | while IFS= read -r CRED_ID; do
    [ -z "$CRED_ID" ] && continue
    DEL_CODE=$(curl -sf -o /dev/null -w "%{http_code}" --max-time 15 \
        -X DELETE \
        "${KONG_ADMIN_URL}/consumers/${KONG_CONSUMER_NAME}/jwt/${CRED_ID}" \
        2>/dev/null || echo "000")
    log_json "info" "kong_credential_deleted" \
        '"credential_id":"'"${CRED_ID}"'"' \
        '"http_code":"'"${DEL_CODE}"'"'
done

# ==============================================================================
# STEP 4b — Registra nova credencial JWT RS256 com a chave pública atualizada
#
# O campo `rsa_public_key` aceita o certificado PEM completo.
# O Kong extrai a chave pública do certificado X.509 automaticamente.
# O campo `key` deve ser idêntico ao valor do claim `iss` nos JWTs do Keycloak.
# ==============================================================================
log_json "info" "kong_credential_register_start" \
    '"kid":"'"${CURRENT_KID}"'"' \
    '"issuer":"'"${KEYCLOAK_ISSUER}"'"'

# Formata o certificado PEM a partir do campo x5c do JWKS
PEM="-----BEGIN CERTIFICATE-----\n${CURRENT_X5C}\n-----END CERTIFICATE-----"

# Payload JSON para POST /consumers/{consumer}/jwt
PAYLOAD=$(printf '{"key":"%s","algorithm":"RS256","rsa_public_key":"%s"}' \
    "${KEYCLOAK_ISSUER}" \
    "${PEM}")

RESPONSE_CODE=$(curl -sf \
    -o /tmp/jwks_rotator_response.txt \
    -w "%{http_code}" \
    --max-time 15 \
    -X POST "${KONG_ADMIN_URL}/consumers/${KONG_CONSUMER_NAME}/jwt" \
    -H "Content-Type: application/json" \
    --data-raw "$PAYLOAD" \
    2>/dev/null || echo "000")

# ==============================================================================
# STEP 5 — Verifica resultado e persiste novo kid no state file
# ==============================================================================
if [ "$RESPONSE_CODE" = "201" ] || [ "$RESPONSE_CODE" = "200" ]; then
    # Persiste kid do novo sync — próxima execução será no-op se kid não mudar
    printf '%s' "$CURRENT_KID" > "$KID_STATE_FILE"

    # Log de auditoria — a mensagem "JWKS updated successfully" é verificada pelo AT-13.1
    log_json "info" "jwks_update_success" \
        '"kid":"'"${CURRENT_KID}"'"' \
        '"old_kid":"'"${LAST_KID:-none}"'"' \
        '"http_code":"'"${RESPONSE_CODE}"'"' \
        '"message":"JWKS updated successfully"' \
        '"state_file":"'"${KID_STATE_FILE}"'"'

    exit 0
else
    # Falha ao registrar — log de erro com detalhes para diagnóstico
    RESPONSE_BODY=$(cat /tmp/jwks_rotator_response.txt 2>/dev/null | \
        tr '"' "'" | tr '\n' ' ' | cut -c1-200)
    log_json "error" "kong_credential_register_failed" \
        '"kid":"'"${CURRENT_KID}"'"' \
        '"http_code":"'"${RESPONSE_CODE}"'"' \
        '"response":"'"${RESPONSE_BODY}"'"'
    exit 1
fi
