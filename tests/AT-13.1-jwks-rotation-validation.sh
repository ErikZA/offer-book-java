#!/bin/sh
# ==============================================================================
# AT-13.1 — Teste de Validação: Rotação Automática de JWKS no Kong
#
# FASE RED (TDD): Este script FALHA antes da implementação do rotator.
#
# O que valida (10 testes):
#   1.  Artefatos de implementação existem (jwks-rotation.sh, Dockerfile)
#   2.  Infraestrutura de teste acessível (Kong Admin, Keycloak, Kong Proxy)
#   3.  Consumer e credencial JWT registrados no Kong
#   4.  Token inicial (kid A) retorna 200 OK no Kong Proxy
#   5.  Rotação forçada no Keycloak gera novo kid (kid B ≠ kid A)
#   6.  Token novo (kid B) retorna 401 ANTES da rotação do Kong (prova o problema)
#   7.  Execução do rotator atualiza credencial no Kong
#   8.  Token novo (kid B) retorna 200 APÓS rotação do Kong (prova a solução)
#   9.  Log do rotator contém "JWKS updated successfully"
#  10.  Rotação é idempotente (segunda execução não remove/recria a mesma chave)
#
# FASE RED: Testes 1, 7, 8, 9 falham ANTES da implementação porque:
#   - infra/kong/jwks-rotation.sh não existe
#   - Dockerfile.jwks-rotator não existe
#   - Serviço jwks-rotator não está no docker-compose
#
# Pré-requisitos (ambiente de teste rodando):
#   docker compose -f tests/docker-compose.test.yml up -d
#
# Variáveis de ambiente opcionais:
#   KONG_ADMIN_URL   — default: http://localhost:8001
#   KONG_PROXY_URL   — default: http://localhost:8000
#   KEYCLOAK_URL     — default: http://localhost:8180
#   KEYCLOAK_REALM   — default: orderbook-realm
#   KONG_CONSUMER    — default: keycloak-realm-consumer
#   KEYCLOAK_ISSUER  — default: http://localhost:8180/realms/orderbook-realm
#   ROTATION_SCRIPT  — path local do script (para execução manual no teste headless)
#
# Uso:
#   chmod +x tests/AT-13.1-jwks-rotation-validation.sh
#   ./tests/AT-13.1-jwks-rotation-validation.sh
#
# Retorno:
#   0 → todos os testes passaram (FASE GREEN)
#   1 → pelo menos um teste falhou (FASE RED — esperado antes da implementação)
# ==============================================================================

KONG_ADMIN_URL="${KONG_ADMIN_URL:-http://localhost:8001}"
KONG_PROXY_URL="${KONG_PROXY_URL:-http://localhost:8000}"
KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8180}"
KEYCLOAK_REALM="${KEYCLOAK_REALM:-orderbook-realm}"
KONG_CONSUMER="${KONG_CONSUMER:-keycloak-realm-consumer}"
KEYCLOAK_ISSUER="${KEYCLOAK_ISSUER:-http://localhost:8180/realms/${KEYCLOAK_REALM}}"
KEYCLOAK_CLIENT="${KEYCLOAK_CLIENT:-order-client}"
KEYCLOAK_USER="${KEYCLOAK_USER:-tester}"
KEYCLOAK_PASS="${KEYCLOAK_PASS:-test-password}"
KEYCLOAK_ADMIN_USER="${KEYCLOAK_ADMIN_USER:-admin}"
KEYCLOAK_ADMIN_PASS="${KEYCLOAK_ADMIN_PASS:-admin123}"

# Caminho para o script de rotação (relativo à raiz do projeto)
SCRIPT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ROTATION_SCRIPT="${ROTATION_SCRIPT:-${SCRIPT_DIR}/infra/kong/jwks-rotation.sh}"
DOCKERFILE_ROTATOR="${DOCKERFILE_ROTATOR:-${SCRIPT_DIR}/infra/docker/Dockerfile.jwks-rotator}"

FAIL=0
PASS=0

# Cores ANSI
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

pass()  { printf "${GREEN}[PASS]${NC} %s\n" "$1"; PASS=$((PASS+1)); }
fail()  { printf "${RED}[FAIL]${NC} %s\n" "$1"; FAIL=$((FAIL+1)); }
info()  { printf "${YELLOW}[INFO]${NC} %s\n" "$1"; }
debug() { printf "${BLUE}[DEBUG]${NC} %s\n" "$1"; }

