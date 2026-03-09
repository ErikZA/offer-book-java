# Estrutura do Projeto

Visão atual da estrutura do repositório e papel de cada diretório principal.

## Árvore de Alto Nível

```text
.
├── apps/
│   ├── order-service/
│   └── wallet-service/
├── docs/
│   ├── architecture/
│   ├── database/
│   ├── testing/
│   └── archive/
├── infra/
├── libs/
│   ├── common-contracts/
│   └── common-utils/
├── scripts/
├── tests/
│   ├── e2e/
│   └── performance/
├── init.ps1
├── Makefile
└── pom.xml
```

## Módulos Principais

- `apps/order-service`: comando e projeção do domínio de ordens.
- `apps/wallet-service`: gestão de carteiras e liquidação financeira.
- `libs/common-contracts`: contratos de eventos e DTOs compartilhados.
- `libs/common-utils`: utilitários compartilhados.

## Documentação

- Índice principal: [README.md](./README.md)
- Arquitetura: [architecture/README.md](./architecture/README.md)
- Dados/modelagem: [database/README.md](./database/README.md)
- Testes: [testing/README.md](./testing/README.md)
- Estratégia de consolidação: [DOCS_INFORMATION_ARCHITECTURE.md](./DOCS_INFORMATION_ARCHITECTURE.md)
- Rastreabilidade de migração: [DOCS_TRACEABILITY_MATRIX.md](./DOCS_TRACEABILITY_MATRIX.md)

## Infraestrutura e Execução

- Infra Docker: [../infra/README.md](../infra/README.md)
- Script de validação inicial: [../init.ps1](../init.ps1)
- Script de comandos Windows: [../scripts/build.ps1](../scripts/build.ps1)
- Comandos Unix: [../Makefile](../Makefile)

## Testes

- Suíte E2E: [../tests/README.md](../tests/README.md)
- Compose E2E: [../tests/e2e/docker-compose.e2e.yml](../tests/e2e/docker-compose.e2e.yml)
- Performance: [../tests/performance/README.md](../tests/performance/README.md)

## Histórico

A versão anterior, extensa e histórica, foi preservada em:

- [archive/PROJECT_STRUCTURE_legacy_2026-03-09.md](./archive/PROJECT_STRUCTURE_legacy_2026-03-09.md)

## Estado do Documento

- Status: `canônico`
- Última atualização: 2026-03-09
