#!/bin/bash

# ================================================================================
# INFRASTRUCTURE VALIDATION SCRIPT - LINUX/macOS (Bash)
# ================================================================================
# Validates critical infrastructure for the Vibranium Platform.
#
# Environments:
#   dev   -> infra/docker-compose.dev.yml   (Kong DB-less, Keycloak com banco dedicado)
#   infra -> infra/docker-compose.yml       (Kong DB-mode, Keycloak no PostgreSQL compartilhado)
#
# Usage (executar a partir da RAIZ do projeto):
#   ./scripts/verify-infra.sh              # default: dev
#   ./scripts/verify-infra.sh dev
#   ./scripts/verify-infra.sh infra
#
# Exit code: 0 = ALL PASSED, 1 = FAILURE
# ================================================================================

set -uo pipefail  # Exit on undefined variables, pipe failures (no -e: we handle errors manually)

ENVIRONMENT="${1:-dev}"

# Validate environment argument
if [[ "$ENVIRONMENT" != "dev" && "$ENVIRONMENT" != "infra" ]]; then
    echo "Usage: $0 [dev|infra]"
    echo "  dev   — validates infra/docker-compose.dev.yml containers (default)"
    echo "  infra — validates infra/docker-compose.yml containers"
    exit 1
fi

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Counters
FAILURES=0
PASSED=0

# ================================================================================
# LOGGING FUNCTIONS
# ================================================================================

log_header() {
    echo -e "${BLUE}===============================================================${NC}"
    echo -e "${BLUE}$1${NC}"
    echo -e "${BLUE}===============================================================${NC}"
}

log_success() {
    echo -e "  ${GREEN}✓ $1${NC}"
    ((PASSED++))
}

log_fail() {
    echo -e "  ${RED}✗ $1${NC}"
    ((FAILURES++))
}

log_warn() {
    echo -e "  ${YELLOW}⚠ $1${NC}"
}

log_info() {
    echo -e "  ${BLUE}ℹ $1${NC}"
}

# ================================================================================
# HELPER FUNCTIONS
# ================================================================================

command_exists() {
    command -v "$1" >/dev/null 2>&1 || return 1
}

# Check if a container is healthy via Docker healthcheck
container_healthy() {
    local name=$1
    local status
    status=$(docker inspect --format '{{.State.Health.Status}}' "$name" 2>/dev/null) || return 1
    [[ "$status" == "healthy" ]]
}

# Execute a command inside a running container
docker_exec() {
    local container=$1
    shift
    docker exec "$container" sh -c "$*" 2>/dev/null
}

# Execute a PostgreSQL query via docker exec (no local psql required)
pg_query() {
    local container=$1
    local user=$2
    local database=$3
    local query=$4
    docker exec "$container" psql -U "$user" -d "$database" -tc "$query" 2>/dev/null | tr -d ' '
}

# ================================================================================
# DEV ENVIRONMENT CHECKS (7)
# Container names: vibranium-postgres, vibranium-mongodb, vibranium-redis,
#                  vibranium-rabbitmq, vibranium-keycloak-db-dev,
#                  vibranium-keycloak-dev, vibranium-kong-dev
# Kong runs in DB-less mode (no database checks).
# Keycloak uses a dedicated keycloak-db container (not shared PostgreSQL).
# ================================================================================

# 1. PostgreSQL: running + vibranium_orders + vibranium_wallet
check_dev_postgres() {
    log_info "Check 1/7: PostgreSQL (vibranium-postgres)..."

    if ! container_healthy "vibranium-postgres"; then
        log_fail "PostgreSQL container (vibranium-postgres) not running or not healthy"
        return
    fi

    for db in vibranium_orders vibranium_wallet; do
        local ok
        ok=$(pg_query "vibranium-postgres" "postgres" "$db" "SELECT 1")
        if [[ "$ok" != "1" ]]; then
            log_fail "Database '$db' is not accessible"
            return
        fi
    done

    log_success "PostgreSQL healthy — vibranium_orders + vibranium_wallet OK"
}

# 2. MongoDB: running + replica set rs0
check_dev_mongodb() {
    log_info "Check 2/7: MongoDB + Replica Set (vibranium-mongodb)..."

    if ! container_healthy "vibranium-mongodb"; then
        log_fail "MongoDB container (vibranium-mongodb) not running or not healthy"
        return
    fi

    local rs
    rs=$(docker_exec "vibranium-mongodb" 'mongosh --quiet --eval "rs.status().ok" 2>/dev/null')
    if [[ "$rs" != *"1"* ]]; then
        log_fail "MongoDB replica set rs0 not initialized"
        return
    fi

    log_success "MongoDB healthy — replica set rs0 active"
}