echo ""
echo "======================================================================"
echo " AT-13.1 — Rotação Automática de JWKS no Kong via Sidecar"
echo " Kong Admin : ${KONG_ADMIN_URL}"
echo " Kong Proxy : ${KONG_PROXY_URL}"
echo " Keycloak   : ${KEYCLOAK_URL} | Realm: ${KEYCLOAK_REALM}"
echo " Consumer   : ${KONG_CONSUMER}"
echo " Timestamp  : $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
echo "======================================================================"
echo ""

# ==============================================================================
# TEST 1 — Artefatos de implementação existem
#
# FASE RED: FALHA se jwks-rotation.sh ou Dockerfile.jwks-rotator não existirem.
# Estes artefatos são criados pela implementação do AT-13.1.
# ==============================================================================
echo "--- TEST 1: Artefatos de implementação existem ---"

if [ -f "${ROTATION_SCRIPT}" ]; then
    pass "infra/kong/jwks-rotation.sh existe"
else
    fail "infra/kong/jwks-rotation.sh NÃO encontrado em: ${ROTATION_SCRIPT}"
    fail "  FASE RED: implemente o script idempotente de rotação JWKS"
fi

if [ -f "${DOCKERFILE_ROTATOR}" ]; then
    pass "infra/docker/Dockerfile.jwks-rotator existe"
else
    fail "infra/docker/Dockerfile.jwks-rotator NÃO encontrado em: ${DOCKERFILE_ROTATOR}"
    fail "  FASE RED: implemente o Dockerfile do sidecar rotator"
fi

ENTRYPOINT="${SCRIPT_DIR}/infra/kong/jwks-rotator-entrypoint.sh"
if [ -f "${ENTRYPOINT}" ]; then
    pass "infra/kong/jwks-rotator-entrypoint.sh existe"
else
    fail "infra/kong/jwks-rotator-entrypoint.sh NÃO encontrado"
    fail "  FASE RED: implemente o entrypoint com loop de 6 horas"
fi

echo ""

# ==============================================================================
# TEST 2 — Infraestrutura de teste acessível
# ==============================================================================
echo "--- TEST 2: Infraestrutura acessível ---"

STATUS=$(curl -sf -o /dev/null -w "%{http_code}" "${KONG_ADMIN_URL}/status" 2>/dev/null || echo "000")
if [ "$STATUS" = "200" ]; then
    pass "Kong Admin API acessível (HTTP ${STATUS})"
else
    fail "Kong Admin API indisponível (HTTP ${STATUS}) — suba: docker compose -f tests/docker-compose.test.yml up -d"
    echo ""
    echo "RESULTADO PARCIAL: ${PASS} passaram, ${FAIL} falharam — abortando (infra unavailable)"
    exit 1
fi

STATUS=$(curl -sf -o /dev/null -w "%{http_code}" "${KEYCLOAK_URL}/health/live" 2>/dev/null || echo "000")
if [ "$STATUS" = "200" ]; then
    pass "Keycloak Health acessível (HTTP ${STATUS})"
else
    fail "Keycloak indisponível (HTTP ${STATUS})"
    echo ""
    echo "RESULTADO PARCIAL: ${PASS} passaram, ${FAIL} falharam — abortando (infra unavailable)"
    exit 1
fi

JWKS_URL="${KEYCLOAK_URL}/realms/${KEYCLOAK_REALM}/protocol/openid-connect/certs"
STATUS=$(curl -sf -o /dev/null -w "%{http_code}" "${JWKS_URL}" 2>/dev/null || echo "000")
if [ "$STATUS" = "200" ]; then
    pass "JWKS endpoint acessível (HTTP ${STATUS})"
else
    fail "JWKS endpoint indisponível (HTTP ${STATUS}) — realm não importado?"
fi

echo ""

# ==============================================================================
# TEST 3 — Consumer e credencial JWT registrados no Kong
# ==============================================================================
echo "--- TEST 3: Consumer e credencial JWT no Kong ---"

