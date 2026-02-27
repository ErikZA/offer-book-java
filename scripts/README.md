# Scripts

Utilitários de automação para desenvolvimento e deployment.

## 📋 Scripts Disponíveis

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
.\scripts\build.ps1 test            # Executar testes
.\scripts\build.ps1 docker-dev-up   # Dev com hotreload
```

### Linux/macOS
```bash
cd /path/to/project
make test               # Testes
make docker-dev-up      # Dev
make coverage           # Cobertura
```

---

Para mais detalhes, consulte o [README principal](../README.md).