# 3. Redis: running + healthy
check_dev_redis() {
    log_info "Check 3/7: Redis (vibranium-redis)..."

    if ! container_healthy "vibranium-redis"; then
        log_fail "Redis container (vibranium-redis) not running or not healthy"
        return
    fi

    log_success "Redis healthy"
}

# 4. RabbitMQ: running + healthy
check_dev_rabbitmq() {
    log_info "Check 4/7: RabbitMQ (vibranium-rabbitmq)..."

    if ! container_healthy "vibranium-rabbitmq"; then
        log_fail "RabbitMQ container (vibranium-rabbitmq) not running or not healthy"
        return
    fi

    log_success "RabbitMQ healthy"
}

# 5. Keycloak: keycloak-db healthy + keycloak healthy
check_dev_keycloak() {
    log_info "Check 5/7: Keycloak + Keycloak DB..."

    if ! container_healthy "vibranium-keycloak-db-dev"; then
        log_fail "Keycloak DB container (vibranium-keycloak-db-dev) not healthy"
        return
    fi

    if ! container_healthy "vibranium-keycloak-dev"; then
        log_fail "Keycloak container (vibranium-keycloak-dev) not healthy"
        return
    fi

    log_success "Keycloak + Keycloak DB healthy"
}

# 6. Kong: running + healthy (DB-less mode)
check_dev_kong() {
    log_info "Check 6/7: Kong DB-less (vibranium-kong-dev)..."

    if ! container_healthy "vibranium-kong-dev"; then
        log_fail "Kong container (vibranium-kong-dev) not healthy"
        return
    fi

    log_success "Kong healthy (DB-less mode)"
}

# 7. E2E compose file valid
check_e2e_compose() {
    log_info "Check 7/7: E2E test compose configuration..."

    local compose_path="tests/e2e/docker-compose.e2e.yml"

    if [ ! -f "$compose_path" ]; then
        log_fail "$compose_path not found (execute a partir do diretorio raiz do projeto)"
        return
    fi

    if ! docker compose -f "$compose_path" config >/dev/null 2>&1; then
        log_fail "$compose_path has invalid configuration"
        return
    fi

    log_success "E2E compose configuration is valid"
}

# ================================================================================
# INFRA ENVIRONMENT CHECKS (7)
# Container names: vibranium-postgresql, vibranium-kong, vibranium-keycloak,
#                  vibranium-rabbitmq, vibranium-redis-kong
# Kong runs in DB mode (PostgreSQL, schema 'kong' in vibranium_infra).
# Keycloak uses schema 'keycloak' in the shared vibranium_infra database.
# ================================================================================

# 1. Kong running & healthy
check_infra_kong_health() {
    log_info "Check 1/7: Kong (vibranium-kong)..."

    if ! container_healthy "vibranium-kong"; then
        log_fail "Kong container (vibranium-kong) not running or not healthy"
        return
    fi

    local status
    status=$(docker_exec "vibranium-kong" "curl -sf http://localhost:8001/status")
    if [ -z "$status" ]; then
        log_fail "Kong Admin API not responding"
        return
    fi

    log_success "Kong running and healthy"
}

# 2. Kong connected to PostgreSQL
check_infra_kong_db() {
    log_info "Check 2/7: Kong -> PostgreSQL connection..."

    local db_status
    db_status=$(docker_exec "vibranium-kong" "curl -sf http://localhost:8001/status")
    if ! echo "$db_status" | grep -q '"reachable":true'; then
        log_fail "Kong cannot reach PostgreSQL"
        return
    fi

    log_success "Kong connected to PostgreSQL"
}

# 3. Kong critical tables exist
check_infra_kong_tables() {
    log_info "Check 3/7: Kong tables in PostgreSQL..."

    local tables=("services" "routes" "plugins" "upstreams" "targets")

    for table in "${tables[@]}"; do
        local exists
        exists=$(pg_query "vibranium-postgresql" "postgres" "vibranium_infra" \
            "SELECT 1 FROM information_schema.tables WHERE table_schema='kong' AND table_name='$table'")
        if [[ "$exists" != "1" ]]; then
            log_fail "Kong table '$table' does not exist in schema 'kong'"
            return
        fi
    done

    log_success "Kong critical tables exist (services, routes, plugins, upstreams, targets)"
}

