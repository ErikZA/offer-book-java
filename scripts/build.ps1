# ============================================
# VIBRANIUM - Docker Build Script para Windows
# =============================================
# IMPORTANTE: Todos os comandos usam Docker
# Não instale Java/Maven - use apenas Docker
# =============================================

param(
    [Parameter(Mandatory=$false)]
    [string]$Command = "help",
    
    [Parameter(Mandatory=$false)]
    [string]$Service = ""
)

# Cores
$RED = "`e[0;31m"
$GREEN = "`e[0;32m"
$YELLOW = "`e[1;33m"
$BLUE = "`e[0;34m"
$NC = "`e[0m"

function Show-Banner {
    Write-Host ""
    Write-Host "$BLUE╔════════════════════════════════════════╗$NC"
    Write-Host "$BLUE║   VIBRANIUM - Docker Build Script     ║$NC"
    Write-Host "$BLUE║   💡 All work via Docker              ║$NC"
    Write-Host "$BLUE╚════════════════════════════════════════╝$NC"
    Write-Host ""
}

function Assert-EnvFile {
    if (-not (Test-Path ".env")) {
        Write-Host "${RED}ERROR: .env file not found!${NC}"
        Write-Host "${YELLOW}Copie .env.example para .env e preencha os valores:${NC}"
        Write-Host "  Copy-Item .env.example .env" -ForegroundColor Cyan
        Write-Host "  Veja .env.example para detalhes das variaveis obrigatorias." -ForegroundColor Cyan
        exit 1
    }
}

function Show-Help {
    Show-Banner
    Write-Host "${YELLOW}🐳 Docker Development (with hotreload):$NC"
    Write-Host "  .\scripts\build.ps1 docker-dev-up      - Start dev environment"
    Write-Host "  .\scripts\build.ps1 docker-dev-down    - Stop dev environment"
    Write-Host "  .\scripts\build.ps1 docker-dev-logs -Service order-service"
    Write-Host ""
    Write-Host "${YELLOW}🧪 Testing:$NC"
    Write-Host "  .\scripts\build.ps1 docker-test        - Run all tests in containers"
    Write-Host ""
    Write-Host "${YELLOW}🐳 Build & Production:$NC"
    Write-Host "  .\scripts\build.ps1 docker-build       - Build Docker images"
    Write-Host "  .\scripts\build.ps1 docker-prod-up     - Start production environment"
    Write-Host "  .\scripts\build.ps1 docker-prod-down   - Stop production environment"
    Write-Host ""
    Write-Host "${YELLOW}⚙️  Utilities:$NC"
    Write-Host "  .\scripts\build.ps1 docker-status      - Show running containers"
    Write-Host "  .\scripts\build.ps1 docker-clean       - Clean all containers/images"
    Write-Host ""
}

