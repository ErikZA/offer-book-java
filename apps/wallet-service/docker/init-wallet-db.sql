-- =============================================================================
-- Init script: vibranium_wallet database
-- Executado automaticamente pelo PostgreSQL no primeiro start do container.
-- (montado via volumes em docker-compose.dev.yml)
--
-- NÃO cria tabelas — isso é responsabilidade do Flyway na subida da aplicação.
-- Este script apenas garante as extensões e permissões necessárias.
-- =============================================================================

-- Extensão uuid-ossp: funções UUID (gen_random_uuid() já está nativa no PG13+,
-- mas uuid_generate_v4() de algumas libs legadas requer isso)
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- pgcrypto: usado por gen_random_bytes e criptografia de dados sensíveis
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- Garante que o schema public está com as permissões corretas
GRANT ALL PRIVILEGES ON DATABASE vibranium_wallet TO postgres;
GRANT ALL ON SCHEMA public TO postgres;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO postgres;

SELECT NOW() AS initialized_at, 'vibranium_wallet database ready for Flyway migrations' AS status;
