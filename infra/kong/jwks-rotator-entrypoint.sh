#!/bin/sh
# ==============================================================================
# jwks-rotator-entrypoint.sh — Loop de verificação JWKS a cada 6 horas
#
# Estratégia (sidecar vs CronJob):
#   - Ambiente: Docker Compose (sem Kubernetes disponível)
#   - Padrão: container sidecar com loop infinito + sleep
#   - Em Kubernetes, equivaleria a um CronJob com schedule: "0 */6 * * *"
#
# Comportamento:
#   1. Executa jwks-rotation.sh imediatamente no startup (sincronia inicial)
#   2. Aguarda ROTATION_INTERVAL segundos (default: 21600 = 6 horas)
#   3. Repete indefinidamente
#
# O script de rotação é idempotente: se o kid não mudou, não faz nenhuma
# chamada à Admin API do Kong. O intervalo de 6h é conservador e deliberado
# (veja trade-offs documentados em docs/architecture/).
#
# Variáveis de ambiente:
#   ROTATION_INTERVAL  — Intervalo em segundos (default: 21600 = 6 horas)
#   Todas as variáveis de jwks-rotation.sh também se aplicam aqui
# ==============================================================================

ROTATION_INTERVAL="${ROTATION_INTERVAL:-21600}"

log_json_entrypoint() {
    LEVEL="$1"; EVENT="$2"; shift 2
    EXTRAS=""
    for KV in "$@"; do EXTRAS="${EXTRAS},${KV}"; done
    printf '{"ts":"%s","level":"%s","event":"%s","service":"jwks-rotator"%s}\n' \
        "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$LEVEL" "$EVENT" "$EXTRAS"
}

log_json_entrypoint "info" "rotator_started" \
    '"interval_seconds":'"${ROTATION_INTERVAL}"'' \
    '"interval_human":"'"$(( ROTATION_INTERVAL / 3600 ))h"'"' \
    '"kong_admin":"'"${KONG_ADMIN_URL:-http://kong:8001}"'"' \
    '"keycloak":"'"${KEYCLOAK_URL:-http://keycloak:8080}"'/realms/'"${KEYCLOAK_REALM:-orderbook-realm}"'"'

# ==============================================================================
# Aguarda Kong e Keycloak estarem disponíveis antes da primeira execução
# (o sidecar pode subir antes do kong-init terminar no startup inicial)
# ==============================================================================
WAIT_MAX=60
WAIT_N=0
while [ $WAIT_N -lt $WAIT_MAX ]; do
    KONG_STATUS=$(curl -sf -o /dev/null -w "%{http_code}" \
        --max-time 5 \
        "${KONG_ADMIN_URL:-http://kong:8001}/status" 2>/dev/null || echo "000")
    KC_STATUS=$(curl -sf -o /dev/null -w "%{http_code}" \
        --max-time 5 \
        "${KEYCLOAK_URL:-http://keycloak:8080}/health/live" 2>/dev/null || echo "000")

    if [ "$KONG_STATUS" = "200" ] && [ "$KC_STATUS" = "200" ]; then
        log_json_entrypoint "info" "dependencies_ready" \
            '"kong_status":"'"${KONG_STATUS}"'"' \
            '"keycloak_status":"'"${KC_STATUS}"'"'
        break
    fi

    WAIT_N=$((WAIT_N + 1))
    log_json_entrypoint "info" "waiting_for_dependencies" \
        '"attempt":'"${WAIT_N}"'' \
        '"max_attempts":'"${WAIT_MAX}"'' \
        '"kong_status":"'"${KONG_STATUS}"'"' \
        '"keycloak_status":"'"${KC_STATUS}"'"'
    sleep 5
done

if [ $WAIT_N -ge $WAIT_MAX ]; then
    log_json_entrypoint "error" "dependencies_timeout" \
        '"max_attempts":'"${WAIT_MAX}"''
    exit 1
fi

# ==============================================================================
# Loop principal: executa rotação e aguarda o intervalo configurado
# ==============================================================================
RUN_COUNT=0
while true; do
    RUN_COUNT=$((RUN_COUNT + 1))
    log_json_entrypoint "info" "rotation_cycle_start" \
        '"run":'"${RUN_COUNT}"''

    # Executa o script de rotação; falhas individuais não derrubam o loop
    /jwks-rotation.sh || \
        log_json_entrypoint "warn" "rotation_cycle_failed" \
            '"run":'"${RUN_COUNT}"'' \
            '"action":"will_retry_next_interval"'

    NEXT_RUN_TS=$(date -u -d "+${ROTATION_INTERVAL} seconds" '+%Y-%m-%dT%H:%M:%SZ' \
        2>/dev/null || \
        date -u '+%Y-%m-%dT%H:%M:%SZ')  # fallback: Alpine busybox date não suporta -d

    log_json_entrypoint "info" "rotation_cycle_done" \
        '"run":'"${RUN_COUNT}"'' \
        '"next_check_in_seconds":'"${ROTATION_INTERVAL}"'' \
        '"next_check_approx":"'"${NEXT_RUN_TS}"'"'

    sleep "${ROTATION_INTERVAL}"
done
