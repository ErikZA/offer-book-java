#!/bin/bash
# =============================================================================
# init-replica-set-staging.sh — Inicializa o replica set rs0 com 3 nós
# =============================================================================
# Executado pelo serviço mongo-rs-init (docker-compose.staging.yml) APÓS os
# 3 nós MongoDB estarem healthy. Conecta a mongodb-1 (futuro PRIMARY preferido)
# e executa rs.initiate() com os 3 membros.
#
# Idempotente: verifica rs.status() antes de chamar rs.initiate() — seguro
# para restart do container (ex: docker compose up --force-recreate).
#
# Variáveis esperadas (passadas via environment no compose):
#   MONGO_ROOT_USER     — usuário admin (padrão: admin)
#   MONGO_ROOT_PASSWORD — senha admin   (padrão: admin123)
# =============================================================================
set -e

MONGO_HOST="${MONGO_HOST:-mongodb-1}"
MONGO_PORT="${MONGO_PORT:-27017}"
MONGO_URI="mongodb://${MONGO_ROOT_USER:-admin}:${MONGO_ROOT_PASSWORD:-admin123}@${MONGO_HOST}:${MONGO_PORT}/admin?authSource=admin"

# ---------------------------------------------------------------------------
# 1. Aguarda mongodb-1 aceitar conexões autenticadas
# ---------------------------------------------------------------------------
echo "[mongo-rs-init] Aguardando ${MONGO_HOST}:${MONGO_PORT} aceitar conexões..."
until mongosh "$MONGO_URI" --eval "db.runCommand({ping:1})" --quiet 2>/dev/null; do
    echo "[mongo-rs-init] Não está pronto ainda — tentando em 3s..."
    sleep 3
done
echo "[mongo-rs-init] ${MONGO_HOST} disponível."

# ---------------------------------------------------------------------------
# 2. Verifica se replica set já foi iniciado (evita re-initiate após restart)
# ---------------------------------------------------------------------------
RS_OK=$(mongosh "$MONGO_URI" \
    --eval "try { rs.status().ok } catch(e) { 0 }" --quiet 2>/dev/null | tail -1)

if [ "$RS_OK" = "1" ]; then
    echo "[mongo-rs-init] Replica set já iniciado — nenhuma ação necessária."
else
    echo "[mongo-rs-init] Iniciando replica set rs0 com 3 membros..."
    mongosh "$MONGO_URI" --eval '
        rs.initiate({
            _id: "rs0",
            members: [
                { _id: 0, host: "mongodb-1:27017" },
                { _id: 1, host: "mongodb-2:27017" },
                { _id: 2, host: "mongodb-3:27017" }
            ]
        })
    '
    echo "[mongo-rs-init] rs.initiate() executado — aguardando eleição de PRIMARY..."
fi

# ---------------------------------------------------------------------------
# 3. Aguarda um nó atingir estado PRIMARY antes de sair
#    (garante que order-service não conecte antes do replica set estar pronto)
# ---------------------------------------------------------------------------
echo "[mongo-rs-init] Verificando eleição do PRIMARY..."
ATTEMPTS=0
MAX_ATTEMPTS=30  # 30 * 3s = 90s máximo
until mongosh "$MONGO_URI" --quiet --eval \
    'db.adminCommand({isMaster:1}).ismaster' 2>/dev/null | grep -q "true"; do
    ATTEMPTS=$((ATTEMPTS + 1))
    if [ "$ATTEMPTS" -ge "$MAX_ATTEMPTS" ]; then
        echo "[mongo-rs-init] ERRO: PRIMARY não eleito após ${MAX_ATTEMPTS} tentativas." >&2
        # Imprime status para diagnóstico
        mongosh "$MONGO_URI" --eval "rs.status()" --quiet 2>/dev/null || true
        exit 1
    fi
    echo "[mongo-rs-init] Aguardando PRIMARY... (tentativa ${ATTEMPTS}/${MAX_ATTEMPTS})"
    sleep 3
done

echo "[mongo-rs-init] PRIMARY eleito — replica set rs0 pronto (1 PRIMARY + 2 SECONDARY)."
