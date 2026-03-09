# ⛔ Setup Local com Maven (Descontinuado)

## ⚠️ AVISO: Use Docker Em Vez Disso!

Este documento é **APENAS REFERÊNCIA HISTÓRICA**. 

**NÃO instale Maven ou Java na sua máquina.** Todos os builds e testes devem ser executados em containers Docker.

---

## ✅ Setup Recomendado (Docker)

Para desenvolver neste projeto, você **APENAS precisa**:

1. **Docker Desktop** instalado (Windows/Mac) ou Docker Engine (Linux)
2. **Docker Compose** (incluído no Docker Desktop)

### Começar:

```bash
# 1. Validar Docker
.\init.ps1              # Windows
make init               # Linux/Mac

# 2. Iniciar desenvolvimento
.\build.ps1 docker-dev-up       # Windows
make docker-dev-up              # Linux/Mac

# 3. Executar testes
.\build.ps1 docker-test         # Windows
make docker-test                # Linux/Mac
```

Veja [docs/docker/README.md](../../infra/README.md) para mais detalhes sobre ambientes Docker.

---

## 📚 Por Que Não Instalar Maven Localmente?

- ✅ **Consistência**: Mesmo build em qualquer máquina
- ✅ **Isolamento**: Não interfere com outro software
- ✅ **Simplicidade**: Apenas Docker precisa estar instalado
- ✅ **CI/CD**: Mesmo ambiente do pipeline
- ✅ **Limpeza**: `docker system prune` remove tudo

## ✅ Verificação de Status

```powershell
# Verificar Java
java -version

# Verificar Maven
mvn -version

# Resultado esperado:
# Java: OpenJDK 21.0.9+10 (Temurin)
# Maven: 3.9.12
```

## 📊 Testes Rodando com Sucesso

```
✅ Order Service:  1 test passed  
✅ Wallet Service: 1 test passed
✅ Total:         2 tests passed

BUILD SUCCESS in 17.604 seconds
```

## 🛠️ Script de Inicialização Automática (Opcional)

Para não precisar executar `.\init.ps1` toda vez, adicione ao seu PowerShell Profile:

```powershell
# Editar: $PROFILE
# Adicionar:
. 'C:\Users\erik_\Desktop\code_workspace\testes\teste_java\init.ps1'
```

Depois reabra PowerShell e os comandos Java/Maven funcionarão automáticamente.

## 🆘 Troubleshooting

### Erro: "Java não encontrado"
```powershell
# Reinstalar Temurin
choco install temurin21 -y --force
```

### Erro: "Maven não funciona"
```powershell
# Reinstalar Maven
choco install maven -y --force

# Depois execute init.ps1
.\init.ps1
```

### Erro: "JAVA_HOME não definido"
```powershell
# Executar init.ps1 resolvendo
.\init.ps1
```

## 📁 Estrutura do Projeto

```
.
├── init.ps1                    # 🚀 Execute ANTES de qualquer Maven
├── pom.xml                     # POM raiz
├── apps/
│   ├── order-service/
│   │   ├── pom.xml
│   │   ├── src/main/java/...
│   │   └── src/test/java/...   
│   └── wallet-service/
│       ├── pom.xml
│       ├── src/main/java/...
│       └── src/test/java/...
└── libs/
    └── common-contracts/
        ├── pom.xml
        └── src/...
```

## 🎯 Próximos Passos

1. ✅ **Ambiente pronto** - Execute `.\init.ps1; .\build.ps1 docker-test`
2. 📝 **Criar testes** - Copie padrões de `docs/testing/COMPREHENSIVE_TESTING.md`
3. 🐳 **Docker** - Use `build.ps1` ou `Makefile` para dev/test
4. 🔄 **CI/CD** - Configure GitHub Actions para rodar testes em containers

---

**Status**: ✅ Docker-Only (Histórico de Setup Local Descontinuado)
**Última Atualização**: 2026-02-27
**Testado em**: Windows 11 + PowerShell