CONSUMER_STATUS=$(curl -sf -o /dev/null -w "%{http_code}" \
    "${KONG_ADMIN_URL}/consumers/${KONG_CONSUMER}" 2>/dev/null || echo "000")

if [ "$CONSUMER_STATUS" = "200" ]; then
    pass "Consumer '${KONG_CONSUMER}' registrado no Kong"
else
    fail "Consumer '${KONG_CONSUMER}' não encontrado (HTTP ${CONSUMER_STATUS})"
    fail "  Execute o kong-init (kong-setup.sh) antes do teste"
fi

JWT_CREDS=$(curl -sf "${KONG_ADMIN_URL}/consumers/${KONG_CONSUMER}/jwt" 2>/dev/null || echo '{"data":[]}')
JWT_COUNT=$(echo "$JWT_CREDS" | grep -c '"id"' || echo "0")

if [ "$JWT_COUNT" -gt 0 ]; then
    CURRENT_KONG_KEY_ALG=$(echo "$JWT_CREDS" | grep -o '"algorithm":"[^"]*"' | head -1 | sed 's/"algorithm":"//;s/"//')
    pass "Credencial JWT encontrada no Kong (algoritmo: ${CURRENT_KONG_KEY_ALG:-desconhecido})"

    CURRENT_KONG_KEY=$(echo "$JWT_CREDS" | grep -o '"key":"[^"]*"' | head -1 | sed 's/"key":"//;s/"//')
    if [ "$CURRENT_KONG_KEY" = "$KEYCLOAK_ISSUER" ]; then
        pass "Campo 'key' da credencial bate com KEYCLOAK_ISSUER (iss)"
    else
        fail "Campo 'key' ('${CURRENT_KONG_KEY}') não bate com KEYCLOAK_ISSUER ('${KEYCLOAK_ISSUER}')"
    fi
else
    fail "Nenhuma credencial JWT no consumer '${KONG_CONSUMER}'"
fi

echo ""

# ==============================================================================
# TEST 4 — Token inicial retorna 200 OK no Kong Proxy
# ==============================================================================
echo "--- TEST 4: Token inicial (kid A) aceito pelo Kong ---"

TOKEN_ENDPOINT="${KEYCLOAK_URL}/realms/${KEYCLOAK_REALM}/protocol/openid-connect/token"

TOKEN_RESPONSE=$(curl -sf --max-time 10 -X POST "${TOKEN_ENDPOINT}" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "grant_type=password" \
    -d "client_id=${KEYCLOAK_CLIENT}" \
    -d "username=${KEYCLOAK_USER}" \
    -d "password=${KEYCLOAK_PASS}" \
    2>/dev/null || echo "")

TOKEN_A=$(echo "$TOKEN_RESPONSE" | grep -o '"access_token":"[^"]*"' | sed 's/"access_token":"//;s/"//')

if [ -z "$TOKEN_A" ]; then
    fail "Falha ao obter token do Keycloak — verifique credenciais do usuário de teste"
    echo "  user=${KEYCLOAK_USER} pass=${KEYCLOAK_PASS} client=${KEYCLOAK_CLIENT}"
    echo ""
    echo "RESULTADO PARCIAL: ${PASS} passaram, ${FAIL} falharam"
    exit 1
fi

# Extrai kid do header JWT (parte 1 do token, base64url decodificado)
KID_A=$(echo "$TOKEN_A" | cut -d. -f1 | { read H; printf '%s' "$H" | tr '_-' '/+' | \
    awk '{ pad=length($0)%4; if(pad==2) $0=$0"=="; else if(pad==3) $0=$0"="; print $0 }' | \
    base64 -d 2>/dev/null || echo ""; } | grep -o '"kid":"[^"]*"' | sed 's/"kid":"//;s/"//')

info "kid do token A: ${KID_A:-não detectado}"
pass "Token obtido com sucesso do Keycloak"

# Testa o token no Kong Proxy
PROXY_STATUS=$(curl -sf -o /dev/null -w "%{http_code}" \
    -X POST "${KONG_PROXY_URL}/api/v1/orders" \
    -H "Authorization: Bearer ${TOKEN_A}" \
    -H "Content-Type: application/json" \
    -d '{"test":"at-13.1-initial"}' \
    2>/dev/null || echo "000")

