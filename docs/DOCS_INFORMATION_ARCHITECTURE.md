# Arquitetura Alvo da Documentação

Este arquivo define a **Etapa 2** da melhoria documental: fonte única por tema, responsabilidade de cada documento e regras de consolidação sem perda de informação.

## Objetivo

- Reduzir duplicação entre `docs/` e `README`s.
- Manter 100% das informações técnicas atuais.
- Tornar a navegação previsível para onboarding, operação e manutenção.

## Princípios

- Cada tema tem um documento canônico.
- `README`s são porta de entrada e contexto local, não espelho completo de `docs/`.
- Conteúdo histórico permanece referenciado até ser consolidado.
- Todo conteúdo migrado deve preservar exemplos, comandos e decisões técnicas.

## Fonte Única por Tema

| Tema | Documento Canônico | Papel dos Demais |
|---|---|---|
| Onboarding e visão geral | `README.md` | apontar para docs especializados |
| Índice de documentação | `docs/README.md` | centralizar navegação por domínio |
| Testes (guia completo) | `docs/testing/COMPREHENSIVE_TESTING.md` | `docs/testing/README.md` como índice rápido; `tests/README.md` com foco no módulo `tests/` |
| Arquitetura | `docs/architecture/README.md` | arquivos em `docs/architecture/*.md` como detalhes por tópico |
| Dados/modelagem | `docs/database/README.md` | arquivos em `docs/database/*.md` como detalhe por tecnologia |
| Infraestrutura operacional | `infra/README.md` | `infra/docker/README.md` vira ponte/atalho |
| Segurança e secrets | `docs/SECRETS_MANAGEMENT.md` | `infra/secrets/README.md` mantém instrução local mínima |
| Performance | `tests/performance/README.md` (execução) + `docs/PERFORMANCE_REPORT.md` (resultado/análise) | `docs/JWT_ENV_MAPPING.md` como referência de apoio |
| Estrutura do projeto | `docs/PROJECT_STRUCTURE.md` | referenciado por `README.md` e `docs/README.md` |

## Política para READMEs

- `README.md` (raiz): quick start, comandos principais e mapa de documentação.
- `apps/*/README.md`: responsabilidades do serviço, endpoints/eventos e ponte para docs transversais.
- `apps/*/docker/README.md`: conteúdo duplicado deve ser absorvido pelo README do serviço + `infra/README.md`.
- `tests/README.md`: foco em E2E e scripts do módulo `tests/`.
- `scripts/README.md`: somente scripts e exemplos de uso.

## Política de Ciclo de Vida

Cada documento deve ter um estado explícito:

- `canônico`: fonte oficial daquele tema.
- `ponte`: resumo + link para o canônico.
- `histórico`: mantido por contexto, sem evolução ativa.

## Status Atual da Execução

1. Setup e testes básicos: consolidado (`docs/SETUP_COMPLETE.md`, `docs/SETUP_MAVEN.md`, `docs/testing/README.md`, `tests/README.md`).
2. Docker/infra duplicado: consolidado (`apps/order-service/docker/README.md`, `apps/wallet-service/docker/README.md`, `infra/docker/README.md`).
3. READMEs de serviços: consolidados (`apps/order-service/README.md`, `apps/wallet-service/README.md`).
4. Links internos: revisados e validados.
5. Checklist final de não perda: concluído com rastreabilidade em `docs/DOCS_TRACEABILITY_MATRIX.md`.

## Critérios de Aceite da Etapa 2

- [x] Fonte única definida por tema.
- [x] Responsabilidade de cada README definida.
- [x] Ordem de execução das consolidações definida.

## Governança de Evolução

- Regras operacionais de manutenção da documentação: [DOCS_CONTRIBUTING.md](./DOCS_CONTRIBUTING.md)
