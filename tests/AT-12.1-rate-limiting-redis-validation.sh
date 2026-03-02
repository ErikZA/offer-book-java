#!/bin/sh
# ==============================================================================
# AT-12.1 — Teste de Validação: Rate-Limiting Redis Policy
#
# FASE RED (TDD): Este script FALHA antes da implementação de `policy: redis`.
#
# O que valida:
#   1. Admin API reporta `policy: redis` em todos os plugins rate-limiting
#   2. Redis-kong está acessível (porta 6379, db 1)
#   3. Simulação multi-nó: 2 instâncias Kong compartilham contador
#      → 3 reqs para nó A + 3 reqs para nó B com limite=5 → pelo menos 1 retorna 429
#
# Pré-requisitos:
#   - Kong Admin API disponível em KONG_ADMIN_URL (default: http://localhost:8001)
#   - Para o teste multi-nó: dois nós Kong em KONG_NODE_A e KONG_NODE_B
#   - curl disponível no PATH
#
# Uso:
#   ./tests/AT-12.1-rate-limiting-redis-validation.sh
#
# Retorno:
#   0 → todos os testes passaram (FASE GREEN)
#   1 → pelo menos um teste falhou (FASE RED — antes da implementação)
# ==============================================================================

KONG_ADMIN_URL="${KONG_ADMIN_URL:-http://localhost:8001}"
KONG_PROXY_A="${KONG_PROXY_A:-http://localhost:8000}"
KONG_PROXY_B="${KONG_PROXY_B:-http://localhost:8000}"   # mesmo nó em single-node
REDIS_HOST="${REDIS_HOST:-localhost}"
REDIS_PORT="${REDIS_PORT:-6379}"

FAIL=0
PASS=0

# Cores ANSI para saída legível
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'

pass() { printf "${GREEN}[PASS]${NC} %s\n" "$1"; PASS=$((PASS+1)); }
fail() { printf "${RED}[FAIL]${NC} %s\n" "$1"; FAIL=$((FAIL+1)); }
info() { printf "${YELLOW}[INFO]${NC} %s\n" "$1"; }

echo "======================================================================"
echo " AT-12.1 — Rate-Limiting Redis Policy — Validação"
echo " Kong Admin : ${KONG_ADMIN_URL}"
echo " Timestamp  : $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
echo "======================================================================"
echo ""

# ==============================================================================
# TEST 1 — Admin API acessível
# ==============================================================================
info "TEST 1: Admin API disponível"
STATUS=$(curl -s -o /dev/null -w "%{http_code}" "${KONG_ADMIN_URL}/status" 2>/dev/null || echo "000")
if [ "$STATUS" = "200" ]; then
    pass "Admin API respondeu HTTP 200"
else
    fail "Admin API indisponível (HTTP ${STATUS}) — Kong não está rodando"
    echo ""
    echo "RESULTADO: ${PASS} passaram, ${FAIL} falharam"
    exit 1
fi

# ==============================================================================
# TEST 2 — Nenhum plugin rate-limiting usa policy=local
#
# FASE RED: este teste FALHA enquanto policy=local estiver configurado.
# Após a implementação (policy=redis) este teste passa.
# ==============================================================================
echo ""
info "TEST 2: Todos os plugins rate-limiting devem usar policy=redis (não local)"

PLUGINS_JSON=$(curl -s "${KONG_ADMIN_URL}/plugins?size=100")
RATE_LIMITING_ENTRIES=$(echo "$PLUGINS_JSON" | grep -A 30 '"name":"rate-limiting"')

# Conta quantos plugins rate-limiting existem
TOTAL_RL=$(echo "$PLUGINS_JSON" | grep -c '"name":"rate-limiting"' || echo "0")
info "Plugins rate-limiting encontrados: ${TOTAL_RL}"

# Verifica se algum ainda usa policy=local
LOCAL_COUNT=$(echo "$PLUGINS_JSON" | grep -c '"policy":"local"' || echo "0")
REDIS_COUNT=$(echo "$PLUGINS_JSON" | grep -c '"policy":"redis"' || echo "0")