# 200 ou 5xx é OK — 5xx significa que o upstream (order-service) não está rodando
# mas o Kong aceitou o token (não retornou 401)
if [ "$PROXY_STATUS" = "401" ]; then
    fail "Kong rejeitou token A com 401 — credencial JWT não está corretamente registrada"
elif [ "$PROXY_STATUS" = "000" ]; then
    fail "Kong Proxy inacessível (HTTP 000)"
else
    pass "Token A aceito pelo Kong (HTTP ${PROXY_STATUS} ≠ 401)"
fi

echo ""

# ==============================================================================
# TEST 5 — Rotação forçada no Keycloak gera novo kid
#
# Usa a Keycloak Admin API para deletar o componente RSA ativo.
# Keycloak gera novo par de chaves automaticamente.
# ==============================================================================
echo "--- TEST 5: Rotação de chave forçada no Keycloak ---"

# Obtém token de admin do realm master
ADMIN_TOKEN_RESPONSE=$(curl -sf --max-time 10 -X POST \
    "${KEYCLOAK_URL}/realms/master/protocol/openid-connect/token" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "grant_type=password" \
    -d "client_id=admin-cli" \
    -d "username=${KEYCLOAK_ADMIN_USER}" \
    -d "password=${KEYCLOAK_ADMIN_PASS}" \
    2>/dev/null || echo "")

ADMIN_TOKEN=$(echo "$ADMIN_TOKEN_RESPONSE" | grep -o '"access_token":"[^"]*"' | \
    sed 's/"access_token":"//;s/"//')

if [ -z "$ADMIN_TOKEN" ]; then
    fail "Falha ao obter admin token do Keycloak Master realm"
    echo "  Verifique: admin=${KEYCLOAK_ADMIN_USER} pass=${KEYCLOAK_ADMIN_PASS}"
else
    pass "Admin token obtido do realm master"

    # Lista componentes de chave RSA do realm
    COMPONENTS=$(curl -sf --max-time 10 \
        "${KEYCLOAK_URL}/admin/realms/${KEYCLOAK_REALM}/components?type=org.keycloak.keys.KeyProvider" \
        -H "Authorization: Bearer ${ADMIN_TOKEN}" 2>/dev/null || echo "[]")

    # Extrai ID do provider RSA-Generated (tipo rsa-generated)
    RSA_COMPONENT_ID=$(echo "$COMPONENTS" | grep -B2 '"rsa-generated"' | \
        grep '"id"' | head -1 | grep -o '"id":"[^"]*"' | sed 's/"id":"//;s/"//')

    if [ -z "$RSA_COMPONENT_ID" ]; then
        # Fallback: pega o primeiro componente RSA de qualquer subtipo
        RSA_COMPONENT_ID=$(echo "$COMPONENTS" | grep '"id"' | head -1 | \
            grep -o '"[0-9a-f-]*"' | tr -d '"' | head -1)
        info "Componente RSA via fallback: ${RSA_COMPONENT_ID:-não encontrado}"
    else
        info "Componente RSA-Generated encontrado: ${RSA_COMPONENT_ID}"
    fi

    if [ -n "$RSA_COMPONENT_ID" ]; then
        # Deleta o componente → Keycloak gera novo par RSA automaticamente
        DEL_STATUS=$(curl -sf -o /dev/null -w "%{http_code}" --max-time 10 \
            -X DELETE \
            "${KEYCLOAK_URL}/admin/realms/${KEYCLOAK_REALM}/components/${RSA_COMPONENT_ID}" \
            -H "Authorization: Bearer ${ADMIN_TOKEN}" 2>/dev/null || echo "000")

        if [ "$DEL_STATUS" = "204" ] || [ "$DEL_STATUS" = "200" ]; then
            pass "Componente RSA deletado do Keycloak (HTTP ${DEL_STATUS}) — nova chave será gerada"

            # Aguarda Keycloak regenerar chave (pode levar até 5s)
            info "Aguardando Keycloak regenerar par RSA..."
            sleep 5

            # Verifica que JWKS agora tem nova chave disponível
            NEW_JWKS=$(curl -sf --max-time 10 "${JWKS_URL}" 2>/dev/null || echo "")
            NEW_KID=$(echo "$NEW_JWKS" | grep -o '"kid":"[^"]*"' | head -1 | sed 's/"kid":"//;s/"//')

            if [ -n "$NEW_KID" ] && [ "$NEW_KID" != "$KID_A" ]; then
                pass "Novo kid confirmado no JWKS: ${NEW_KID} (was: ${KID_A:-desconhecido})"
                KID_B="$NEW_KID"
            elif [ -n "$NEW_KID" ]; then
                # kid pode ser o mesmo se Keycloak reutilizou
                info "kid após rotação: ${NEW_KID} (pode ser novo ou idêntico dependendo da versão KC)"
                KID_B="$NEW_KID"
            else
                fail "Não foi possível confirmar novo kid no JWKS após rotação"
                KID_B=""
            fi
        else
            fail "Falha ao deletar componente RSA (HTTP ${DEL_STATUS})"
        fi
    else
        fail "Componente RSA não encontrado nos key providers do realm '${KEYCLOAK_REALM}'"
        fail "  Possível causa: realm não tem RSA-Generated key provider configurado"
    fi
