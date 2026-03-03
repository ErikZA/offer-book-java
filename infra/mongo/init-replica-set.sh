#!/bin/bash
# =============================================================================
# init-replica-set.sh — Inicializa o replica set rs0 no MongoDB single-node
# =============================================================================
# Executado pelo serviço mongo-rs-init (docker-compose.dev.yml) APÓS o MongoDB
# estar healthy (healthcheck ping). Usa retry para tolerar latência de boot.
#
# Por que replica set mesmo em dev?
#   MongoDB exige replica set para criar sessões de transação. O bean
#   MongoTransactionManager falha com "Transaction numbers are only allowed on
#   a replica set member or mongos" em modo standalone.
# =============================================================================
set -e

MONGO_URI="mongodb://${MONGO_ROOT_USER:-admin}:${MONGO_ROOT_PASSWORD}@mongodb:27017/admin?authSource=admin"

echo "[mongo-rs-init] Aguardando MongoDB aceitar conexões..."
until mongosh "$MONGO_URI" --eval "db.runCommand({ping:1})" --quiet 2>/dev/null; do
    echo "[mongo-rs-init] Não está pronto ainda — tentando em 2s..."
    sleep 2
done
echo "[mongo-rs-init] MongoDB disponível."

# Verifica se o replica set já foi iniciado (evita re-initiate após restart)
RS_OK=$(mongosh "$MONGO_URI" \
    --eval "try { rs.status().ok } catch(e) { 0 }" --quiet 2>/dev/null | tail -1)

if [ "$RS_OK" = "1" ]; then
    echo "[mongo-rs-init] Replica set já iniciado — nenhuma ação necessária."
else
    echo "[mongo-rs-init] Iniciando replica set rs0..."
    mongosh "$MONGO_URI" --eval '
        rs.initiate({
            _id: "rs0",
            members: [{ _id: 0, host: "mongodb:27017" }]
        })
    '
    echo "[mongo-rs-init] rs.initiate() executado."

    # Aguarda o nó atingir estado PRIMARY antes de sair com sucesso
    echo "[mongo-rs-init] Aguardando estado PRIMARY..."
    until mongosh "$MONGO_URI" \
        --eval "rs.status().members[0].stateStr === 'PRIMARY'" --quiet 2>/dev/null \
        | grep -q "true"; do
        sleep 1
    done
    echo "[mongo-rs-init] MongoDB está PRIMARY — replica set pronto."
fi
