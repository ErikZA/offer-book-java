# Documentação

Referência técnica central do projeto.

## Estrutura

### Testes e desenvolvimento

- [testing/README.md](./testing/README.md): índice rápido da documentação de testes.
- [testing/COMPREHENSIVE_TESTING.md](./testing/COMPREHENSIVE_TESTING.md): guia canônico condensado de testes.
- [testing/COMPREHENSIVE_TESTING_SECTIONS.md](./testing/COMPREHENSIVE_TESTING_SECTIONS.md): mapa de seções da versão legada detalhada.
- [../tests/README.md](../tests/README.md): foco no módulo `tests/` (E2E e scripts).

### Arquitetura e design

- [architecture/README.md](./architecture/README.md): índice canônico da arquitetura.

### Dados e modelagem

- [database/README.md](./database/README.md): índice canônico de modelagem e persistência.

### Infraestrutura e segurança

- [../infra/README.md](../infra/README.md): execução dos ambientes Docker e operação da infra.
- [SECRETS_MANAGEMENT.md](./SECRETS_MANAGEMENT.md): gestão de credenciais e secrets.

### Performance

- [../tests/performance/README.md](../tests/performance/README.md): execução dos benchmarks.
- [PERFORMANCE_REPORT.md](./PERFORMANCE_REPORT.md): resultados e análise técnica.
- [JWT_ENV_MAPPING.md](./JWT_ENV_MAPPING.md): mapeamento JWT por ambiente.

### Governança e referência

- [DOCS_INFORMATION_ARCHITECTURE.md](./DOCS_INFORMATION_ARCHITECTURE.md): arquitetura alvo da documentação.
- [DOCS_TRACEABILITY_MATRIX.md](./DOCS_TRACEABILITY_MATRIX.md): rastreabilidade de consolidação sem perda.
- [DOCS_CONTRIBUTING.md](./DOCS_CONTRIBUTING.md): regras para contribuir sem regressão documental.
- [PROJECT_STRUCTURE.md](./PROJECT_STRUCTURE.md): visão estrutural do repositório.

## Histórico em consolidação

- [SETUP_COMPLETE.md](./SETUP_COMPLETE.md): ponte para setup histórico.
- [SETUP_MAVEN.md](./SETUP_MAVEN.md): guia descontinuado de Maven local.
- [archive/README.md](./archive/README.md): inventário de documentos legados preservados.

## Por onde começar

| Objetivo | Comece por |
|---|---|
| Subir ambiente local | [../infra/README.md](../infra/README.md) |
| Rodar e escrever testes | [testing/README.md](./testing/README.md) |
| Entender arquitetura | [architecture/README.md](./architecture/README.md) |
| Entender modelagem de dados | [database/README.md](./database/README.md) |
| Rodar performance | [../tests/performance/README.md](../tests/performance/README.md) |
| Gestão de secrets | [SECRETS_MANAGEMENT.md](./SECRETS_MANAGEMENT.md) |

---

Volte ao [README principal](../README.md).
