# Guia de Testes (Canônico Condensado)

Este é o guia canônico de testes após consolidação documental.

## Objetivo

Centralizar o fluxo prático de testes com comandos essenciais e apontar para referências profundas sem duplicação.

## Fluxo Rápido

### Windows (PowerShell)

```powershell
# 1) Validar Docker
.\init.ps1

# 2) Rodar suíte de testes
.\scripts\build.ps1 docker-test

# 3) Subir ambiente de desenvolvimento
.\scripts\build.ps1 docker-dev-up

# 4) Logs de um serviço
.\scripts\build.ps1 docker-dev-logs -Service order-service
```

### Linux/macOS

```bash
# 1) Validar ambiente
make docker-status

# 2) Rodar suíte de testes
make docker-test

# 3) Subir ambiente de desenvolvimento
make docker-dev-up

# 4) Logs de um serviço
make docker-dev-logs SERVICE=order-service
```

### Compose E2E direto

```bash
docker compose -f tests/e2e/docker-compose.e2e.yml up --abort-on-container-exit
docker compose -f tests/e2e/docker-compose.e2e.yml logs -f order-service-e2e wallet-service-e2e
docker compose -f tests/e2e/docker-compose.e2e.yml down -v
```

## Onde está cada detalhe

- Índice rápido de testes: [README.md](./README.md)
- E2E e scripts do módulo tests: [../../tests/README.md](../../tests/README.md)
- Performance: [../../tests/performance/README.md](../../tests/performance/README.md)
- Infra para execução: [../../infra/README.md](../../infra/README.md)
- Seções detalhadas da versão legada: [COMPREHENSIVE_TESTING_SECTIONS.md](./COMPREHENSIVE_TESTING_SECTIONS.md)
- Event Store (wallet-service): [COMPREHENSIVE_TESTING_SECTIONS.md — AT-14 expansão](./COMPREHENSIVE_TESTING_SECTIONS.md#seções-pós-consolidação-event-store-no-wallet-service-at-14-expansão)

## Cobertura, debug e troubleshooting

- Cobertura (JaCoCo), debug remoto e troubleshooting permanecem documentados na versão legada completa.
- Use o índice de seções para localizar rapidamente o tópico necessário.
- **Event Store wallet-service (AT-14 expansão):** 4 classes de teste com 22 testes cobrindo append-only, replay temporal, audit endpoint RBAC e integração com todas as 7 operações do `WalletService`.

## Versão legada preservada (sem perda)

Todo o conteúdo detalhado anterior foi preservado em:

- [../archive/COMPREHENSIVE_TESTING_legacy_2026-03-09.md](../archive/COMPREHENSIVE_TESTING_legacy_2026-03-09.md)

## Estado do Documento

- Status: `canônico`
- Última consolidação: 2026-03-11
