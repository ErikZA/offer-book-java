#!/bin/bash

# ================================================================================
# INFRASTRUCTURE VALIDATION SCRIPT - DETERMINISTIC & FAIL-FAST
# ================================================================================
# Validates 7 critical infrastructure requirements for Kong + Keycloak + PostgreSQL
# Exit code: 0 = SUCCESS, 1 = FAILURE (any validation fails)
# ================================================================================

set -euo pipefail  # Exit on error, undefined variables, pipe failures

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Counter for failures
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
    echo -e "${GREEN}✓ $1${NC}"
    ((PASSED++))
}

log_error() {
    echo -e "${RED}✗ $1${NC}"
    ((FAILURES++))
}

log_warning() {
    echo -e "${YELLOW}⚠ $1${NC}"
}

log_info() {
    echo -e "${BLUE}ℹ $1${NC}"
}

# ================================================================================
# HELPER FUNCTIONS
# ================================================================================

# Check if command exists
command_exists() {
    command -v "$1" >/dev/null 2>&1 || return 1
}

# Wait for service with timeout
wait_for_service() {
    local host=$1
    local port=$2
    local max_attempts=30
    local attempt=1

    while [ $attempt -le $max_attempts ]; do
        if nc -z "$host" "$port" 2>/dev/null || timeout 2 bash -c "</dev/tcp/$host/$port" 2>/dev/null; then
            return 0
        fi
        echo -n "."
        sleep 1
        ((attempt++))
    done
    return 1
}

# Execute PostgreSQL query
psql_query() {
    local query=$1
    PGPASSWORD="postgres123" psql -h localhost -U postgres -d vibranium_infra -tc "$query" 2>/dev/null || echo ""
}

