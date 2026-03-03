#!/bin/bash
# =============================================================================
# docker-entrypoint-override.sh — Wrapper do entrypoint oficial do mongo:7.0
# =============================================================================
# Problema: MongoDB 7 exige `security.keyFile` quando --auth e --replSet estão
# ativos juntos, mesmo num single-node replica set de desenvolvimento.
#
# Solução: Geramos um keyFile aleatório no boot do container e delegamos ao
# entrypoint original com o argumento --keyFile injetado.
#
# Lifecycle:
#   1. Gera /etc/mongod-keyfile (openssl rand -base64 756)
#   2. Ajusta permissões (chmod 400, chown mongodb)
#   3. Chama /usr/local/bin/docker-entrypoint.sh "$@"
#      → O entrypoint oficial sobe mongod em modo standalone/noauth para rodar
#        scripts de inicialização (init-mongo.js), depois reinicia com todos
#        os flags originais (--replSet rs0, --keyFile, etc.).
#
# ATENÇÃO: keyFile regenerado a cada boot é seguro para single-node dev —
#          nenhum outro membro precisa autenticar com este nó.
# =============================================================================
set -e

echo "[mongo-entrypoint] Gerando keyFile para auth do replica set..."
openssl rand -base64 756 > /etc/mongod-keyfile
chmod 400 /etc/mongod-keyfile
chown mongodb:mongodb /etc/mongod-keyfile
echo "[mongo-entrypoint] keyFile criado em /etc/mongod-keyfile"

echo "[mongo-entrypoint] Delegando ao entrypoint oficial do MongoDB..."
exec /usr/local/bin/docker-entrypoint.sh "$@"
