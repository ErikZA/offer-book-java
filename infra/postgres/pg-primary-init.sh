#!/bin/bash
# =============================================================================
# pg-primary-init.sh — Inicialização do nó primário para streaming replication
# =============================================================================
# Executado via /docker-entrypoint-initdb.d/ após o initdb do postgres:16-alpine.
# O entrypoint oficial já criou o banco $POSTGRES_DB e o usuário $POSTGRES_USER.
#
# Responsabilidades:
#   1. Criar o usuário de replicação `replicator`
#   2. Liberar conexões de replicação em pg_hba.conf
#
# Nota: wal_level, max_wal_senders e max_replication_slots são configurados via
# `command: postgres -c ...` no docker-compose, que tem precedência sobre postgresql.conf.
# =============================================================================
set -e

echo "[pg-primary-init] Criando usuário de replicação 'replicator'..."
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    -- Usuário dedicado exclusivamente para streaming replication.
    -- REPLICATION privilege permite conexões pg_basebackup e WAL streaming.
    CREATE USER replicator WITH REPLICATION ENCRYPTED PASSWORD '${POSTGRES_REPLICATION_PASSWORD}';
EOSQL

# Permite que os containers das réplicas se conectem via protocolo de replicação.
# `all` no campo de endereço abrange qualquer IP dentro da rede Docker interna.
echo "[pg-primary-init] Configurando pg_hba.conf para conexões de replicação..."
echo "host    replication     replicator      all             md5" >> "$PGDATA/pg_hba.conf"

echo "[pg-primary-init] Configuração do primary concluída."
echo "[pg-primary-init]   wal_level=replica         (via docker-compose command)"
echo "[pg-primary-init]   max_wal_senders=3          (via docker-compose command)"
echo "[pg-primary-init]   max_replication_slots=2    (via docker-compose command)"
echo "[pg-primary-init]   usuário replicator criado"
echo "[pg-primary-init]   pg_hba.conf atualizado"
