# =============================================================================
# init.ps1 - Validação de Ambiente Docker
# =============================================================================
# Este script valida que Docker está instalado e configurado
# IMPORTANTE: Todos os trabalhos (build, testes, dev) devem ser feitos via Docker
#
# Execute este script ANTES de usar docker-compose:
#   .\init.ps1
# =============================================================================

Write-Host "⚙️  Validando ambiente Docker..." -ForegroundColor Cyan

# Verificar Docker
Write-Host "`n📦 Verificando Docker..." -ForegroundColor Cyan
try {
    $dockerVersion = & docker --version 2>&1
    Write-Host "   ✅ $dockerVersion" -ForegroundColor Green
} catch {
    Write-Host "   ❌ Docker não encontrado!" -ForegroundColor Red
    Write-Host "   📥 Instale Docker Desktop: https://www.docker.com/products/docker-desktop" -ForegroundColor Yellow
    exit 1
}

# Verificar Docker Compose
Write-Host "`n📦 Verificando Docker Compose..." -ForegroundColor Cyan
try {
    $composeVersion = & docker compose version 2>&1
    Write-Host "   ✅ $composeVersion" -ForegroundColor Green
} catch {
    Write-Host "   ❌ Docker Compose não encontrado!" -ForegroundColor Red
    Write-Host "   Nota: Docker Compose deve vir com Docker Desktop" -ForegroundColor Yellow
    exit 1
}

# Verificar se Docker daemon está rodando
Write-Host "`n📦 Verificando Docker daemon..." -ForegroundColor Cyan
try {
    $dockerInfo = & docker info 2>&1 | Select-Object -First 1
    if ($dockerInfo -match "Containers") {
        Write-Host "   ✅ Docker daemon está rodando" -ForegroundColor Green
    } else {
        Write-Host "   ❌ Docker daemon não está respondendo!" -ForegroundColor Red
        exit 1
    }
} catch {
    Write-Host "   ❌ Docker daemon não está respondendo!" -ForegroundColor Red
    Write-Host "   💡 Inicie Docker Desktop e tente novamente" -ForegroundColor Yellow
    exit 1
}

Write-Host "`n✅ Ambiente Docker validado com sucesso!" -ForegroundColor Green

Write-Host "`n📖 Próximos passos:" -ForegroundColor Cyan
Write-Host "   1. Windows:  .\build.ps1 docker-dev-up" -ForegroundColor Yellow
Write-Host "   2. Linux:    make docker-dev-up" -ForegroundColor Yellow
Write-Host "   3. Ou use:   docker-compose -f docker/docker-compose.dev.yml up" -ForegroundColor Yellow

Write-Host "`n💡 Documentação:" -ForegroundColor Yellow
Write-Host "   Leia: docs/docker/README.md para detalhes sobre cada ambiente" -ForegroundColor Cyan

Write-Host ""