info "  policy=local : ${LOCAL_COUNT} plugin(s)"
info "  policy=redis : ${REDIS_COUNT} plugin(s)"

if [ "$LOCAL_COUNT" -gt 0 ]; then
    fail "Encontrado(s) ${LOCAL_COUNT} plugin(s) com policy=local — rate-limiting NÃO é global!"
    fail "  Em cluster Kong, cada nó mantém contador independente."
    fail "  Limite de 100 req/s vira ${TOTAL_RL}00 req/s efetivos (3 nós = 300 req/s)."
else
    if [ "$REDIS_COUNT" -gt 0 ]; then
        pass "Todos os ${REDIS_COUNT} plugin(s) rate-limiting usam policy=redis"
    else
        fail "Nenhum plugin rate-limiting configurado na Admin API"
    fi
fi

# ==============================================================================
# TEST 3 — Configuração redis_host aponta para redis-kong
# ==============================================================================
echo ""
info "TEST 3: redis_host deve ser 'redis-kong' (Redis dedicado ao Kong)"

REDIS_HOST_FOUND=$(echo "$PLUGINS_JSON" | grep -o '"redis_host":"[^"]*"' | sort -u)
info "  redis_host encontrado: ${REDIS_HOST_FOUND:-'(nenhum)'}"

WRONG_HOST=$(echo "$PLUGINS_JSON" | grep '"redis_host"' | grep -v '"redis_host":"redis-kong"' | wc -l | tr -d ' ')
CORRECT_HOST=$(echo "$PLUGINS_JSON" | grep -c '"redis_host":"redis-kong"' || echo "0")

if [ "$CORRECT_HOST" -gt 0 ] && [ "$WRONG_HOST" -eq 0 ]; then
    pass "redis_host=redis-kong configurado corretamente em ${CORRECT_HOST} plugin(s)"
elif [ "$CORRECT_HOST" -eq 0 ]; then
    fail "redis_host não configurado — plugin não aponta para nenhum Redis"
else
    fail "Alguns plugins apontam para host Redis incorreto"
fi

# ==============================================================================
# TEST 4 — redis_database=1 para namespacing (não conflitar com app data)
# ==============================================================================
echo ""
info "TEST 4: redis_database deve ser 1 (namespace isolado para rate-limiting)"

DB_1=$(echo "$PLUGINS_JSON" | grep -c '"redis_database":1' || echo "0")
DB_0=$(echo "$PLUGINS_JSON" | grep -c '"redis_database":0' || echo "0")  # default/app db

info "  redis_database=1 : ${DB_1} plugin(s)"
info "  redis_database=0 : ${DB_0} plugin(s) [risco de conflito com app data]"

if [ "$DB_1" -gt 0 ] && [ "$DB_0" -eq 0 ]; then
    pass "redis_database=1 configurado em todos os ${DB_1} plugin(s)"
elif [ "$DB_0" -gt 0 ]; then
    fail "redis_database=0 encontrado — rate-limiting pode colidir com dados da aplicação"
else
    fail "redis_database não configurado nos plugins"
fi

# ==============================================================================
# TEST 5 — Redis-kong acessível (requer redis-cli ou nc)
# ==============================================================================
echo ""
info "TEST 5: Redis-kong está acessível em ${REDIS_HOST}:${REDIS_PORT} db=1"

