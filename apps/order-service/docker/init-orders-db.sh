#!/bin/bash
# =============================================================================
# Init script: cria o banco vibranium_orders e habilita extensões necessárias.
#
# Executado automaticamente pelo entrypoint do PostgreSQL via
# /docker-entrypoint-initdb.d/ (apenas no PRIMEIRO start do volume).
#
# Montado no docker-compose.dev.yml como:
#   00-init-orders.sh → roda ANTES do 01-init-wallet.sql (ordem alfabética)
#
# NÃO cria tabelas — isso é responsabilidade do Flyway no startup do
# order-service (V1__create_user_registry.sql, V2__create_orders.sql).
# =============================================================================
set -e

echo "[init-orders-db] Criando banco vibranium_orders..."

# Cria o banco se não existir
# Executado como POSTGRES_USER (postgres) contra o banco padrão POSTGRES_DB (vibranium_wallet)
psql -v ON_ERROR_STOP=1 \
     --username "$POSTGRES_USER" \
     --dbname   "$POSTGRES_DB" \
     --command  "SELECT 'CREATE DATABASE vibranium_orders'
                 WHERE NOT EXISTS (
                   SELECT FROM pg_catalog.pg_database WHERE datname = 'vibranium_orders'
                 )\gexec"

echo "[init-orders-db] Configurando extensões e permissões em vibranium_orders..."

# Habilita extensões e configura permissões no banco recém-criado
psql -v ON_ERROR_STOP=1 \
     --username "$POSTGRES_USER" \
     --dbname   "vibranium_orders" \
     <<-EOSQL

-- uuid-ossp: funções UUID (compatibilidade com libs legadas)
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- pgcrypto: gen_random_bytes, crypt — usados para tokens/correlationId
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- Permissões ao owner do banco
GRANT ALL PRIVILEGES ON DATABASE vibranium_orders TO $POSTGRES_USER;
GRANT ALL ON SCHEMA public TO $POSTGRES_USER;

-- Garante que novas tabelas (criadas pelo Flyway) herdam as permissões
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO $POSTGRES_USER;

-- Confirma inicialização (visível no log do container)
SELECT now() AS initialized_at,
       'vibranium_orders ready for Flyway migrations' AS status;

EOSQL

echo "[init-orders-db] vibranium_orders configurado com sucesso."
