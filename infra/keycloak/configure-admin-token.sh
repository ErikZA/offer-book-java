#!/bin/sh

set -eu

KCADM_CONFIG="${KCADM_CONFIG:-/tmp/kcadm.config}"
export KCADM_CONFIG

resolve_secret() {
    value="${1:-}"
    file_path="${2:-}"

    if [ -n "$value" ]; then
        printf '%s' "$value"
        return 0
    fi

    if [ -n "$file_path" ] && [ -r "$file_path" ]; then
        cat "$file_path"
        return 0
    fi

    return 1
}

ADMIN_USER="${KEYCLOAK_ADMIN:-${KC_BOOTSTRAP_ADMIN_USERNAME:-admin}}"
ADMIN_PASSWORD="$(
    resolve_secret "${KEYCLOAK_ADMIN_PASSWORD:-}" "${KEYCLOAK_ADMIN_PASSWORD_FILE:-}" ||
    resolve_secret "${KC_BOOTSTRAP_ADMIN_PASSWORD:-}" "${KC_BOOTSTRAP_ADMIN_PASSWORD_FILE:-}" ||
    true
)"

cleanup() {
    rm -f "$KCADM_CONFIG"
}

trap cleanup EXIT

/opt/keycloak/bin/kc.sh "$@" &
KEYCLOAK_PID=$!

if [ -z "$ADMIN_PASSWORD" ]; then
    echo "Skipping master realm admin token lifespan setup: admin password not available." >&2
    wait "$KEYCLOAK_PID"
    exit $?
fi

login_deadline=60
attempt=0
lifetime_updated=0

while [ "$attempt" -lt "$login_deadline" ]; do
    if ! kill -0 "$KEYCLOAK_PID" 2>/dev/null; then
        wait "$KEYCLOAK_PID"
        exit $?
    fi

    if [ -n "$ADMIN_PASSWORD" ] && /opt/keycloak/bin/kcadm.sh config credentials \
        --server http://127.0.0.1:8080 \
        --realm master \
        --user "$ADMIN_USER" \
        --password "$ADMIN_PASSWORD" >/dev/null 2>&1; then

        # ── master realm: garante lifespan mínimo de 60 min para token admin ───
        /opt/keycloak/bin/kcadm.sh update realms/master -s accessTokenLifespan=3600 >/dev/null
        echo "Configured master realm admin access token lifespan to 3600 seconds."

        # ── orderbook-realm: garante auto-registro e eventos habilitados ────────
        # Usa 'get' + 'update' idempotente: não falha se o realm já estiver correto.
        if /opt/keycloak/bin/kcadm.sh get realms/orderbook-realm >/dev/null 2>&1; then

            /opt/keycloak/bin/kcadm.sh update realms/orderbook-realm \
                -s registrationAllowed=true \
                -s registrationEmailAsUsername=false \
                -s resetPasswordAllowed=true \
                -s verifyEmail=false \
                -s loginWithEmailAllowed=true \
                -s duplicateEmailsAllowed=false \
                -s accessTokenLifespan=3600 \
                -s eventsEnabled=true \
                -s adminEventsEnabled=true \
                -s adminEventsDetailsEnabled=true \
                -s 'eventsListeners=["jboss-logging","keycloak-to-rabbitmq"]' \
                >/dev/null 2>&1 && \
                echo "orderbook-realm: registro, eventos e access token lifespan (3600s) configurados com sucesso." || \
                echo "AVISO: Falha ao configurar orderbook-realm — verifique os logs do Keycloak." >&2

        else
            echo "orderbook-realm ainda não existe — será criado via --import-realm." >&2
        fi

        lifetime_updated=1
        break
    fi


    attempt=$((attempt + 1))
    sleep 2
done

if [ "$lifetime_updated" -eq 0 ]; then
    echo "Unable to update master realm admin access token lifespan during startup." >&2
fi

wait "$KEYCLOAK_PID"