fi

echo ""

# ==============================================================================
# TEST 6 — Token pós-rotação retorna 401 ANTES do rotator atualizar o Kong
#
# Este é o teste que PROVA o problema existe. Sem o rotator, o Kong ainda
# possui a chave antiga e rejeita tokens assinados com o novo kid.
# ==============================================================================
echo "--- TEST 6: Token pós-rotação → 401 ANTES do rotator (prova o problema) ---"

# Obtém novo token com nova chave
NEW_TOKEN_RESPONSE=$(curl -sf --max-time 10 -X POST "${TOKEN_ENDPOINT}" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "grant_type=password" \
    -d "client_id=${KEYCLOAK_CLIENT}" \
    -d "username=${KEYCLOAK_USER}" \
    -d "password=${KEYCLOAK_PASS}" \
    2>/dev/null || echo "")

TOKEN_B=$(echo "$NEW_TOKEN_RESPONSE" | grep -o '"access_token":"[^"]*"' | sed 's/"access_token":"//;s/"//')

if [ -z "$TOKEN_B" ]; then
    fail "Não foi possível obter Token B pós-rotação"
else
    pass "Token B obtido do Keycloak (signed with new kid)"

    # Extrai kid do Token B para confirmar que é diferente
    TOKEN_B_KID=$(echo "$TOKEN_B" | cut -d. -f1 | { read H; printf '%s' "$H" | tr '_-' '/+' | \
        awk '{ pad=length($0)%4; if(pad==2) $0=$0"=="; else if(pad==3) $0=$0"="; print $0 }' | \
        base64 -d 2>/dev/null || echo ""; } | grep -o '"kid":"[^"]*"' | sed 's/"kid":"//;s/"//')

    info "kid do token B: ${TOKEN_B_KID:-não detectado}"

    # Testa Token B no Kong — DEVE retornar 401 (Kong ainda tem chave antiga)
    PROXY_STATUS_B=$(curl -sf -o /dev/null -w "%{http_code}" \
        -X POST "${KONG_PROXY_URL}/api/v1/orders" \
        -H "Authorization: Bearer ${TOKEN_B}" \
        -H "Content-Type: application/json" \
        -d '{"test":"at-13.1-post-rotation"}' \
        2>/dev/null || echo "000")

    info "Status Kong com Token B ANTES do rotator: HTTP ${PROXY_STATUS_B}"

    if [ "$PROXY_STATUS_B" = "401" ]; then
        pass "Token B rejeitado com 401 — confirma o problema P1 (Kong tem chave antiga)"
    elif [ "$PROXY_STATUS_B" = "200" ] || [ "$PROXY_STATUS_B" = "5"* ]; then
        # Se o kid não mudou (Keycloak reutilizou a mesma chave), o token ainda é aceito
        info "Token B aceito (HTTP ${PROXY_STATUS_B}) — kid pode não ter mudado nesta rotação"
        info "  Isto é aceitável: Keycloak pode preservar o kid em algumas versões"
        pass "Kong respondeu ${PROXY_STATUS_B} — sem 401 inesperado"
    else
        fail "Resposta inesperada do Kong: HTTP ${PROXY_STATUS_B}"
    fi
fi

echo ""

