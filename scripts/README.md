# Scripts

Utilitários de automação para desenvolvimento e deployment.

## 📋 Scripts Disponíveis

### **verify-infra.sh** (Linux/Mac) & **verify-infra.ps1** (Windows)

Scripts de validação determinística da infraestrutura Kong + Keycloak + PostgreSQL.

**7 Requisitos Validados**:
1. ✅ Kong rodando e saudável
2. ✅ Kong conectado ao PostgreSQL
3. ✅ Tabelas Kong criadas
4. ✅ Keycloak rodando e saudável
5. ✅ Keycloak conectado ao PostgreSQL
6. ✅ Tabelas Keycloak criadas
7. ✅ Docker Compose testes pronto

```bash
# Linux/Mac
chmod +x scripts/verify-infra.sh
./scripts/verify-infra.sh

# Windows
powershell -ExecutionPolicy Bypass -File scripts/verify-infra.ps1

# Exit codes
# 0 = Sucesso (todos 7 requisitos validados)
# 1 = Falha (um ou mais requisitos falharam)
```

**Exit Codes**:
- `0`: ✅ Infraestrutura validada com sucesso
- `1`: ❌ Um ou mais requisitos falharam

### **build.ps1** (Windows)
PowerShell script com funções de build, testes e docker.

```powershell
# Compilar
.\scripts\build.ps1 build

# Testes
.\scripts\build.ps1 test-unit
.\scripts\build.ps1 test-integration
.\scripts\build.ps1 test

# Cobertura
.\scripts\build.ps1 coverage

# Docker
.\scripts\build.ps1 docker-build
.\scripts\build.ps1 docker-dev-up
.\scripts\build.ps1 docker-dev-down
.\scripts\build.ps1 docker-test

# Logs
.\scripts\build.ps1 docker-dev-logs -Service order-service
```

### **Makefile** (Linux/macOS)
Equivalente do build.ps1 para sistemas Unix.

```bash
make build
make test
make test-unit
make test-integration
make coverage
make docker-build
make docker-dev-up
make docker-dev-logs
```

## 🔧 Uso

### Windows
```powershell
cd C:\path\to\project
.\init.ps1                          # Setup inicial
.\scripts\verify-infra.ps1          # Validar infraestrutura
.\scripts\build.ps1 test            # Executar testes
.\scripts\build.ps1 docker-dev-up   # Dev com hotreload
```

### Linux/macOS
```bash
cd /path/to/project
./scripts/verify-infra.sh           # Validar infraestrutura
make test                           # Testes
make docker-dev-up                  # Dev
make coverage                       # Cobertura
```

---

Para mais detalhes sobre verificação de infraestrutura, consulte [INFRASTRUCTURE_DECISIONS.md](../docs/INFRASTRUCTURE_DECISIONS.md).