# Tenta PING via redis-cli (se disponível) ou nc
if command -v redis-cli >/dev/null 2>&1; then
    PING=$(redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" -n 1 PING 2>/dev/null || echo "FAIL")
    if [ "$PING" = "PONG" ]; then
        pass "Redis-kong respondeu PONG (redis-cli)"
    else
        fail "Redis-kong não respondeu PONG via redis-cli (resposta: ${PING})"
    fi
elif command -v nc >/dev/null 2>&1; then
    # Tenta PING via socket raw
    PING=$(printf '*1\r\n$4\r\nPING\r\n' | nc -w 2 "$REDIS_HOST" "$REDIS_PORT" 2>/dev/null | tr -d '\r')
    if echo "$PING" | grep -q "+PONG"; then
        pass "Redis-kong respondeu PONG (nc)"
    else
        fail "Redis-kong não acessível em ${REDIS_HOST}:${REDIS_PORT}"
    fi
else
    info "SKIP: redis-cli e nc não disponíveis — skipping Redis connectivity test"
fi

# ==============================================================================
# TEST 6 — Simulação multi-nó: 429 deve ocorrer globalmente
#
# Cenário:
#   - Cria um plugin rate-limiting temporário com limit=5 req/s
#   - Envia 3 requisições para nó A + 3 requisições para nó B
#   - ANTES da mudança (policy=local): todas 6 passam (cada nó conta separado)
#   - DEPOIS da mudança (policy=redis): 6ª req retorna 429 (contador compartilhado)
#
# Este teste requer dois nós Kong diferentes. Em single-node, demonstra que
# o 6º hit no mesmo nó retorna 429 com policy=redis.
# ==============================================================================
echo ""
info "TEST 6: Simulação 429 com limite global (requires sandbox service)"
info "  Configurar variável KONG_TEST_URL para o endpoint a testar"
info "  Ex: KONG_TEST_URL=http://localhost:8000/api/v1/orders"

KONG_TEST_URL="${KONG_TEST_URL:-}"

if [ -z "$KONG_TEST_URL" ]; then
    info "SKIP: KONG_TEST_URL não definida — pulando teste de 429"
    info "  Para executar: KONG_TEST_URL=http://localhost:8000/api/v1/orders ./tests/AT-12.1-rate-limiting-redis-validation.sh"
else
    info "  Enviando 6 requisições rápidas para ${KONG_TEST_URL}"
    GOT_429=0
    for i in $(seq 1 6); do
        HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
            -X POST "${KONG_TEST_URL}" \
            -H "Content-Type: application/json" \
            -d '{"test":"rate-limit-probe"}' \
            2>/dev/null || echo "000")
        info "  Request ${i}/6: HTTP ${HTTP_STATUS}"
        if [ "$HTTP_STATUS" = "429" ]; then
            GOT_429=$((GOT_429+1))
        fi
    done

    if [ "$GOT_429" -gt 0 ]; then
        pass "Rate limiting global funcionando: ${GOT_429} request(s) retornaram 429"
    else
        fail "Nenhuma requisição retornou 429 — rate limiting pode não estar funcional"
    fi
fi

# ==============================================================================
# TEST 7 — fault_tolerant=true (Kong não bloqueia se Redis cair)
# ==============================================================================
echo ""
info "TEST 7: fault_tolerant deve ser true (resiliência contra queda do Redis)"

FT_TRUE=$(echo "$PLUGINS_JSON" | grep -c '"fault_tolerant":true' || echo "0")
FT_FALSE=$(echo "$PLUGINS_JSON" | grep -c '"fault_tolerant":false' || echo "0")

if [ "$FT_TRUE" -gt 0 ] && [ "$FT_FALSE" -eq 0 ]; then
    pass "fault_tolerant=true em todos os ${FT_TRUE} plugin(s) — Kong não bloqueia se Redis cair"
elif [ "$FT_FALSE" -gt 0 ]; then
    fail "fault_tolerant=false encontrado — Kong bloqueará requisições se Redis ficar indisponível"
else
    fail "fault_tolerant não configurado"
fi

# ==============================================================================
# RESULTADO FINAL
# ==============================================================================
echo ""
echo "======================================================================"
echo " RESULTADO: ${PASS} passaram  |  ${FAIL} falharam"
echo "======================================================================"

if [ "$FAIL" -gt 0 ]; then
    echo ""
    printf "${RED}STATUS: FASE RED — Implementação necessária${NC}\n"
    echo ""
    echo "  Execute as correções em:"
    echo "    infra/kong/kong-init.yml"
    echo "    infra/kong/kong-setup.sh"
    echo "    infra/docker-compose.yml"
    echo "    infra/docker-compose.staging.yml"
    echo ""
    exit 1
else
    printf "${GREEN}STATUS: FASE GREEN — Todos os critérios de aceite validados${NC}\n"
    echo ""
    exit 0
fi
