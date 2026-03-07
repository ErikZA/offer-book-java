# Documentação

Referência técnica completa do projeto.

## 📚 Estrutura

### 🧪 [Testes & Desenvolvimento](./testing/)
Tudo o que você precisa para trabalhar com testes e configurar o ambiente.

- **[testing/README.md](./testing/README.md)** - Índice da documentação de testes ⭐
  - Navegação para todos os recursos
  - Quick links
  - Padrões disponíveis

- **[testing/COMPREHENSIVE_TESTING.md](./testing/COMPREHENSIVE_TESTING.md)** - Guia Completo via Docker (500+ linhas) 📖
  - Setup e Quick Start (Windows/Linux/macOS) - **Docker-based**
  - 20+ Padrões prontos para copiar/colar
  - Testes unitários e integração (via Docker)
  - Ferramentas (JUnit 5, Mockito, AssertJ, REST Assured, etc.)
  - Debug remoto
  - Cobertura de código
  - Troubleshooting
  - **👉 Comece por aqui para testes!**

- **[docker/README.md](../docker/README.md)** - Como usar Docker Compose
  - Desenvolvimento com hotreload
  - Testes automatizados
  - Produção
  
- **[SETUP_COMPLETE.md](./SETUP_COMPLETE.md)** - Resumo do que foi implementado

⚠️ **Histórico (Descontinuados)**: 
- SETUP_MAVEN.md - Descontinuado (use Docker em vez disso)
- [testing/TESTING_GUIDE.md](./testing/TESTING_GUIDE.md), [testing/TEST_PATTERNS.md](./testing/TEST_PATTERNS.md), [testing/FINAL_CHECKLIST.md](./testing/FINAL_CHECKLIST.md) - Consolidados em COMPREHENSIVE_TESTING.md

### 🏗️ [Arquitetura & Design](./architecture/)
Entenda a visão geral, fluxos e padrões do sistema.

- **[order-book-mvp.md](./architecture/order-book-mvp.md)** - Visão geral da arquitetura
- **[order-book-mvp-flow.md](./architecture/order-book-mvp-flow.md)** - Fluxos de caso de uso
- **[order-book-mvp-sequence.md](./architecture/order-book-mvp-sequence.md)** - Diagramas de sequência
- **[ddd-cqrs-event-source.md](./architecture/ddd-cqrs-event-source.md)** - Padrões (DDD/CQRS/Event Sourcing) + Transactional Outbox com Polling SKIP LOCKED
- **[tools-stack.md](./architecture/tools-stack.md)** - Stack de ferramentas (inclui Testcontainers, Spring Retry e Polling SKIP LOCKED)
- **[quality-and-tracing.md](./architecture/quality-and-tracing.md)** - Qualidade e observabilidade (inclui tracing Jaeger, métricas Prometheus + Grafana, Circuit Breaker)

### 📋 Referência
- **[SETUP_COMPLETE.md](./SETUP_COMPLETE.md)** - Resumo do que foi implementado
- **[PROJECT_STRUCTURE.md](./PROJECT_STRUCTURE.md)** - Estrutura visual do projeto

## 🎯 Por Onde Começar?

| Objetivo | Comece por |
|----------|-----------|
| **Configurar e usar Docker** | [../docker/README.md](../docker/README.md) |
| **Entender & escrever testes** | [testing/COMPREHENSIVE_TESTING.md](./testing/COMPREHENSIVE_TESTING.md) ⭐ |
| **Entender arquitetura** | [architecture/order-book-mvp.md](./architecture/order-book-mvp.md) |
| **Ver fluxos** | [architecture/order-book-mvp-flow.md](./architecture/order-book-mvp-flow.md) |
| **Padrões de design** | [architecture/ddd-cqrs-event-source.md](./architecture/ddd-cqrs-event-source.md)

---

Volte ao [README principal](../README.md).
