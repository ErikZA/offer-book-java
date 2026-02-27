#!/bin/bash
# =============================================================================
# SCRIPT DE INICIALIZAÇÃO CENTRALIZADA DOS BANCOS DE APLICAÇÃO
# =============================================================================
# Executado automaticamente pelo entrypoint do PostgreSQL uma única vez,
# no primeiro boot do container (via /docker-entrypoint-initdb.d/).
#
# Consolida a criação de TODOS os bancos de aplicação:
#   - vibranium_orders  → order-service  (Flyway gerencia as migrations)
#   - vibranium_wallet  → wallet-service (Flyway gerencia as migrations)
#
# Cada serviço de aplicação DEVE:
#   1. Aguardar o healthcheck do postgres com AMBOS os bancos validados
#   2. Deixar o Flyway executar as migrations na subida — NÃO criar tabelas aqui
#   3. Falhar imediatamente (HikariCP/Flyway) se o banco não existir
#
# Idempotente: usa CREATE DATABASE ... WHERE NOT EXISTS para re-execuções seguras.
# =============================================================================
set -e

# -----------------------------------------------------------------------------
# Função auxiliar: cria um banco de dados se não existir e configura extensões
# -----------------------------------------------------------------------------
create_database() {
    local DB_NAME=$1

    echo "[init-app-databases] Verificando banco '${DB_NAME}'..."

    # Cria o banco se não existir.
    # Nota: \gexec é metacomando psql — só funciona via stdin (heredoc), não via --command.
    psql -v ON_ERROR_STOP=1 \
         --username "$POSTGRES_USER" \
         --dbname   "$POSTGRES_DB" \
         <<-EOSQL
SELECT 'CREATE DATABASE ${DB_NAME}'
WHERE NOT EXISTS (
    SELECT FROM pg_catalog.pg_database
    WHERE datname = '${DB_NAME}'
)\gexec
EOSQL

    echo "[init-app-databases] Configurando extensões e permissões em '${DB_NAME}'..."

    psql -v ON_ERROR_STOP=1 \
         --username "$POSTGRES_USER" \
         --dbname   "${DB_NAME}" \
         <<-EOSQL
-- uuid-ossp: compatibilidade com libs legadas que usam uuid_generate_v4()
-- (gen_random_uuid() já nativo no PG13+, mas algumas dependências transitivas exigem este)
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- pgcrypto: gen_random_bytes e criptografia — usado em tokens e correlationId
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

GRANT ALL PRIVILEGES ON DATABASE ${DB_NAME} TO ${POSTGRES_USER};
GRANT ALL ON SCHEMA public TO ${POSTGRES_USER};

-- Garante que novas tabelas criadas pelo Flyway herdam as permissões corretas
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${POSTGRES_USER};

SELECT NOW() AS initialized_at,
       '${DB_NAME} pronto para Flyway migrations' AS status;
EOSQL

    echo "[init-app-databases] '${DB_NAME}' configurado com sucesso."
}

# =============================================================================
# Cria os bancos de aplicação
# =============================================================================
create_database "vibranium_orders"
create_database "vibranium_wallet"

echo "[init-app-databases] Todos os bancos de aplicação inicializados com sucesso."
echo "[init-app-databases] order-service e wallet-service devem aguardar o healthcheck"
echo "[init-app-databases] do postgres antes de subir (condição: service_healthy)."