# 4. Keycloak running & healthy (Keycloak 22 — health endpoint without /auth/ prefix)
check_infra_keycloak_health() {
    log_info "Check 4/7: Keycloak (vibranium-keycloak)..."

    if ! container_healthy "vibranium-keycloak"; then
        log_fail "Keycloak container (vibranium-keycloak) not running or not healthy"
        return
    fi

    log_success "Keycloak running and healthy"
}

# 5. Keycloak connected to PostgreSQL (keycloak schema)
check_infra_keycloak_db() {
    log_info "Check 5/7: Keycloak -> PostgreSQL connection..."

    local schema_exists
    schema_exists=$(pg_query "vibranium-postgresql" "postgres" "vibranium_infra" \
        "SELECT 1 FROM information_schema.schemata WHERE schema_name='keycloak'")
    if [[ "$schema_exists" != "1" ]]; then
        log_fail "Keycloak schema does not exist in vibranium_infra"
        return
    fi

    local count
    count=$(pg_query "vibranium-postgresql" "postgres" "vibranium_infra" \
        "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='keycloak'")
    if [[ -z "$count" || "$count" -lt 5 ]]; then
        log_fail "Keycloak has not initialized tables (found: ${count:-0})"
        return
    fi

    log_success "Keycloak connected to PostgreSQL (keycloak schema)"
}

# 6. Keycloak critical tables exist
check_infra_keycloak_tables() {
    log_info "Check 6/7: Keycloak tables in PostgreSQL..."

    local tables=("user_entity" "realm" "client" "role_entity")

    for table in "${tables[@]}"; do
        local exists
        exists=$(pg_query "vibranium-postgresql" "postgres" "vibranium_infra" \
            "SELECT 1 FROM information_schema.tables WHERE table_schema='keycloak' AND table_name='$table'")
        if [[ "$exists" != "1" ]]; then
            log_fail "Keycloak table '$table' does not exist"
            return
        fi
    done

    log_success "Keycloak critical tables exist (user_entity, realm, client, role_entity)"
}

# ================================================================================
# SYSTEM DEPENDENCIES CHECK
# ================================================================================
check_dependencies() {
    log_header "Checking System Dependencies"

    local ok=true

    if ! command_exists docker; then
        echo -e "  ${RED}✗ Docker is not installed${NC}"
        ok=false
    else
        echo -e "  ${GREEN}✓ Docker is installed${NC}"
    fi

    # Docker Compose v2 (docker compose, not docker-compose)
    if docker compose version >/dev/null 2>&1; then
        echo -e "  ${GREEN}✓ Docker Compose v2 available${NC}"
    else
        echo -e "  ${RED}✗ Docker Compose v2 not available (expected: docker compose)${NC}"
        ok=false
    fi

    $ok
}

# ================================================================================
# MAIN EXECUTION
# ================================================================================
main() {
    log_header "INFRASTRUCTURE VALIDATION — Environment: $(echo "$ENVIRONMENT" | tr '[:lower:]' '[:upper:]')"

    if ! check_dependencies; then
        echo -e "  ${RED}✗ Missing critical dependencies — aborting${NC}"
        exit 1
    fi

    echo ""

    if [[ "$ENVIRONMENT" == "dev" ]]; then
        log_header "VALIDATING DEV INFRASTRUCTURE (7 Checks)"
        echo ""
        check_dev_postgres
        check_dev_mongodb
        check_dev_redis
        check_dev_rabbitmq
        check_dev_keycloak
        check_dev_kong
        check_e2e_compose
    else
        log_header "VALIDATING INFRA (7 Checks)"
        echo ""
        check_infra_kong_health
        check_infra_kong_db
        check_infra_kong_tables
        check_infra_keycloak_health
        check_infra_keycloak_db
        check_infra_keycloak_tables
        check_e2e_compose
    fi

    echo ""
    log_header "VALIDATION SUMMARY"

    echo -e "  ${GREEN}Passed: $PASSED${NC}"
    echo -e "  ${RED}Failed: $FAILURES${NC}"
    echo ""

    if [ $FAILURES -eq 0 ]; then
        log_success "All $PASSED checks passed!"
        echo ""
        exit 0
    else
        log_fail "Validation FAILED — $FAILURES check(s) did not pass"
        echo -e "  ${RED}Review errors above and fix the infrastructure.${NC}"
        echo ""
        exit 1
    fi
}

# Run main function
main
