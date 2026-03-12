# ================================================================================
# INFRASTRUCTURE VALIDATION SCRIPT - WINDOWS (PowerShell)
# ================================================================================
# Validates critical infrastructure for the Vibranium Platform.
#
# Environments:
#   dev   -> infra/docker-compose.dev.yml   (Kong DB-less, Keycloak com banco dedicado)
#   infra -> infra/docker-compose.yml       (Kong DB-mode, Keycloak no PostgreSQL compartilhado)
#
# Usage (executar a partir da RAIZ do projeto):
#   .\scripts\verify-infra.ps1                    # default: dev
#   .\scripts\verify-infra.ps1 -Environment dev
#   .\scripts\verify-infra.ps1 -Environment infra
#
# Exit code: 0 = ALL PASSED, 1 = FAILURE
# ================================================================================

param(
    [ValidateSet("dev", "infra")]
    [string]$Environment = "dev",
    [switch]$Verbose = $false
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Continue"

# ================================================================================
# LOGGING FUNCTIONS
# ================================================================================

function Write-Header {
    param([string]$Message)
    Write-Host "===============================================================" -ForegroundColor Blue
    Write-Host $Message -ForegroundColor Blue
    Write-Host "===============================================================" -ForegroundColor Blue
}

function Write-Success {
    param([string]$Message)
    Write-Host "  ✓ $Message" -ForegroundColor Green
    $script:PassedChecks++
}

function Write-Fail {
    param([string]$Message)
    Write-Host "  ✗ $Message" -ForegroundColor Red
    $script:FailedChecks++
}

function Write-Warn {
    param([string]$Message)
    Write-Host "  ⚠ $Message" -ForegroundColor Yellow
}

function Write-Info {
    param([string]$Message)
    Write-Host "  ℹ $Message" -ForegroundColor Cyan
}

# ================================================================================
# GLOBAL COUNTERS
# ================================================================================
$script:PassedChecks = 0
$script:FailedChecks = 0

# ================================================================================
# HELPER FUNCTIONS
# ================================================================================

function Test-CommandExists {
    param([string]$Command)
    $null = Get-Command $Command -ErrorAction SilentlyContinue
    return $?
}

# Check if a container is healthy via Docker healthcheck
function Test-ContainerHealthy {
    param([string]$Name)
    try {
        $status = docker inspect --format '{{.State.Health.Status}}' $Name 2>$null
        return $status -eq 'healthy'
    }
    catch { return $false }
}

# Execute a command inside a running container
function Invoke-DockerExec {
    param([string]$Container, [string]$Command)
    try {
        $result = docker exec $Container sh -c $Command 2>&1
        return $result
    }
    catch { return $null }
}

# Execute a PostgreSQL query via docker exec (no local psql required)
function Invoke-PgQuery {
    param(
        [string]$Container,
        [string]$User,
        [string]$Database,
        [string]$Query
    )
    try {
        $raw = docker exec $Container psql -U $User -d $Database -tc $Query 2>&1
        if ($raw -is [array]) { $raw = ($raw | Where-Object { $_ -is [string] }) -join "" }
        return $raw.ToString().Trim()
    }
    catch { return $null }
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
function Check-DevPostgres {
    Write-Info "Check 1/7: PostgreSQL (vibranium-postgres)..."

    if (-not (Test-ContainerHealthy "vibranium-postgres")) {
        Write-Fail "PostgreSQL container (vibranium-postgres) not running or not healthy"
        return
    }

    foreach ($db in @("vibranium_orders", "vibranium_wallet")) {
        $ok = Invoke-PgQuery -Container "vibranium-postgres" -User "postgres" -Database $db -Query "SELECT 1"
        if ($null -eq $ok -or $ok -notmatch "1") {
            Write-Fail "Database '$db' is not accessible"
            return
        }
    }

    Write-Success "PostgreSQL healthy — vibranium_orders + vibranium_wallet OK"
}

# 2. MongoDB: running + replica set rs0
function Check-DevMongoDB {
    Write-Info "Check 2/7: MongoDB + Replica Set (vibranium-mongodb)..."

    if (-not (Test-ContainerHealthy "vibranium-mongodb")) {
        Write-Fail "MongoDB container (vibranium-mongodb) not running or not healthy"
        return
    }

    $rs = Invoke-DockerExec -Container "vibranium-mongodb" -Command 'mongosh --quiet --eval "rs.status().ok" 2>/dev/null'
    if ($null -eq $rs -or "$rs" -notmatch "1") {
        Write-Fail "MongoDB replica set rs0 not initialized"
        return
    }

    Write-Success "MongoDB healthy — replica set rs0 active"
}

# 3. Redis: running + healthy
function Check-DevRedis {
    Write-Info "Check 3/7: Redis (vibranium-redis)..."

    if (-not (Test-ContainerHealthy "vibranium-redis")) {
        Write-Fail "Redis container (vibranium-redis) not running or not healthy"
        return
    }

    Write-Success "Redis healthy"
}

# 4. RabbitMQ: running + healthy
function Check-DevRabbitMQ {
    Write-Info "Check 4/7: RabbitMQ (vibranium-rabbitmq)..."

    if (-not (Test-ContainerHealthy "vibranium-rabbitmq")) {
        Write-Fail "RabbitMQ container (vibranium-rabbitmq) not running or not healthy"
        return
    }

    Write-Success "RabbitMQ healthy"
}

# 5. Keycloak: keycloak-db healthy + keycloak healthy
function Check-DevKeycloak {
    Write-Info "Check 5/7: Keycloak + Keycloak DB..."

    if (-not (Test-ContainerHealthy "vibranium-keycloak-db-dev")) {
        Write-Fail "Keycloak DB container (vibranium-keycloak-db-dev) not healthy"
        return
    }

    if (-not (Test-ContainerHealthy "vibranium-keycloak-dev")) {
        Write-Fail "Keycloak container (vibranium-keycloak-dev) not healthy"
        return
    }

    Write-Success "Keycloak + Keycloak DB healthy"
}

# 6. Kong: running + healthy (DB-less mode)
function Check-DevKong {
    Write-Info "Check 6/7: Kong DB-less (vibranium-kong-dev)..."

    if (-not (Test-ContainerHealthy "vibranium-kong-dev")) {
        Write-Fail "Kong container (vibranium-kong-dev) not healthy"
        return
    }

    Write-Success "Kong healthy (DB-less mode)"
}

# 7. E2E compose file valid
function Check-E2ECompose {
    Write-Info "Check 7/7: E2E test compose configuration..."

    $composePath = "tests/e2e/docker-compose.e2e.yml"
    if (-not (Test-Path $composePath)) {
        Write-Fail "$composePath not found (execute a partir do diretorio raiz do projeto)"
        return
    }

    $null = docker compose -f $composePath config 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Fail "$composePath has invalid configuration"
        return
    }

    Write-Success "E2E compose configuration is valid"
}

# ================================================================================
# INFRA ENVIRONMENT CHECKS (7)
# Container names: vibranium-postgresql, vibranium-kong, vibranium-keycloak,
#                  vibranium-rabbitmq, vibranium-redis-kong
# Kong runs in DB mode (PostgreSQL, schema 'kong' in vibranium_infra).
# Keycloak uses schema 'keycloak' in the shared vibranium_infra database.
# ================================================================================

# 1. Kong running & healthy
function Check-InfraKongHealth {
    Write-Info "Check 1/7: Kong (vibranium-kong)..."

    if (-not (Test-ContainerHealthy "vibranium-kong")) {
        Write-Fail "Kong container (vibranium-kong) not running or not healthy"
        return
    }

    $status = Invoke-DockerExec -Container "vibranium-kong" -Command "curl -sf http://localhost:8001/status"
    if ($null -eq $status -or $status -eq "") {
        Write-Fail "Kong Admin API not responding"
        return
    }

    Write-Success "Kong running and healthy"
}

# 2. Kong connected to PostgreSQL
function Check-InfraKongDb {
    Write-Info "Check 2/7: Kong -> PostgreSQL connection..."

    $dbStatus = Invoke-DockerExec -Container "vibranium-kong" -Command "curl -sf http://localhost:8001/status"
    if ($null -eq $dbStatus -or "$dbStatus" -notmatch '"reachable":\s*true') {
        Write-Fail "Kong cannot reach PostgreSQL"
        return
    }

    Write-Success "Kong connected to PostgreSQL"
}

# 3. Kong critical tables exist
function Check-InfraKongTables {
    Write-Info "Check 3/7: Kong tables in PostgreSQL..."

    $requiredTables = @("services", "routes", "plugins", "upstreams", "targets")
    foreach ($table in $requiredTables) {
        $exists = Invoke-PgQuery -Container "vibranium-postgresql" -User "postgres" -Database "vibranium_infra" `
            -Query "SELECT 1 FROM information_schema.tables WHERE table_schema='kong' AND table_name='$table'"
        if ($null -eq $exists -or $exists -notmatch "1") {
            Write-Fail "Kong table '$table' does not exist in schema 'kong'"
            return
        }
    }

    Write-Success "Kong critical tables exist (services, routes, plugins, upstreams, targets)"
}

# 4. Keycloak running & healthy (Keycloak 22 — health endpoint without /auth/ prefix)
function Check-InfraKeycloakHealth {
    Write-Info "Check 4/7: Keycloak (vibranium-keycloak)..."

    if (-not (Test-ContainerHealthy "vibranium-keycloak")) {
        Write-Fail "Keycloak container (vibranium-keycloak) not running or not healthy"
        return
    }

    Write-Success "Keycloak running and healthy"
}

# 5. Keycloak connected to PostgreSQL (keycloak schema)
function Check-InfraKeycloakDb {
    Write-Info "Check 5/7: Keycloak -> PostgreSQL connection..."

    $schemaExists = Invoke-PgQuery -Container "vibranium-postgresql" -User "postgres" -Database "vibranium_infra" `
        -Query "SELECT 1 FROM information_schema.schemata WHERE schema_name='keycloak'"
    if ($null -eq $schemaExists -or $schemaExists -notmatch "1") {
        Write-Fail "Keycloak schema does not exist in vibranium_infra"
        return
    }

    $count = Invoke-PgQuery -Container "vibranium-postgresql" -User "postgres" -Database "vibranium_infra" `
        -Query "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='keycloak'"
    if ($null -eq $count -or [int]$count -lt 5) {
        Write-Fail "Keycloak has not initialized tables (found: $count)"
        return
    }

    Write-Success "Keycloak connected to PostgreSQL (keycloak schema)"
}

# 6. Keycloak critical tables exist
function Check-InfraKeycloakTables {
    Write-Info "Check 6/7: Keycloak tables in PostgreSQL..."

    $requiredTables = @("user_entity", "realm", "client", "role_entity")
    foreach ($table in $requiredTables) {
        $exists = Invoke-PgQuery -Container "vibranium-postgresql" -User "postgres" -Database "vibranium_infra" `
            -Query "SELECT 1 FROM information_schema.tables WHERE table_schema='keycloak' AND table_name='$table'"
        if ($null -eq $exists -or $exists -notmatch "1") {
            Write-Fail "Keycloak table '$table' does not exist"
            return
        }
    }

    Write-Success "Keycloak critical tables exist (user_entity, realm, client, role_entity)"
}

# ================================================================================
# SYSTEM DEPENDENCIES CHECK
# ================================================================================
function Check-Dependencies {
    Write-Header "Checking System Dependencies"

    $ok = $true

    if (-not (Test-CommandExists "docker")) {
        Write-Host "  ✗ Docker is not installed" -ForegroundColor Red
        $ok = $false
    }
    else {
        Write-Host "  ✓ Docker is installed" -ForegroundColor Green
    }

    # Docker Compose v2 (docker compose, not docker-compose)
    try {
        $null = docker compose version 2>&1
        if ($LASTEXITCODE -eq 0) {
            Write-Host "  ✓ Docker Compose v2 available" -ForegroundColor Green
        }
        else {
            Write-Host "  ✗ Docker Compose v2 not available (expected: docker compose)" -ForegroundColor Red
            $ok = $false
        }
    }
    catch {
        Write-Host "  ✗ Docker Compose v2 not available" -ForegroundColor Red
        $ok = $false
    }

    return $ok
}

# ================================================================================
# MAIN EXECUTION
# ================================================================================
function Main {
    Write-Header "INFRASTRUCTURE VALIDATION — Environment: $($Environment.ToUpper())"

    if (-not (Check-Dependencies)) {
        Write-Host "  ✗ Missing critical dependencies — aborting" -ForegroundColor Red
        exit 1
    }

    Write-Host ""

    if ($Environment -eq "dev") {
        Write-Header "VALIDATING DEV INFRASTRUCTURE (7 Checks)"
        Write-Host ""
        Check-DevPostgres
        Check-DevMongoDB
        Check-DevRedis
        Check-DevRabbitMQ
        Check-DevKeycloak
        Check-DevKong
        Check-E2ECompose
    }
    else {
        Write-Header "VALIDATING INFRA (7 Checks)"
        Write-Host ""
        Check-InfraKongHealth
        Check-InfraKongDb
        Check-InfraKongTables
        Check-InfraKeycloakHealth
        Check-InfraKeycloakDb
        Check-InfraKeycloakTables
        Check-E2ECompose
    }

    Write-Host ""
    Write-Header "VALIDATION SUMMARY"

    Write-Host "  Passed: $($script:PassedChecks)" -ForegroundColor Green
    Write-Host "  Failed: $($script:FailedChecks)" -ForegroundColor Red
    Write-Host ""

    if ($script:FailedChecks -eq 0) {
        Write-Success "All $($script:PassedChecks) checks passed!"
        Write-Host ""
        exit 0
    }
    else {
        Write-Fail "Validation FAILED — $($script:FailedChecks) check(s) did not pass"
        Write-Host "  Review errors above and fix the infrastructure." -ForegroundColor Red
        Write-Host ""
        exit 1
    }
}

# Run main function
Main
