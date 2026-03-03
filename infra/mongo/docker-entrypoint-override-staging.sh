#!/bin/bash
# =============================================================================
# docker-entrypoint-override-staging.sh — Wrapper do entrypoint mongo:7.0
#                                         para replica set de 3 nós (staging)
# =============================================================================
#
# Diferença em relação ao dev (docker-entrypoint-override.sh):
#   - Dev:     gera keyFile ALEATÓRIO por boot (seguro: single-node, nenhum
#               membro externo precisa autenticar com este nó).
#   - Staging: usa keyFile FIXO compartilhado via MONGO_REPLICA_KEY.
#               Obrigatório: os 3 nós devem ter EXATAMENTE o mesmo conteúdo
#               no keyFile para que a intra-cluster auth do replica set funcione.
#               Se os keyFiles divergirem, os secundários rejeitam o primário
#               com "Authentication failed" e o replica set não se forma.
#
# Lifecycle:
#   1. Escreve MONGO_REPLICA_KEY em /etc/mongod-keyfile
#   2. Ajusta permissões exigidas pelo MongoDB (chmod 400, chown mongodb)
#   3. Delega ao entrypoint oficial com os flags passados pelo `command`
#      (--replSet rs0 --bind_ip_all --keyFile /etc/mongod-keyfile)
#
# Variável obrigatória via docker-compose environment:
#   MONGO_REPLICA_KEY — string base64-like, mínimo 6 caracteres.
#                       Deve ser IDÊNTICA nos 3 nós do replica set.
# =============================================================================
set -e

if [ -z "${MONGO_REPLICA_KEY}" ]; then
    echo "[mongo-entrypoint-staging] ERRO: MONGO_REPLICA_KEY não definida." >&2
    echo "[mongo-entrypoint-staging] Todos os 3 nós devem ter o mesmo valor." >&2
    exit 1
fi

echo "[mongo-entrypoint-staging] Configurando keyFile compartilhado para replica set rs0..."
printf '%s' "${MONGO_REPLICA_KEY}" > /etc/mongod-keyfile
chmod 400 /etc/mongod-keyfile
chown mongodb:mongodb /etc/mongod-keyfile
echo "[mongo-entrypoint-staging] keyFile gravado em /etc/mongod-keyfile"

echo "[mongo-entrypoint-staging] Delegando ao entrypoint oficial do MongoDB..."
exec /usr/local/bin/docker-entrypoint.sh "$@"
