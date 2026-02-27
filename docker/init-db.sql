-- ================================================================================
-- PostgreSQL 16 INITIALIZATION SCRIPT - IDEMPOTENT
-- ================================================================================
-- Preparação básica de schemas e extensões
-- Kong: migrations bootstrap no docker-compose vai criar tabelas
-- Keycloak: Liquibase vai criar tabelas automaticamente
-- ================================================================================

-- ================================================================================
-- 1. ENABLE REQUIRED EXTENSIONS
-- ================================================================================
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ================================================================================
-- 2. KONG SCHEMA
-- ================================================================================
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.schemata WHERE schema_name = 'kong') THEN
    CREATE SCHEMA kong;
    GRANT USAGE ON SCHEMA kong TO postgres;
    GRANT CREATE ON SCHEMA kong TO postgres;
  END IF;
END
$$;

-- Kong schema permissions
ALTER SCHEMA kong OWNER TO postgres;
GRANT USAGE ON SCHEMA kong TO postgres;
GRANT CREATE ON SCHEMA kong TO postgres;
ALTER DEFAULT PRIVILEGES IN SCHEMA kong GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO postgres;

-- ================================================================================
-- 3. KEYCLOAK SCHEMA (EMPTY - Liquibase manages tables)
-- ================================================================================
-- Keycloak 22 uses Liquibase for automatic schema creation
-- We only create the schema here and let Keycloak handle table creation
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.schemata WHERE schema_name = 'keycloak') THEN
    CREATE SCHEMA keycloak;
    GRANT USAGE ON SCHEMA keycloak TO postgres;
    GRANT CREATE ON SCHEMA keycloak TO postgres;
  END IF;
END
$$;

-- Keycloak schema permissions
ALTER SCHEMA keycloak OWNER TO postgres;
GRANT USAGE ON SCHEMA keycloak TO postgres;
GRANT CREATE ON SCHEMA keycloak TO postgres;
ALTER DEFAULT PRIVILEGES IN SCHEMA keycloak GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO postgres;

-- ================================================================================
-- 4. PUBLIC SCHEMA HOUSEKEEPING
-- ================================================================================
-- Ensure vibranium_infra database is ready for Kong and Keycloak
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO postgres;

-- ================================================================================
-- 5. INITIALIZATION STATUS
-- ================================================================================
SELECT NOW() as initialization_timestamp;
SELECT 'Database initialization completed successfully' as status;
SELECT 'Kong will create its schema via: kong migrations bootstrap' as kong_migration_note;
SELECT 'Keycloak will create its schema via: Liquibase (automatic)' as keycloak_migration_note;