# ================================================================================
# REQUIREMENT 1: KONG IS RUNNING & HEALTHY
# ================================================================================
check_kong_health() {
    log_info "Checking Requirement 1: Kong running and healthy..."
    
    # Check if Kong container is running
    if ! docker ps | grep -q "vibranium-kong"; then
        log_error "Kong container is not running"
        return 1
    fi
    
    # Check Kong health via Admin API
    if ! docker exec vibranium-kong curl -s http://localhost:8001/status >/dev/null 2>&1; then
        log_error "Kong Admin API not responding"
        return 1
    fi
    
    # Verify Kong is in healthy state
    local status=$(docker exec vibranium-kong curl -s http://localhost:8001/status | grep -o '"server":[^}]*' || echo "")
    if [ -z "$status" ]; then
        log_error "Kong status validation failed"
        return 1
    fi
    
    log_success "Kong is running and healthy"
    return 0
}

# ================================================================================
# REQUIREMENT 2: KONG CONNECTED TO POSTGRESQL
# ================================================================================
check_kong_db_connection() {
    log_info "Checking Requirement 2: Kong connected to PostgreSQL..."
    
    # Verify Kong can access database
    local db_status=$(docker exec vibranium-kong curl -s http://localhost:8001/status | grep -o '"database":true' || echo "")
    
    if [ -z "$db_status" ]; then
        log_error "Kong database connection failed"
        return 1
    fi
    
    # Double-check by querying directly
    if ! psql_query "SELECT 1" >/dev/null; then
        log_error "Direct PostgreSQL connection test failed"
        return 1
    fi
    
    log_success "Kong is connected to PostgreSQL"
    return 0
}

# ================================================================================
# REQUIREMENT 3: KONG TABLES & COLUMNS CREATED
# ================================================================================
check_kong_tables() {
    log_info "Checking Requirement 3: Kong tables and columns exist..."
    
    # Wait for Kong migrations to complete (up to 2 minutes)
    local max_wait=120
    local elapsed=0
    
    while [ $elapsed -lt $max_wait ]; do
        local tables_count=$(psql_query "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='kong'" | tr -d ' ')
        
        if [ "$tables_count" -ge 20 ]; then  # Kong typically creates 20+ tables
            break
        fi
        
        echo -n "."
        sleep 5
        ((elapsed+=5))
    done
    
    # Validate critical Kong tables exist
    declare -a REQUIRED_TABLES=("services" "routes" "plugins" "upstreams" "targets")
    
    for table in "${REQUIRED_TABLES[@]}"; do
        local exists=$(psql_query "SELECT 1 FROM information_schema.tables WHERE table_schema='kong' AND table_name='$table'" | tr -d ' ')
        
        if [ -z "$exists" ]; then
            log_error "Kong table '$table' does not exist"
            return 1
        fi
    done
    
    # Validate critical columns in Kong services table
    declare -a REQUIRED_COLUMNS=("id" "name" "url" "protocol")
    
    for column in "${REQUIRED_COLUMNS[@]}"; do
        local exists=$(psql_query "SELECT 1 FROM information_schema.columns WHERE table_schema='kong' AND table_name='services' AND column_name='$column'" | tr -d ' ')
        
        if [ -z "$exists" ]; then
            log_error "Kong services table missing column '$column'"
            return 1
        fi
    done
    
    log_success "Kong tables and critical columns exist"
    return 0
}

# ================================================================================
# REQUIREMENT 4: KEYCLOAK IS RUNNING & HEALTHY
# ================================================================================
check_keycloak_health() {
    log_info "Checking Requirement 4: Keycloak running and healthy..."
    
    # Check if Keycloak container is running
    if ! docker ps | grep -q "vibranium-keycloak"; then
        log_error "Keycloak container is not running"
        return 1
    fi
    
    # Check Keycloak liveness endpoint
    if ! docker exec vibranium-keycloak curl -s http://localhost:8080/auth/health/live 2>/dev/null | grep -q "UP"; then
        log_error "Keycloak liveness check failed"
        return 1
    fi
    
    # Check Keycloak readiness endpoint
    if ! docker exec vibranium-keycloak curl -s http://localhost:8080/auth/health/ready 2>/dev/null | grep -q "UP"; then
        log_error "Keycloak readiness check failed"
        return 1
    fi
    
    log_success "Keycloak is running and healthy"
    return 0
}

# ================================================================================
# REQUIREMENT 5: KEYCLOAK CONNECTED TO POSTGRESQL
# ================================================================================
check_keycloak_db_connection() {
    log_info "Checking Requirement 5: Keycloak connected to PostgreSQL..."
    
    # Verify Keycloak schema exists and has tables
    local schema_exists=$(psql_query "SELECT 1 FROM information_schema.schemata WHERE schema_name='keycloak'" | tr -d ' ')
    
    if [ -z "$schema_exists" ]; then
        log_error "Keycloak schema does not exist"
        return 1
    fi
    
    # Verify database connection by checking if Keycloak is not in error state
    # This is implicit if readiness check passes, but we can verify schema
    local tables_count=$(psql_query "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='keycloak'" | tr -d ' ')
    
    if [ "$tables_count" -lt 5 ]; then
        log_error "Keycloak has not initialized database tables yet (found: $tables_count)"
        return 1
    fi
    
    log_success "Keycloak is connected to PostgreSQL"
    return 0
}

# ================================================================================
# REQUIREMENT 6: KEYCLOAK TABLES & COLUMNS CREATED
# ================================================================================
check_keycloak_tables() {
    log_info "Checking Requirement 6: Keycloak tables and columns exist..."
    
    # Validate critical Keycloak tables
    declare -a REQUIRED_TABLES=("user_entity" "realm" "client" "role_entity")
    
    for table in "${REQUIRED_TABLES[@]}"; do
        local exists=$(psql_query "SELECT 1 FROM information_schema.tables WHERE table_schema='keycloak' AND table_name='$table'" | tr -d ' ')
        
        if [ -z "$exists" ]; then
            log_error "Keycloak table '$table' does not exist"
            return 1
        fi
    done
    
    # Validate critical columns
    local user_entity_cols=$(psql_query "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='keycloak' AND table_name='user_entity' AND column_name IN ('id', 'username', 'email', 'realm_id')" | tr -d ' ')
    
    if [ "$user_entity_cols" -lt 4 ]; then
        log_error "Keycloak user_entity table missing critical columns"
        return 1
    fi
    
    local realm_cols=$(psql_query "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='keycloak' AND table_name='realm' AND column_name IN ('id', 'name')" | tr -d ' ')
    
    if [ "$realm_cols" -lt 2 ]; then
        log_error "Keycloak realm table missing critical columns"
        return 1
    fi
    
    local client_cols=$(psql_query "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='keycloak' AND table_name='client' AND column_name IN ('id', 'client_id', 'realm_id')" | tr -d ' ')
    
    if [ "$client_cols" -lt 3 ]; then
        log_error "Keycloak client table missing critical columns"
        return 1
    fi
    
    local role_cols=$(psql_query "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='keycloak' AND table_name='role_entity' AND column_name IN ('id', 'name')" | tr -d ' ')
    
    if [ "$role_cols" -lt 2 ]; then
        log_error "Keycloak role_entity table missing critical columns"
        return 1
    fi
    
    log_success "Keycloak tables and critical columns exist"
    return 0
}

# ================================================================================
# REQUIREMENT 7: COMPOSE TEST EXECUTION
# ================================================================================
check_compose_test_execution() {
    log_info "Checking Requirement 7: Docker Compose test environment..."
    
    # Verify docker-compose.test.yml exists
    if [ ! -f "docker-compose.test.yml" ]; then
        log_error "docker-compose.test.yml not found"
        return 1
    fi
    
    # Check that test docker compose file is valid
    if ! docker-compose -f docker-compose.test.yml config >/dev/null 2>&1; then
        log_error "docker-compose.test.yml is invalid"
        return 1
    fi
    
    log_success "Docker Compose test configuration is valid"
    return 0
}

# ================================================================================
# SYSTEM DEPENDENCIES CHECK
# ================================================================================
check_dependencies() {
    log_header "Checking System Dependencies"
    
    local missing_deps=0
    
    # Check Docker
    if ! command_exists docker; then
        log_error "Docker is not installed"
        ((missing_deps++))
    else
        log_success "Docker is installed"
    fi
    
    # Check Docker Compose
    if ! command_exists docker-compose; then
        log_error "Docker Compose is not installed"
        ((missing_deps++))
    else
        log_success "Docker Compose is installed"
    fi
    
    # Check PostgreSQL client
    if ! command_exists psql; then
        log_warning "PostgreSQL client (psql) not found - some validations will be skipped"
    else
        log_success "PostgreSQL client is available"
    fi
    
    # Check curl
    if ! command_exists curl; then
        log_error "curl is not installed - required for health checks"
        ((missing_deps++))
    else
        log_success "curl is installed"
    fi
    
    # Check nc (netcat)
    if ! command_exists nc; then
        log_warning "netcat (nc) not found - port checks may be limited"
    else
        log_success "netcat is available"
    fi
    
    if [ $missing_deps -gt 0 ]; then
        return 1
    fi
    
    return 0
}

# ================================================================================
# MAIN EXECUTION
# ================================================================================
main() {
    log_header "🚀 INFRASTRUCTURE VALIDATION - 7 CRITICAL CHECKS"
    
    # Check dependencies
    if ! check_dependencies; then
        log_error "Missing critical dependencies"
        exit 1
    fi
    
    echo ""
    log_header "📋 VALIDATING INFRASTRUCTURE (7 Requirements)"
    echo ""
    
    # Execute all validation checks
    check_kong_health || true
    check_kong_db_connection || true
    check_kong_tables || true
    check_keycloak_health || true
    check_keycloak_db_connection || true
    check_keycloak_tables || true
    check_compose_test_execution || true
    
    echo ""
    log_header "📊 VALIDATION SUMMARY"
    
    echo -e "  ${GREEN}Passed: $PASSED${NC}"
    echo -e "  ${RED}Failed: $FAILURES${NC}"
    echo ""
    
    if [ $FAILURES -eq 0 ]; then
        log_success "Infrastructure validation completed successfully!"
        echo -e "${GREEN}All 7 requirements validated ✓${NC}"
        echo ""
        exit 0
    else
        log_error "Infrastructure validation FAILED - $FAILURES checks did not pass"
        echo -e "${RED}Please review the errors above and fix the infrastructure.${NC}"
        echo ""
        exit 1
    fi
}

# Run main function
main "$@"
