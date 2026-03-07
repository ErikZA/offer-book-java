#!/bin/bash
# ================================================================================
# AT-12 — Script de Validação: Prometheus + Grafana Observability Stack
# ================================================================================
# Valida que Prometheus e Grafana estão operacionais e configurados corretamente.
#
# Uso:  bash tests/AT-12-observability-stack-validation.sh
# Pré:  docker compose -f infra/docker-compose.dev.yml up -d
# ================================================================================

set -euo pipefail

PASS=0
FAIL=0
TOTAL=0

# Cores para output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

check() {
    local description="$1"
    local result="$2"
    TOTAL=$((TOTAL + 1))
    if [ "$result" -eq 0 ]; then
        echo -e "  ${GREEN}✓${NC} $description"
        PASS=$((PASS + 1))
    else
        echo -e "  ${RED}✗${NC} $description"
        FAIL=$((FAIL + 1))
    fi
}

echo ""
echo "================================================================================"
echo " AT-12 — Observability Stack Validation (Prometheus + Grafana)"
echo "================================================================================"
echo ""

# --------------------------------------------------------------------------
# 1. Prometheus Health
# --------------------------------------------------------------------------
echo -e "${YELLOW}[1/6] Prometheus Health${NC}"

HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:9090/-/healthy 2>/dev/null || echo "000")
check "Prometheus is healthy (GET /-/healthy → 200)" "$([ "$HTTP_CODE" = "200" ] && echo 0 || echo 1)"

# --------------------------------------------------------------------------
# 2. Prometheus Targets (order-service e wallet-service UP)
# --------------------------------------------------------------------------
echo -e "${YELLOW}[2/6] Prometheus Scrape Targets${NC}"

TARGETS_RESPONSE=$(curl -s http://localhost:9090/api/v1/targets 2>/dev/null || echo "{}")

# Verifica se order-service target existe
ORDER_TARGET=$(echo "$TARGETS_RESPONSE" | grep -c '"order-service"' || true)
check "Prometheus target 'order-service' exists" "$([ "$ORDER_TARGET" -gt 0 ] && echo 0 || echo 1)"

# Verifica se wallet-service target existe
WALLET_TARGET=$(echo "$TARGETS_RESPONSE" | grep -c '"wallet-service"' || true)
check "Prometheus target 'wallet-service' exists" "$([ "$WALLET_TARGET" -gt 0 ] && echo 0 || echo 1)"

# Verifica targets UP (pode falhar se os serviços ainda não subiram)
ORDER_UP=$(echo "$TARGETS_RESPONSE" | grep -A5 '"order-service"' | grep -c '"up"' || true)
check "order-service target is UP" "$([ "$ORDER_UP" -gt 0 ] && echo 0 || echo 1)"

WALLET_UP=$(echo "$TARGETS_RESPONSE" | grep -A5 '"wallet-service"' | grep -c '"up"' || true)
check "wallet-service target is UP" "$([ "$WALLET_UP" -gt 0 ] && echo 0 || echo 1)"

# --------------------------------------------------------------------------
# 3. Prometheus Query — métrica de negócio disponível
# --------------------------------------------------------------------------
echo -e "${YELLOW}[3/6] Prometheus Metrics Query${NC}"

QUERY_RESPONSE=$(curl -s "http://localhost:9090/api/v1/query?query=vibranium_orders_created_total" 2>/dev/null || echo "{}")
QUERY_STATUS=$(echo "$QUERY_RESPONSE" | grep -c '"success"' || true)
check "Prometheus query 'vibranium_orders_created_total' returns success" "$([ "$QUERY_STATUS" -gt 0 ] && echo 0 || echo 1)"

# --------------------------------------------------------------------------
# 4. Grafana Health
# --------------------------------------------------------------------------
echo -e "${YELLOW}[4/6] Grafana Health${NC}"

GRAFANA_HEALTH=$(curl -s http://localhost:3000/api/health 2>/dev/null || echo "{}")
GRAFANA_OK=$(echo "$GRAFANA_HEALTH" | grep -c '"ok"' || true)
check "Grafana is healthy (GET /api/health → ok)" "$([ "$GRAFANA_OK" -gt 0 ] && echo 0 || echo 1)"

# --------------------------------------------------------------------------
# 5. Grafana Dashboards Provisioned (4 dashboards esperados)
# --------------------------------------------------------------------------
echo -e "${YELLOW}[5/6] Grafana Dashboard Provisioning${NC}"

DASHBOARDS_RESPONSE=$(curl -s -u admin:admin http://localhost:3000/api/search?type=dash-db 2>/dev/null || echo "[]")

# Contar dashboards
DASHBOARD_COUNT=$(echo "$DASHBOARDS_RESPONSE" | grep -o '"uid"' | wc -l || echo "0")
check "Grafana has provisioned dashboards (found: $DASHBOARD_COUNT, expected: >= 4)" "$([ "$DASHBOARD_COUNT" -ge 4 ] && echo 0 || echo 1)"

# Verificar cada dashboard específico
ORDER_FLOW=$(echo "$DASHBOARDS_RESPONSE" | grep -c 'vibranium-order-flow' || true)
check "Dashboard 'Vibranium — Order Flow' exists" "$([ "$ORDER_FLOW" -gt 0 ] && echo 0 || echo 1)"

WALLET_HEALTH=$(echo "$DASHBOARDS_RESPONSE" | grep -c 'vibranium-wallet-health' || true)
check "Dashboard 'Vibranium — Wallet Health' exists" "$([ "$WALLET_HEALTH" -gt 0 ] && echo 0 || echo 1)"

INFRASTRUCTURE=$(echo "$DASHBOARDS_RESPONSE" | grep -c 'vibranium-infrastructure' || true)
check "Dashboard 'Vibranium — Infrastructure' exists" "$([ "$INFRASTRUCTURE" -gt 0 ] && echo 0 || echo 1)"

SLA=$(echo "$DASHBOARDS_RESPONSE" | grep -c 'vibranium-sla' || true)
check "Dashboard 'Vibranium — SLA' exists" "$([ "$SLA" -gt 0 ] && echo 0 || echo 1)"

# --------------------------------------------------------------------------
# 6. Grafana Datasource — Prometheus auto-provisionado
# --------------------------------------------------------------------------
echo -e "${YELLOW}[6/6] Grafana Datasource Provisioning${NC}"

DS_RESPONSE=$(curl -s -u admin:admin http://localhost:3000/api/datasources 2>/dev/null || echo "[]")
PROM_DS=$(echo "$DS_RESPONSE" | grep -c '"Prometheus"' || true)
check "Grafana datasource 'Prometheus' is provisioned" "$([ "$PROM_DS" -gt 0 ] && echo 0 || echo 1)"

# --------------------------------------------------------------------------
# Resultado Final
# --------------------------------------------------------------------------
echo ""
echo "================================================================================"
echo -e " Results: ${GREEN}${PASS} passed${NC}, ${RED}${FAIL} failed${NC}, ${TOTAL} total"
echo "================================================================================"
echo ""

if [ "$FAIL" -gt 0 ]; then
    echo -e "${RED}FAILED${NC} — Some validations did not pass."
    echo "  Hint: Ensure all services are running with:"
    echo "    docker compose -f infra/docker-compose.dev.yml up -d"
    echo "  Wait for healthchecks to pass before re-running this script."
    exit 1
else
    echo -e "${GREEN}ALL PASSED${NC} — Observability stack is fully operational."
    echo ""
    echo "  Prometheus UI:  http://localhost:9090"
    echo "  Grafana UI:     http://localhost:3000  (admin/admin)"
    echo ""
    exit 0
fi
