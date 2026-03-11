# Matriz de Rastreabilidade da Documentação

Este arquivo é a **Etapa 1** da consolidação documental. Ele garante rastreabilidade `origem -> destino` para evitar perda de informação durante a condensação de `docs/` e melhoria dos `README`s.

## Resumo do Inventário

- Arquivos em escopo: **53**
- docs/*.md: **41** arquivos (**11990 linhas**)
- README*: **12** arquivos (**1448 linhas**)

## Top 12 por Tamanho (prioridade de condensação)

| Arquivo | Linhas | Ação |
|---|---:|---|
| `docs/archive/COMPREHENSIVE_TESTING_legacy_2026-03-09.md` | 5185 | preservado |
| `docs/archive/order-service_README_legacy_2026-03-09.md` | 800 | preservado |
| `docs/PERFORMANCE_REPORT.md` | 617 | manter/melhorar |
| `docs/archive/wallet-service_README_legacy_2026-03-09.md` | 470 | preservado |
| `docs/architecture/quality-and-tracing.md` | 404 | manter/melhorar |
| `docs/archive/SETUP_COMPLETE_legacy_2026-03-06.md` | 401 | preservado |
| `docs/archive/PROJECT_STRUCTURE_legacy_2026-03-09.md` | 365 | preservado |
| `docs/architecture/ddd-cqrs-event-source.md` | 348 | manter/melhorar |
| `tests/performance/README.md` | 315 | manter/melhorar |
| `docs/architecture/adr-001-debezium-single-instance.md` | 308 | manter/melhorar |
| `docs/SECRETS_MANAGEMENT.md` | 269 | manter/melhorar |
| `docs/architecture/motor-order-book.md` | 261 | manter/melhorar |

## Matriz de Rastreabilidade (Origem -> Destino Canônico Proposto)

| Origem | Tipo | Linhas | Tema | Ação | Destino Canônico Proposto |
|---|---|---:|---|---|---|
| `docs/architecture/adr-001-debezium-single-instance.md` | docs | 308 | arquitetura | manter/melhorar | `docs/architecture/README.md + docs/architecture/*` |
| `docs/architecture/consumer-prefetch-tuning.md` | docs | 131 | arquitetura | manter/melhorar | `docs/architecture/README.md + docs/architecture/*` |
| `docs/architecture/ddd-cqrs-event-source.md` | docs | 406 | arquitetura | manter/melhorar | `docs/architecture/README.md + docs/architecture/*` |
| `docs/architecture/motor-order-book.md` | docs | 261 | arquitetura | manter/melhorar | `docs/architecture/README.md + docs/architecture/*` |
| `docs/architecture/order-book-mvp-flow.md` | docs | 120 | arquitetura | manter/melhorar | `docs/architecture/README.md + docs/architecture/*` |
| `docs/architecture/order-book-mvp-sequence.md` | docs | 137 | arquitetura | manter/melhorar | `docs/architecture/README.md + docs/architecture/*` |
| `docs/architecture/order-book-mvp.md` | docs | 65 | arquitetura | consolidado (canônico) | `docs/architecture/README.md + docs/architecture/*` |
| `docs/architecture/quality-and-tracing.md` | docs | 404 | arquitetura | manter/melhorar | `docs/architecture/README.md + docs/architecture/*` |
| `docs/architecture/README.md` | docs | 31 | arquitetura | manter/melhorar | `docs/architecture/README.md + docs/architecture/*` |
| `docs/architecture/redis-cluster-setup.md` | docs | 236 | arquitetura | manter/melhorar | `docs/architecture/README.md + docs/architecture/*` |
| `docs/architecture/tools-stack.md` | docs | 46 | arquitetura | manter/melhorar | `docs/architecture/README.md + docs/architecture/*` |
| `libs/common-contracts/README.md` | readme | 223 | biblioteca | manter/melhorar | `libs/common-contracts/README.md` |
| `docs/database/data-base-storage.md` | docs | 91 | dados | manter/melhorar | `docs/database/README.md + docs/database/*` |
| `docs/database/modelagem-banco-order.md` | docs | 184 | dados | manter/melhorar | `docs/database/README.md + docs/database/*` |
| `docs/database/modelagem-banco-wallet.md` | docs | 209 | dados | manter/melhorar | `docs/database/README.md + docs/database/*` |
| `docs/database/modelagem-kong-keycloak.md` | docs | 95 | dados | manter/melhorar | `docs/database/README.md + docs/database/*` |
| `docs/database/modelagem-mongodb.md` | docs | 32 | dados | consolidado (ponte) | `docs/database/README.md + docs/database/*` |
| `docs/database/modelagem-motor-redis.md` | docs | 102 | dados | manter/melhorar | `docs/database/README.md + docs/database/*` |
| `docs/database/README.md` | docs | 24 | dados | manter/melhorar | `docs/database/README.md + docs/database/*` |
| `apps/order-service/docker/README.md` | readme | 93 | docker-serviços | consolidar | `apps/<service>/README.md + infra/README.md` |
| `apps/wallet-service/docker/README.md` | readme | 93 | docker-serviços | consolidar | `apps/<service>/README.md + infra/README.md` |
| `docs/PROJECT_STRUCTURE.md` | docs | 68 | estrutura | manter/melhorar | `docs/PROJECT_STRUCTURE.md + docs/archive/PROJECT_STRUCTURE_legacy_2026-03-09.md` |
| `docs/DOCS_CONTRIBUTING.md` | docs | 33 | governança | governança | `docs/DOCS_INFORMATION_ARCHITECTURE.md + docs/DOCS_CONTRIBUTING.md + docs/DOCS_TRACEABILITY_MATRIX.md` |
| `docs/DOCS_INFORMATION_ARCHITECTURE.md` | docs | 69 | governança | governança | `docs/DOCS_INFORMATION_ARCHITECTURE.md + docs/DOCS_CONTRIBUTING.md + docs/DOCS_TRACEABILITY_MATRIX.md` |
| `docs/DOCS_TRACEABILITY_MATRIX.md` | docs | 96 | governança | governança | `docs/DOCS_INFORMATION_ARCHITECTURE.md + docs/DOCS_CONTRIBUTING.md + docs/DOCS_TRACEABILITY_MATRIX.md` |
| `docs/archive/COMPREHENSIVE_TESTING_legacy_2026-03-09.md` | docs | 5185 | histórico | preservado | `docs/archive/* (somente referência histórica)` |
| `docs/archive/modelagem-mongodb_legacy_2026-03-09.md` | docs | 49 | histórico | preservado | `docs/archive/* (somente referência histórica)` |
| `docs/archive/order-book-mvp_legacy_2026-03-09.md` | docs | 121 | histórico | preservado | `docs/archive/* (somente referência histórica)` |
| `docs/archive/order-service_README_legacy_2026-03-09.md` | docs | 800 | histórico | preservado | `docs/archive/* (somente referência histórica)` |
| `docs/archive/PROJECT_STRUCTURE_legacy_2026-03-09.md` | docs | 365 | histórico | preservado | `docs/archive/* (somente referência histórica)` |
| `docs/archive/README.md` | docs | 26 | histórico | preservado | `docs/archive/* (somente referência histórica)` |
| `docs/archive/ROOT_README_legacy_2026-03-09.md` | docs | 223 | histórico | preservado | `docs/archive/* (somente referência histórica)` |
| `docs/archive/SETUP_COMPLETE_legacy_2026-03-06.md` | docs | 401 | histórico | preservado | `docs/archive/* (somente referência histórica)` |
| `docs/archive/SETUP_MAVEN_legacy.md` | docs | 138 | histórico | preservado | `docs/archive/* (somente referência histórica)` |
| `docs/archive/wallet-service_README_legacy_2026-03-09.md` | docs | 470 | histórico | preservado | `docs/archive/* (somente referência histórica)` |
| `docs/README.md` | docs | 59 | índice-docs | melhorar | `docs/README.md` |
| `infra/docker/README.md` | readme | 27 | infra | consolidar | `infra/README.md` |
| `infra/README.md` | readme | 209 | infra | manter/melhorar | `infra/README.md` |
| `apps/order-service/README.md` | readme | 79 | módulo-serviço | consolidado (canônico) | `apps/order-service/README.md + docs/archive/order-service_README_legacy_2026-03-09.md` |
| `apps/wallet-service/README.md` | readme | 78 | módulo-serviço | consolidado (canônico) | `apps/wallet-service/README.md + docs/archive/wallet-service_README_legacy_2026-03-09.md` |
| `README.md` | readme | 91 | onboarding | melhorar | `README.md + docs/archive/ROOT_README_legacy_2026-03-09.md` |
| `docs/JWT_ENV_MAPPING.md` | docs | 41 | performance | manter/melhorar | `tests/performance/README.md + docs/PERFORMANCE_REPORT.md` |
| `docs/PERFORMANCE_REPORT.md` | docs | 617 | performance | manter/melhorar | `tests/performance/README.md + docs/PERFORMANCE_REPORT.md` |
| `tests/performance/README.md` | readme | 315 | performance | manter/melhorar | `tests/performance/README.md + docs/PERFORMANCE_REPORT.md` |
| `scripts/README.md` | readme | 100 | scripts | manter/melhorar | `scripts/README.md` |
| `docs/SECRETS_MANAGEMENT.md` | docs | 269 | segurança-secrets | manter/melhorar | `docs/SECRETS_MANAGEMENT.md + infra/secrets/README.md` |
| `infra/secrets/README.md` | readme | 18 | segurança-secrets | manter/melhorar | `docs/SECRETS_MANAGEMENT.md + infra/secrets/README.md` |
| `docs/SETUP_COMPLETE.md` | docs | 25 | setup | consolidado (ponte) | `README.md + docs/testing/COMPREHENSIVE_TESTING.md + docs/archive/*` |
| `docs/SETUP_MAVEN.md` | docs | 20 | setup | consolidado (histórico) | `README.md + docs/testing/COMPREHENSIVE_TESTING.md + docs/archive/*` |
| `docs/testing/COMPREHENSIVE_TESTING_SECTIONS.md` | docs | 112 | testes | consolidado (índice legado) | `docs/testing/COMPREHENSIVE_TESTING.md + docs/archive/COMPREHENSIVE_TESTING_legacy_2026-03-09.md` |
| `docs/testing/COMPREHENSIVE_TESTING.md` | docs | 78 | testes | consolidado (canônico) | `docs/testing/COMPREHENSIVE_TESTING.md + docs/testing/README.md + docs/testing/COMPREHENSIVE_TESTING_SECTIONS.md` |
| `docs/testing/README.md` | docs | 24 | testes | consolidado (índice) | `docs/testing/COMPREHENSIVE_TESTING.md + docs/testing/README.md + docs/testing/COMPREHENSIVE_TESTING_SECTIONS.md` |
| `tests/README.md` | readme | 122 | testes | manter/melhorar | `docs/testing/COMPREHENSIVE_TESTING.md + docs/testing/README.md + docs/testing/COMPREHENSIVE_TESTING_SECTIONS.md` |

## Regras de Não Perda de Informação

- Nenhum arquivo é removido sem migração para um destino canônico rastreado nesta matriz.
- Conteúdos históricos/descontinuados ficam preservados em `docs/archive/` com status explícito.
- Todo comando e exemplo técnico deve permanecer acessível em documento canônico ou histórico.
- Links internos devem permanecer válidos após cada consolidação.

## Status da Etapa 1

- [x] Inventário completo
- [x] Mapeamento origem -> destino proposto
- [x] Priorização por volume
- [x] Validação final pós-consolidação (Etapa 6)