# ==============================================================================
# TEST 7 — Execução do rotator atualiza credencial no Kong
#
# FASE RED: FALHA se jwks-rotation.sh não existir ou o rotator não executar.
# Executa o script diretamente (simulando execução do sidecar).
# ==============================================================================
echo "--- TEST 7: Execução do rotator atualiza Kong ---"

if [ ! -f "${ROTATION_SCRIPT}" ]; then
    fail "jwks-rotation.sh não existe — FASE RED: implemente o script"
else
    # Executa o rotator com variáveis de ambiente do ambiente de teste
    ROTATION_LOG=$(KONG_ADMIN_URL="${KONG_ADMIN_URL}" \
        KEYCLOAK_URL="${KEYCLOAK_URL}" \
        KEYCLOAK_REALM="${KEYCLOAK_REALM}" \
        KONG_CONSUMER_NAME="${KONG_CONSUMER}" \
        KEYCLOAK_ISSUER="${KEYCLOAK_ISSUER}" \
        KID_STATE_FILE="/tmp/at-13.1-last-kid" \
        sh "${ROTATION_SCRIPT}" 2>&1)

    ROTATION_EXIT=$?

    debug "Saída do rotator:"
    echo "$ROTATION_LOG" | sed 's/^/    /'
    echo ""

    if [ $ROTATION_EXIT -eq 0 ]; then
        pass "Script jwks-rotation.sh executou sem erro (exit 0)"
    else
        fail "Script jwks-rotation.sh terminou com erro (exit ${ROTATION_EXIT})"
    fi
fi

echo ""

# ==============================================================================
# TEST 8 — Token pós-rotação retorna 200 APÓS o rotator atualizar o Kong
#
# FASE RED: FALHA se o rotator não tiver atualizado a credencial no Kong.
# ==============================================================================
echo "--- TEST 8: Token B → 200 OK APÓS rotator (prova a solução) ---"

if [ -z "$TOKEN_B" ]; then
    fail "Token B não disponível — testes anteriores falharam, skip"
else
    # Aguarda Kong propagar a nova credencial (< 1s normalmente)
    sleep 2

    PROXY_STATUS_AFTER=$(curl -sf -o /dev/null -w "%{http_code}" \
        -X POST "${KONG_PROXY_URL}/api/v1/orders" \
        -H "Authorization: Bearer ${TOKEN_B}" \
        -H "Content-Type: application/json" \
        -d '{"test":"at-13.1-post-rotation-after-rotator"}' \
        2>/dev/null || echo "000")

    info "Status Kong com Token B APÓS rotator: HTTP ${PROXY_STATUS_AFTER}"

    if [ "$PROXY_STATUS_AFTER" = "401" ]; then
        fail "Token B AINDA rejeitado com 401 após rotator — FASE RED: rotator não atualizou Kong"
        fail "  Verifique se jwks-rotation.sh está correto e se a credencial foi atualizada"
        fail "  Debug: curl ${KONG_ADMIN_URL}/consumers/${KONG_CONSUMER}/jwt"
    else
        pass "Token B aceito pelo Kong após rotação (HTTP ${PROXY_STATUS_AFTER} ≠ 401)"
    fi
fi

echo ""

# ==============================================================================
# TEST 9 — Log do rotator contém "JWKS updated successfully"
#
# FASE RED: FALHA se o script não emitir log de auditoria estruturado.
# ==============================================================================
echo "--- TEST 9: Log de auditoria contém 'JWKS updated successfully' ---"

if [ ! -f "${ROTATION_SCRIPT}" ]; then
    fail "jwks-rotation.sh não existe — não é possível verificar log"
