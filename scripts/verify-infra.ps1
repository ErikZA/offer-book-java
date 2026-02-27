# ================================================================================
# INFRASTRUCTURE VALIDATION SCRIPT - WINDOWS (PowerShell)
# ================================================================================
# Validates 7 critical infrastructure requirements for Kong + Keycloak + PostgreSQL
# Exit code: 0 = SUCCESS, 1 = FAILURE (any validation fails)
# ================================================================================

param(
    [switch]$Verbose = $false
)

# Strict mode
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
    Write-Host "✓ $Message" -ForegroundColor Green
    $script:PassedChecks++
}

function Write-Error-Custom {
    param([string]$Message)
    Write-Host "✗ $Message" -ForegroundColor Red
    $script:FailedChecks++
}

function Write-Warning-Custom {
    param([string]$Message)
    Write-Host "⚠ $Message" -ForegroundColor Yellow
}

function Write-Info {
    param([string]$Message)
    Write-Host "ℹ $Message" -ForegroundColor Cyan
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

function Invoke-DockerExec {
    param(
        [string]$Container,
        [string]$Command
    )
    
    try {
        $result = docker exec $Container sh -c $Command 2>&1
        return $result
    }
    catch {
        return $null
    }
}

function Invoke-PostgresQuery {
    param([string]$Query)
    
    try {
        $env:PGPASSWORD = "postgres123"
        $result = psql -h localhost -U postgres -d vibranium_infra -tc $Query 2>$null
        Remove-Item env:PGPASSWORD
        return $result.Trim()
    }
    catch {
        return $null
    }
}

function Test-DockerContainerRunning {
    param([string]$ContainerPattern)
    
    try {
        $running = docker ps 2>$null | Select-String $ContainerPattern
        return $null -ne $running
    }
    catch {
        return $false
    }
}

# ================================================================================
# REQUIREMENT 1: KONG IS RUNNING & HEALTHY
# ================================================================================
function Check-KongHealth {
    Write-Info "Checking Requirement 1: Kong running and healthy..."
    
    # Check if Kong container is running
    if (-not (Test-DockerContainerRunning "vibranium-kong")) {
        Write-Error-Custom "Kong container is not running"
        return $false
    }
    
    # Check Kong health via Admin API
    try {
        $status = Invoke-DockerExec -Container "vibranium-kong" -Command "curl -s http://localhost:8001/status"
        
        if ($null -eq $status -or $status -eq "") {
            Write-Error-Custom "Kong Admin API not responding"
            return $false
        }
        
        Write-Success "Kong is running and healthy"
        return $true
    }
    catch {
        Write-Error-Custom "Kong health check failed: $_"
        return $false
    }
}

# ================================================================================
# REQUIREMENT 2: KONG CONNECTED TO POSTGRESQL
# ================================================================================
function Check-KongDbConnection {
    Write-Info "Checking Requirement 2: Kong connected to PostgreSQL..."
    
    try {
        # Verify Kong can access database
        $status = Invoke-DockerExec -Container "vibranium-kong" -Command "curl -s http://localhost:8001/status | grep -o '\"database\":true'"
        
        if ($null -eq $status -or $status -eq "") {
            Write-Error-Custom "Kong database connection failed"
            return $false
        }
        
        # Double-check by querying directly
        $pgCheck = Invoke-PostgresQuery -Query "SELECT 1"
        
        if ($null -eq $pgCheck) {
            Write-Error-Custom "Direct PostgreSQL connection test failed"
            return $false
        }
        
        Write-Success "Kong is connected to PostgreSQL"
        return $true
    }
    catch {
        Write-Error-Custom "Kong DB connection check failed: $_"
        return $false
    }
}

# ================================================================================
# REQUIREMENT 3: KONG TABLES & COLUMNS CREATED
# ================================================================================
function Check-KongTables {
    Write-Info "Checking Requirement 3: Kong tables and columns exist..."
    
    try {
        # Wait for Kong migrations to complete (up to 2 minutes)
        $maxWait = 120
        $elapsed = 0
        
        while ($elapsed -lt $maxWait) {
            $tablesCount = Invoke-PostgresQuery -Query "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='kong'"
            $tablesCount = [int]$tablesCount.Trim()
            
            if ($tablesCount -ge 20) {  # Kong typically creates 20+ tables
                break
            }
            
            Write-Host -NoNewline "."
            Start-Sleep -Seconds 5
            $elapsed += 5
        }
        
        Write-Host ""  # New line
        
        # Validate critical Kong tables exist
        $requiredTables = @("services", "routes", "plugins", "upstreams", "targets")
        
        foreach ($table in $requiredTables) {
            $exists = Invoke-PostgresQuery -Query "SELECT 1 FROM information_schema.tables WHERE table_schema='kong' AND table_name='$table'"
            
            if ($null -eq $exists -or $exists -eq "") {
                Write-Error-Custom "Kong table '$table' does not exist"
                return $false
            }
        }
        
        # Validate critical columns in Kong services table
        $requiredColumns = @("id", "name", "url", "protocol")
        
        foreach ($column in $requiredColumns) {
            $exists = Invoke-PostgresQuery -Query "SELECT 1 FROM information_schema.columns WHERE table_schema='kong' AND table_name='services' AND column_name='$column'"
            
            if ($null -eq $exists -or $exists -eq "") {
                Write-Error-Custom "Kong services table missing column '$column'"
                return $false
            }
        }
        
        Write-Success "Kong tables and critical columns exist"
        return $true
    }
    catch {
        Write-Error-Custom "Kong tables check failed: $_"
        return $false
    }
}

# ================================================================================
# REQUIREMENT 4: KEYCLOAK IS RUNNING & HEALTHY
# ================================================================================
function Check-KeycloakHealth {
    Write-Info "Checking Requirement 4: Keycloak running and healthy..."
    
    try {
        # Check if Keycloak container is running
        if (-not (Test-DockerContainerRunning "vibranium-keycloak")) {
            Write-Error-Custom "Keycloak container is not running"
            return $false
        }
        
        # Check Keycloak liveness endpoint
        $liveness = Invoke-DockerExec -Container "vibranium-keycloak" -Command "curl -s http://localhost:8080/auth/health/live"
        
        if ($null -eq $liveness -or $liveness -notmatch "UP") {
            Write-Error-Custom "Keycloak liveness check failed"
            return $false
        }
        
        # Check Keycloak readiness endpoint
        $readiness = Invoke-DockerExec -Container "vibranium-keycloak" -Command "curl -s http://localhost:8080/auth/health/ready"
        
        if ($null -eq $readiness -or $readiness -notmatch "UP") {
            Write-Error-Custom "Keycloak readiness check failed"
            return $false
        }
        
        Write-Success "Keycloak is running and healthy"
        return $true
    }
    catch {
        Write-Error-Custom "Keycloak health check failed: $_"
        return $false
    }
}

# ================================================================================
# REQUIREMENT 5: KEYCLOAK CONNECTED TO POSTGRESQL
# ================================================================================
function Check-KeycloakDbConnection {
    Write-Info "Checking Requirement 5: Keycloak connected to PostgreSQL..."
    
    try {
        # Verify Keycloak schema exists and has tables
        $schemaExists = Invoke-PostgresQuery -Query "SELECT 1 FROM information_schema.schemata WHERE schema_name='keycloak'"
        
        if ($null -eq $schemaExists -or $schemaExists -eq "") {
            Write-Error-Custom "Keycloak schema does not exist"
            return $false
        }
        
        # Verify database connection by checking table count
        $tablesCount = Invoke-PostgresQuery -Query "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='keycloak'"
        $tablesCount = [int]$tablesCount.Trim()
        
        if ($tablesCount -lt 5) {
            Write-Error-Custom "Keycloak has not initialized database tables yet (found: $tablesCount)"
            return $false
        }
        
        Write-Success "Keycloak is connected to PostgreSQL"
        return $true
    }
    catch {
        Write-Error-Custom "Keycloak DB connection check failed: $_"
        return $false
    }
}

# ================================================================================
# REQUIREMENT 6: KEYCLOAK TABLES & COLUMNS CREATED
# ================================================================================
function Check-KeycloakTables {
    Write-Info "Checking Requirement 6: Keycloak tables and columns exist..."
    
    try {
        # Validate critical Keycloak tables
        $requiredTables = @("user_entity", "realm", "client", "role_entity")
        
        foreach ($table in $requiredTables) {
            $exists = Invoke-PostgresQuery -Query "SELECT 1 FROM information_schema.tables WHERE table_schema='keycloak' AND table_name='$table'"
            
            if ($null -eq $exists -or $exists -eq "") {
                Write-Error-Custom "Keycloak table '$table' does not exist"
                return $false
            }
        }
        
        # Validate critical columns
        $userEntityCols = Invoke-PostgresQuery -Query "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='keycloak' AND table_name='user_entity' AND column_name IN ('id', 'username', 'email', 'realm_id')"
        $userEntityCols = [int]$userEntityCols.Trim()
        
        if ($userEntityCols -lt 4) {
            Write-Error-Custom "Keycloak user_entity table missing critical columns"
            return $false
        }
        
        $realmCols = Invoke-PostgresQuery -Query "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='keycloak' AND table_name='realm' AND column_name IN ('id', 'name')"
        $realmCols = [int]$realmCols.Trim()
        
        if ($realmCols -lt 2) {
            Write-Error-Custom "Keycloak realm table missing critical columns"
            return $false
        }
        
        $clientCols = Invoke-PostgresQuery -Query "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='keycloak' AND table_name='client' AND column_name IN ('id', 'client_id', 'realm_id')"
        $clientCols = [int]$clientCols.Trim()
        
        if ($clientCols -lt 3) {
            Write-Error-Custom "Keycloak client table missing critical columns"
            return $false
        }
        
        $roleCols = Invoke-PostgresQuery -Query "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='keycloak' AND table_name='role_entity' AND column_name IN ('id', 'name')"
        $roleCols = [int]$roleCols.Trim()
        
        if ($roleCols -lt 2) {
            Write-Error-Custom "Keycloak role_entity table missing critical columns"
            return $false
        }
        
        Write-Success "Keycloak tables and critical columns exist"
        return $true
    }
    catch {
        Write-Error-Custom "Keycloak tables check failed: $_"
        return $false
    }
}

# ================================================================================
# REQUIREMENT 7: COMPOSE TEST EXECUTION
# ================================================================================
function Check-ComposeTestExecution {
    Write-Info "Checking Requirement 7: Docker Compose test environment..."
    
    try {
        # Verify docker-compose.test.yml exists
        if (-not (Test-Path "docker-compose.test.yml")) {
            Write-Error-Custom "docker-compose.test.yml not found"
            return $false
        }
        
        # Check that test docker compose file is valid
        $validation = docker-compose -f docker-compose.test.yml config 2>&1
        
        if ($LASTEXITCODE -ne 0) {
            Write-Error-Custom "docker-compose.test.yml is invalid"
            return $false
        }
        
        Write-Success "Docker Compose test configuration is valid"
        return $true
    }
    catch {
        Write-Error-Custom "Compose test execution check failed: $_"
        return $false
    }
}

# ================================================================================
# SYSTEM DEPENDENCIES CHECK
# ================================================================================
function Check-Dependencies {
    Write-Header "🔍 Checking System Dependencies"
    
    $missingDeps = 0
    
    # Check Docker
    if (-not (Test-CommandExists "docker")) {
        Write-Error-Custom "Docker is not installed"
        $missingDeps++
    }
    else {
        Write-Success "Docker is installed"
    }
    
    # Check Docker Compose
    if (-not (Test-CommandExists "docker-compose")) {
        Write-Error-Custom "Docker Compose is not installed"
        $missingDeps++
    }
    else {
        Write-Success "Docker Compose is installed"
    }
    
    # Check PostgreSQL client
    if (-not (Test-CommandExists "psql")) {
        Write-Warning-Custom "PostgreSQL client (psql) not found - some validations will be skipped"
    }
    else {
        Write-Success "PostgreSQL client is available"
    }
    
    # Check curl
    if (-not (Test-CommandExists "curl")) {
        Write-Error-Custom "curl is not installed - required for health checks"
        $missingDeps++
    }
    else {
        Write-Success "curl is installed"
    }
    
    return $missingDeps -eq 0
}

# ================================================================================
# MAIN EXECUTION
# ================================================================================
function Main {
    Write-Header "🚀 INFRASTRUCTURE VALIDATION - 7 CRITICAL CHECKS"
    
    # Check dependencies
    if (-not (Check-Dependencies)) {
        Write-Error-Custom "Missing critical dependencies"
        exit 1
    }
    
    Write-Host ""
    Write-Header "📋 VALIDATING INFRASTRUCTURE (7 Requirements)"
    Write-Host ""
    
    # Execute all validation checks
    Check-KongHealth | Out-Null
    Check-KongDbConnection | Out-Null
    Check-KongTables | Out-Null
    Check-KeycloakHealth | Out-Null
    Check-KeycloakDbConnection | Out-Null
    Check-KeycloakTables | Out-Null
    Check-ComposeTestExecution | Out-Null
    
    Write-Host ""
    Write-Header "📊 VALIDATION SUMMARY"
    
    Write-Host "  Passed: $($script:PassedChecks)" -ForegroundColor Green
    Write-Host "  Failed: $($script:FailedChecks)" -ForegroundColor Red
    Write-Host ""
    
    if ($script:FailedChecks -eq 0) {
        Write-Success "Infrastructure validation completed successfully!"
        Write-Host "All 7 requirements validated ✓" -ForegroundColor Green
        Write-Host ""
        exit 0
    }
    else {
        Write-Error-Custom "Infrastructure validation FAILED - $($script:FailedChecks) checks did not pass"
        Write-Host "Please review the errors above and fix the infrastructure." -ForegroundColor Red
        Write-Host ""
        exit 1
    }
}

# Run main function
Main
