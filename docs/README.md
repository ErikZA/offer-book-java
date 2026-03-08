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
- **[redis-cluster-setup.md](./architecture/redis-cluster-setup.md)** - Redis Cluster HA para Order Book (AT-15: 6 nodes, failover, recovery, monitoramento)

### 🔐 [Segurança & Secrets](./SECRETS_MANAGEMENT.md)
Gestão de credenciais com Docker Secrets.

- **[SECRETS_MANAGEMENT.md](./SECRETS_MANAGEMENT.md)** - Guia completo de gestão de secrets (Docker Secrets, rotação, troubleshooting)

### 🚀 [Performance Testing](./PERFORMANCE_REPORT.md)
Testes de carga e análise de capacidade.

- **[PERFORMANCE_REPORT.md](./PERFORMANCE_REPORT.md)** - Relatório completo de performance (Smoke, Load, Stress, Soak)
  - Métricas detalhadas por cenário
  - Análise de causas raiz
  - Recomendações de tuning e escala
  - Projeção de capacidade
- **[PERFORMANCE_PROMPTS.md](./PERFORMANCE_PROMPTS.md)** - Prompts de engenharia para correção de bugs e escalabilidade
- **[JWT_ENV_MAPPING.md](./JWT_ENV_MAPPING.md)** - Mapeamento de variáveis JWT por ambiente

### 📋 Referência
- **[SETUP_COMPLETE.md](./SETUP_COMPLETE.md)** - Resumo do que foi implementado
- **[PROJECT_STRUCTURE.md](./PROJECT_STRUCTURE.md)** - Estrutura visual do projeto

## 🎯 Por Onde Começar?

| Objetivo | Comece por |
|----------|-----------|
| **Configurar e usar Docker** | [../docker/README.md](../docker/README.md) |
| **Entender & escrever testes** | [testing/COMPREHENSIVE_TESTING.md](./testing/COMPREHENSIVE_TESTING.md) ⭐ |
| **Testes de performance** | [PERFORMANCE_REPORT.md](./PERFORMANCE_REPORT.md) 🚀 |
| **Gestão de secrets** | [SECRETS_MANAGEMENT.md](./SECRETS_MANAGEMENT.md) 🔐 |
| **Entender arquitetura** | [architecture/order-book-mvp.md](./architecture/order-book-mvp.md) |
| **Ver fluxos** | [architecture/order-book-mvp-flow.md](./architecture/order-book-mvp-flow.md) |
| **Padrões de design** | [architecture/ddd-cqrs-event-source.md](./architecture/ddd-cqrs-event-source.md)

---

Volte ao [README principal](../README.md).
