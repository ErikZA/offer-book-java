# 📚 Documentação de Testes

Bem-vindo à seção de testes! Aqui você encontra tudo o que precisa saber sobre testes no projeto.

## 📖 Documentação Principal

### 🎯 [COMPREHENSIVE_TESTING.md](COMPREHENSIVE_TESTING.md) - **COMECE AQUI** ⭐

**Documento consolidado com 500+ linhas** contendo:

- ✅ Visão geral do ambiente TDD
- ✅ Quick Start (Windows/Linux/macOS)
- ✅ Estrutura de testes e nomenclatura
- ✅ Tipos de testes (Unitários, Integração)
- ✅ **20+ Padrões de teste prontos para copiar/colar**
  - Testes unitários
  - Testes de integração
  - Rest Assured (BDD)
  - Mock de APIs externas
  - Transações
  - Validações
  - Performance
  - Concorrência
- ✅ Ferramentas (JUnit 5, Mockito, AssertJ, etc)
- ✅ Ambientes Docker
- ✅ Cobertura de código (Jacoco)
- ✅ Debug remoto (VSCode/IntelliJ)
- ✅ Checklist de qualidade
- ✅ Troubleshooting
- ✅ CI/CD (GitHub Actions)

---

## 📚 Arquivo de Referência (Histórico)

Os seguintes arquivos foram consolidados no documento principal, mas estão disponíveis como referência:

- [TESTING_GUIDE.md](TESTING_GUIDE.md) - **Descontinuado** (conteúdo em COMPREHENSIVE_TESTING.md)
- [TEST_PATTERNS.md](TEST_PATTERNS.md) - **Descontinuado** (conteúdo em COMPREHENSIVE_TESTING.md)
- [FINAL_CHECKLIST.md](FINAL_CHECKLIST.md) - **Descontinuado** (conteúdo em COMPREHENSIVE_TESTING.md)

---

## 🚀 Quick Navigation

### Para novo desenvolvedor:
```
1. Leia docs/README.md (overview do projeto)
   ↓
2. Validar Docker: .\ init.ps1
   ↓
3. Execute: .\build.ps1 docker-test
   ↓
4. Leia docs/testing/COMPREHENSIVE_TESTING.md (este guia)
```

### Para escrever testes:
```
1. Abra docs/testing/COMPREHENSIVE_TESTING.md
   ↓
2. Encontre padrão similar ao que você precisa
   ↓
3. Copie, adapte para seu caso
   ↓
4. Siga AAA (Arrange-Act-Assert)
```

### Para entender cobertura:
```
1. Execute: .\build.ps1 docker-test (gera automaticamente)
   ↓
2. Abra: target/site/jacoco/index.html
   ↓
3. Veja seção "Cobertura de Código" em COMPREHENSIVE_TESTING.md
```

### Para debug:
```
1. Veja seção "Debug Remoto" em COMPREHENSIVE_TESTING.md
   ↓
2. Configure VSCode ou IntelliJ
   ↓
3. Execute: docker-compose -f docker-compose.dev.yml up
```

---

## 📊 Padrões Disponíveis

| Categoria | Padrões | Localização |
|-----------|---------|------------|
| **Unitários** | 5+ padrões | COMPREHENSIVE_TESTING.md #padrões-de-teste |
| **Integração** | 7+ padrões | COMPREHENSIVE_TESTING.md #padrões-de-teste |
| **APIs** | REST Assured, BDD | COMPREHENSIVE_TESTING.md #rest-assured |
| **Mocks** | Mockito, WireMock | COMPREHENSIVE_TESTING.md #padrões-de-teste |
| **Validação** | JSR-380 | COMPREHENSIVE_TESTING.md #padrões-de-teste |
| **Performance** | Tempo, Concorrência | COMPREHENSIVE_TESTING.md #padrões-de-teste |

---

## 🛠️ Ferramentas Disponíveis

- **JUnit 5 (Jupiter)** - Framework moderno
- **Mockito** - Mocking avançado
- **AssertJ** - Assertions fluentes
- **REST Assured** - Testes de API
- **WireMock** - Mock HTTP
- **TestContainers** - Testes com Docker
- **Jacoco** - Cobertura de código

Veja COMPREHENSIVE_TESTING.md para exemplos de cada ferramenta.

---

## ✅ Checklist Antes de Fazer PR

- [ ] Testes para nova funcionalidade
- [ ] Todos os testes passam (via Docker: `.\build.ps1 docker-test`)
- [ ] Padrão AAA (Arrange-Act-Assert)
- [ ] `@DisplayName` descritivo
- [ ] Sem testes interdependentes
- [ ] Tempo de execução razoável
- [ ] Assertions significativas

---

## 🆘 Precisa de Ajuda?

| Dúvida | Solução |
|--------|---------|
| **"Como faço meu primeiro teste?"** | Veja seção "Quick Start" em COMPREHENSIVE_TESTING.md |
| **"Qual padrão usar?"** | Procure padrão similar em "Padrões de Teste" |
| **"Por que meu teste falha?"** | Veja "Troubleshooting" em COMPREHENSIVE_TESTING.md |
| **"Como mockar uma API?"** | Procure "Mock de APIs" em COMPREHENSIVE_TESTING.md |
| **"Como debugar?"** | Veja "Debug Remoto" em COMPREHENSIVE_TESTING.md |
| **"Qual a cobertura esperada?"** | Veja "Cobertura de Código" em COMPREHENSIVE_TESTING.md |

---

## 📞 Referências Rápidas

- Full Documentation: [COMPREHENSIVE_TESTING.md](COMPREHENSIVE_TESTING.md)
- Docker Setup: [docker/README.md](../../docker/README.md)
- Project Overview: [docs/README.md](../README.md)
- Architecture: [docs/architecture/](../architecture/)

---

**Última atualização**: 27 de fevereiro de 2026  
**Status**: ✅ Consolidado em COMPREHENSIVE_TESTING.md
