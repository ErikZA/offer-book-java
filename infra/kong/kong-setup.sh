#!/bin/sh
# ==============================================================================
# kong-setup.sh — Sincronização Declarativa Via JSON (Fix de Esquema 3.4)
# ==============================================================================

set -e

KONG_ADMIN_URL="${KONG_ADMIN_URL:-http://kong:8001}"
KEYCLOAK_URL="${KEYCLOAK_URL:-http://keycloak:8080}"
KEYCLOAK_REALM="${KEYCLOAK_REALM:-orderbook-realm}"
KONG_CONSUMER_NAME="${KONG_CONSUMER_NAME:-keycloak-realm-consumer}"
KEYCLOAK_ISSUER="${KEYCLOAK_ISSUER:-http://keycloak:8080/realms/${KEYCLOAK_REALM}}"

JWKS_URL="${KEYCLOAK_URL}/realms/${KEYCLOAK_REALM}/protocol/openid-connect/certs"

wait_for() {
    URL=$1; NAME=$2; MAX=60; N=0
    echo "[wait] Aguardando ${NAME}..."
    while [ $N -lt $MAX ]; do
        CODE=$(curl -s -o /dev/null -w "%{http_code}" "${URL}" 2>/dev/null || echo "000")
        [ "$CODE" = "200" ] && echo "[ok]   ${NAME} pronto" && return 0
        N=$((N+1)); sleep 5
    done
    echo "[erro] ${NAME} indisponível"; exit 1
}

wait_for "${KONG_ADMIN_URL}/status"    "Kong Admin API"
wait_for "${KEYCLOAK_URL}/health/live" "Keycloak Health"

echo "[keycloak] Buscando JWKS..."
JWKS=$(curl -s "${JWKS_URL}")
X5C=$(echo "$JWKS" | grep -o '"x5c":\["[^"]*"' | head -1 | sed 's|"x5c":\["||;s|"||g')

if [ -z "$X5C" ]; then
    echo "[erro] Falha ao extrair certificado x5c"
    exit 1
fi

# Formata a chave PEM com \n reais para o JSON (o shell lida com isso)
PEM_DATA="-----BEGIN CERTIFICATE-----\\n${X5C}\\n-----END CERTIFICATE-----"

echo "[kong] Aplicando configuração declarativa COMPLETA..."

# Payload JSON definitivo para Kong 3.4.2
# O segredo (secret) é obrigatório no esquema, mesmo para RS256.
cat <<EOF > /tmp/kong_full.json
{
  "_format_version": "3.0",
  "services": [
    {
      "name": "order-service",
      "url": "http://order-service:8080",
      "routes": [
        {
          "name": "place-order-route",
          "paths": ["/api/v1/orders"],
          "methods": ["POST", "OPTIONS"]
        },
        {
          "name": "list-orders-route",
          "paths": ["/api/v1/orders"],
          "methods": ["GET", "OPTIONS"]
        }
      ],
      "plugins": [
        {
          "name": "jwt",
          "config": {
            "key_claim_name": "iss",
            "claims_to_verify": ["exp"]
          }
        }
      ]
    },
    {
      "name": "wallet-service",
      "url": "http://wallet-service:8081",
      "routes": [
        {
          "name": "get-wallet-route",
          "paths": ["/api/v1/wallets"],
          "methods": ["GET", "OPTIONS"]
        }
      ],
      "plugins": [
        {
          "name": "jwt",
          "config": {
            "key_claim_name": "iss",
            "claims_to_verify": ["exp"]
          }
        }
      ]
    }
  ],
  "consumers": [
    {
      "username": "${KONG_CONSUMER_NAME}",
      "jwt_secrets": [
        {
          "key": "${KEYCLOAK_ISSUER}",
          "algorithm": "RS256",
          "secret": "dummy-secret-not-used-for-rs256",
          "rsa_public_key": "${PEM_DATA}"
        }
      ]
    }
  ]
}
EOF

# Aplicamos via endpoint /config que recarrega todo o Kong DB-less
CODE=$(curl -s -o /tmp/kong_out.txt -w "%{http_code}" \
    -X POST "${KONG_ADMIN_URL}/config" \
    -H "Content-Type: application/json" \
    --data @/tmp/kong_full.json)

if [ "$CODE" = "201" ] || [ "$CODE" = "200" ]; then
    echo "[ok]   Configuração aplicada com sucesso (HTTP ${CODE})"
else
    echo "[erro] Falha ao aplicar (HTTP ${CODE}):"
    cat /tmp/kong_out.txt
    exit 1
fi