switch ($Command.ToLower()) {
    "docker-build" {
        Show-Banner
        Write-Host "${YELLOW}🐳 Building Docker images...${NC}"
        docker build -f apps/order-service/docker/Dockerfile -t vibranium-order-service:latest . 2>&1 | ForEach-Object { Write-Host "   $_" -ForegroundColor Gray }
        docker build -f apps/wallet-service/docker/Dockerfile -t vibranium-wallet-service:latest . 2>&1 | ForEach-Object { Write-Host "   $_" -ForegroundColor Gray }
        Write-Host "${GREEN}✓ Docker images built${NC}"
        Write-Host "${BLUE}Images:${NC}"
        docker images | Select-String "vibranium" | ForEach-Object { Write-Host "  $_" -ForegroundColor Cyan }
    }

    "docker-dev-up" {
        Show-Banner
        Assert-EnvFile
        Write-Host "${YELLOW}🚀 Starting development environment with hotreload...${NC}"
        docker compose --env-file .env -f infra/docker-compose.dev.yml up -d 2>&1 | ForEach-Object { Write-Host "   $_" -ForegroundColor Gray }
        Write-Host "${GREEN}✓ Dev environment started${NC}"
        Write-Host "${BLUE}Services:${NC}"
        Write-Host "  Order Service: http://localhost:8080 (Debug: 5005)" -ForegroundColor Cyan
        Write-Host "  Wallet Service: http://localhost:8081 (Debug: 5006)" -ForegroundColor Cyan
        Write-Host "${YELLOW}📝 Tip: Run '.\scripts\build.ps1 docker-dev-logs -Service order-service' to view logs${NC}"
    }

    "docker-dev-down" {
        Show-Banner
        Write-Host "${YELLOW}🛑 Stopping development environment...${NC}"
        docker compose --env-file .env -f infra/docker-compose.dev.yml down 2>&1 | ForEach-Object { Write-Host "   $_" -ForegroundColor Gray }
        Write-Host "${GREEN}✓ Dev environment stopped${NC}"
    }

    "docker-dev-logs" {
        Show-Banner
        if ($Service -eq "") {
            Write-Host "${RED}ERROR: Service name required${NC}"
            Write-Host "Usage: .\scripts\build.ps1 docker-dev-logs -Service order-service"
            exit 1
        }
        Write-Host "${YELLOW}📋 Showing logs for $Service...${NC}"
        docker compose --env-file .env -f infra/docker-compose.dev.yml logs -f $Service
    }

    "docker-test" {
        Show-Banner
        Write-Host "${YELLOW}🧪 Running tests in Docker...${NC}"
        docker compose -f tests/e2e/docker-compose.e2e.yml up --abort-on-container-exit 2>&1 | ForEach-Object { Write-Host "   $_" -ForegroundColor Gray }
        Write-Host "${YELLOW}🧹 Cleaning test environment...${NC}"
        docker compose -f tests/e2e/docker-compose.e2e.yml down -v 2>&1 | ForEach-Object { Write-Host "   $_" -ForegroundColor Gray }
        Write-Host "${GREEN}✓ Tests completed${NC}"
    }

    "docker-prod-up" {
        Show-Banner
        Assert-EnvFile
        Write-Host "${YELLOW}🚀 Starting production environment...${NC}"
        docker compose --env-file .env -f infra/docker-compose.yml up -d 2>&1 | ForEach-Object { Write-Host "   $_" -ForegroundColor Gray }
        Write-Host "${GREEN}✓ Production environment started${NC}"
        Write-Host "${BLUE}Containers:${NC}"
        docker ps | Select-String "vibranium" | ForEach-Object { Write-Host "  $_" -ForegroundColor Cyan }
    }

    "docker-prod-down" {
        Show-Banner
        Write-Host "${YELLOW}🛑 Stopping production environment...${NC}"
        docker compose --env-file .env -f infra/docker-compose.yml down 2>&1 | ForEach-Object { Write-Host "   $_" -ForegroundColor Gray }
        Write-Host "${GREEN}✓ Production environment stopped${NC}"
    }

    "docker-status" {
        Show-Banner
        Write-Host "${YELLOW}📊 Container Status:${NC}"
        docker ps --all --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}" | ForEach-Object { Write-Host "  $_" -ForegroundColor Cyan }
    }

    "docker-clean" {
        Show-Banner
        Write-Host "${YELLOW}🧹 Cleaning Docker artifacts...${NC}"
        docker compose --env-file .env -f infra/docker-compose.dev.yml down -v 2>&1 | ForEach-Object { Write-Host "   $_" -ForegroundColor Gray }
        docker compose -f tests/e2e/docker-compose.e2e.yml down -v 2>&1 | ForEach-Object { Write-Host "   $_" -ForegroundColor Gray }
        docker compose --env-file .env -f infra/docker-compose.yml down -v 2>&1 | ForEach-Object { Write-Host "   $_" -ForegroundColor Gray }
        Write-Host "${GREEN}✓ Clean completed${NC}"
    }

    "help" {
        Show-Help
    }

    default {
        Write-Host "${RED}Unknown command: $Command${NC}"
        Show-Help
        exit 1
    }
}