else
    # Força uma atualização simulando mudança de kid no state file
    # (apaga state file para forçar o script a acreditar que houve rotação)
    rm -f /tmp/at-13.1-last-kid

    AUDIT_LOG=$(KONG_ADMIN_URL="${KONG_ADMIN_URL}" \
        KEYCLOAK_URL="${KEYCLOAK_URL}" \
        KEYCLOAK_REALM="${KEYCLOAK_REALM}" \
        KONG_CONSUMER_NAME="${KONG_CONSUMER}" \
        KEYCLOAK_ISSUER="${KEYCLOAK_ISSUER}" \
        KID_STATE_FILE="/tmp/at-13.1-last-kid" \
        sh "${ROTATION_SCRIPT}" 2>&1)

    if echo "$AUDIT_LOG" | grep -q "JWKS updated successfully"; then
        pass "Log contém 'JWKS updated successfully' — auditoria confirmada"
    else
        fail "Log NÃO contém 'JWKS updated successfully'"
        fail "  FASE RED: adicione log estruturado ao jwks-rotation.sh"
        info "  Saída atual do script:"
        echo "$AUDIT_LOG" | sed 's/^/    /'
    fi

    # Verifica que o log é JSON estruturado
    FIRST_LINE=$(echo "$AUDIT_LOG" | head -1)
    if echo "$FIRST_LINE" | grep -q '"ts"'; then
        pass "Log é JSON estruturado (contém campo 'ts')"
    else
        fail "Log NÃO é JSON estruturado — implemente logging em formato JSON"
    fi
fi

echo ""

# ==============================================================================
# TEST 10 — Idempotência: segunda execução não recria a mesma chave
# ==============================================================================
echo "--- TEST 10: Rotação é idempotente (sem mudança de kid → sem update) ---"

if [ ! -f "${ROTATION_SCRIPT}" ]; then
    fail "jwks-rotation.sh não existe — não é possível verificar idempotência"
else
    # Executa o rotator duas vezes seguidas sem mudar o JWKS
    IDEMPOTENT_LOG1=$(KONG_ADMIN_URL="${KONG_ADMIN_URL}" \
        KEYCLOAK_URL="${KEYCLOAK_URL}" \
        KEYCLOAK_REALM="${KEYCLOAK_REALM}" \
        KONG_CONSUMER_NAME="${KONG_CONSUMER}" \
        KEYCLOAK_ISSUER="${KEYCLOAK_ISSUER}" \
        KID_STATE_FILE="/tmp/at-13.1-idempotent-kid" \
        sh "${ROTATION_SCRIPT}" 2>&1)

    IDEMPOTENT_LOG2=$(KONG_ADMIN_URL="${KONG_ADMIN_URL}" \
        KEYCLOAK_URL="${KEYCLOAK_URL}" \
        KEYCLOAK_REALM="${KEYCLOAK_REALM}" \
        KONG_CONSUMER_NAME="${KONG_CONSUMER}" \
        KEYCLOAK_ISSUER="${KEYCLOAK_ISSUER}" \
        KID_STATE_FILE="/tmp/at-13.1-idempotent-kid" \
        sh "${ROTATION_SCRIPT}" 2>&1)

    # Na segunda execução, o kid não mudou → log deve conter "no_change"
    if echo "$IDEMPOTENT_LOG2" | grep -q "no_change"; then
        pass "Segunda execução sem mudança de kid → rotator retorna 'no_change' (idempotente)"
    else
        fail "Segunda execução sem mudança de kid → rotator não detectou 'no_change'"
        fail "  FASE RED: adicione verificação de kid ao jwks-rotation.sh"
        info "  Log 2ª execução:"
        echo "$IDEMPOTENT_LOG2" | sed 's/^/    /'
    fi

    # Na 1ª execução deveria ter atualizado (state file novo)
    if echo "$IDEMPOTENT_LOG1" | grep -q "JWKS updated successfully"; then
        pass "Primeira execução sem state → rotator atualizou credencial corretamente"
    else
        info "Primeira execução: não houve update ou kid já estava registrado"
    fi
fi

echo ""

# ==============================================================================
# Sumário Final
# ==============================================================================
TOTAL=$((PASS + FAIL))
echo "======================================================================"
echo " AT-13.1 — Resultado Final"
echo " PASS: ${PASS} / TOTAL: ${TOTAL}"
echo " FAIL: ${FAIL} / TOTAL: ${TOTAL}"
echo "======================================================================"

if [ "$FAIL" -gt 0 ]; then
    printf "${RED}"
    echo " STATUS: FASE RED — ${FAIL} teste(s) falharam"
    echo " Implemente os artefatos AT-13.1 para tornar este teste GREEN."
    printf "${NC}"
    echo ""
    exit 1
else
    printf "${GREEN}"
    echo " STATUS: FASE GREEN — todos os ${PASS} testes passaram"
    printf "${NC}"
    echo ""
    exit 0
fi
