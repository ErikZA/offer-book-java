#!/bin/bash
# =============================================================================
# pg-replica-entrypoint.sh — Entrypoint customizado para nós standby (hot_standby)
# =============================================================================
# Substitui o docker-entrypoint.sh padrão da imagem postgres:16-alpine.
# Em vez de executar initdb, clona o primary via pg_basebackup e configura o
# nó como hot_standby (somente leitura) via streaming replication (PG 12+ WAL).
#
# Variáveis de ambiente necessárias (injetadas pelo docker-compose):
#   PGDATA                      — diretório de dados (default: /var/lib/postgresql/data)
#   POSTGRES_PRIMARY_HOST       — nome DNS do primary (default: postgres-primary)
#   POSTGRES_PRIMARY_PORT       — porta do primary (default: 5432)
#   POSTGRES_REPLICATION_USER   — usuário de replicação (default: replicator)
#   POSTGRES_REPLICATION_PASSWORD — senha do usuário de replicação (obrigatória)
#
# Behavior em reinício de container:
#   Se PGDATA já contém PG_VERSION (clone anterior), pula pg_basebackup e
#   reinicia diretamente no modo standby (standby.signal já existe).
# =============================================================================
set -e

PGDATA="${PGDATA:-/var/lib/postgresql/data}"
PG_PRIMARY_HOST="${POSTGRES_PRIMARY_HOST:-postgres-primary}"
PG_PRIMARY_PORT="${POSTGRES_PRIMARY_PORT:-5432}"
REPLICATION_USER="${POSTGRES_REPLICATION_USER:-replicator}"
REPLICATION_PASS="${POSTGRES_REPLICATION_PASSWORD:?POSTGRES_REPLICATION_PASSWORD é obrigatória para configurar o standby}"

# --------------------------------------------------------------------------
# Passo 0 — garantir que PGDATA pertence ao usuário postgres.
# Volumes Docker são montados como root; a imagem usa su-exec para rebaixar.
# --------------------------------------------------------------------------
if [ "$(id -u)" = '0' ]; then
    echo "[replica] Ajustando permissões de $PGDATA..."
    mkdir -p "$PGDATA"
    chown -R postgres:postgres "$PGDATA"
    chmod 700 "$PGDATA"
    # Re-executa este script como usuário postgres (su-exec disponível no alpine)
    exec su-exec postgres "$0" "$@"
fi

# --------------------------------------------------------------------------
# Passo 1 — verificar se o nó já foi inicializado (reinício de container).
# Nesse caso, standby.signal e postgresql.auto.conf já estão no lugar.
# --------------------------------------------------------------------------
if [ -s "$PGDATA/PG_VERSION" ]; then
    echo "[replica] Diretório de dados já inicializado — iniciando standby diretamente..."
    exec postgres
fi

# --------------------------------------------------------------------------
# Passo 2 — aguardar o primary aceitar conexões antes do pg_basebackup.
# pg_isready sai com 0 quando o servidor está aceitando conexões.
# --------------------------------------------------------------------------
echo "[replica] Aguardando primary em $PG_PRIMARY_HOST:$PG_PRIMARY_PORT..."
until pg_isready -h "$PG_PRIMARY_HOST" -p "$PG_PRIMARY_PORT"; do
    echo "[replica] Primary ainda não está pronto — tentando novamente em 3s..."
    sleep 3
done
echo "[replica] Primary pronto."

# --------------------------------------------------------------------------
# Passo 3 — clonar o primary via pg_basebackup.
# --format=plain: copia arquivos diretamente (sem tar), compatível com PGDATA.
# --wal-method=stream: transmite WAL durante o backup (garante consistência).
# --progress: log de progresso no stderr.
# --no-password: usa PGPASSWORD da env, não solicita senha interativa.
# NÃO usamos --write-recovery-conf (-R) porque escrevemos o primary_conninfo
# manualmente abaixo, garantindo que a senha seja incluída no conninfo.
# --------------------------------------------------------------------------
echo "[replica] Executando pg_basebackup a partir de $PG_PRIMARY_HOST..."
PGPASSWORD="$REPLICATION_PASS" pg_basebackup \
    --host="$PG_PRIMARY_HOST" \
    --port="$PG_PRIMARY_PORT" \
    --username="$REPLICATION_USER" \
    --pgdata="$PGDATA" \
    --format=plain \
    --wal-method=stream \
    --progress \
    --no-password

echo "[replica] pg_basebackup concluído."

# --------------------------------------------------------------------------
# Passo 4 — configurar streaming replication no modo hot_standby (PG 12+).
#
# standby.signal: arquivo vazio que sinaliza ao postgres para iniciar como
# standby. Substitui recovery.conf que foi removido no PG 12.
#
# postgresql.auto.conf: tem precedência sobre postgresql.conf. Escrevemos
# primary_conninfo com a senha e habilitamos hot_standby (leitura nas réplicas).
#
# application_name=$(hostname): identifica a réplica em pg_stat_replication,
# facilitando o diagnóstico de qual instância está conectada.
# --------------------------------------------------------------------------
echo "[replica] Configurando standby (standby.signal + postgresql.auto.conf)..."

# standby.signal indica ao postgres para operar como hot_standby
touch "$PGDATA/standby.signal"

# Append ao auto.conf (evita sobrescrever configs do basebackup)
cat >> "$PGDATA/postgresql.auto.conf" <<EOF

# --- Streaming Replication (gerado por pg-replica-entrypoint.sh) ---
primary_conninfo = 'host=$PG_PRIMARY_HOST port=$PG_PRIMARY_PORT user=$REPLICATION_USER password=$REPLICATION_PASS application_name=$(hostname)'
# hot_standby=on permite consultas SELECT em réplicas enquanto em recovery
hot_standby = on
EOF

echo "[replica] Configuração de standby completa — iniciando PostgreSQL..."
exec postgres
