# Wallet Service

Microsserviço responsável pela gestão de carteiras, reservas, liquidação e consistência de saldo.

## Responsabilidades

- Criar e manter carteiras de usuários.
- Processar reservas/liberações/liquidações de fundos.
- Publicar eventos financeiros para a Saga de ordens.
- Aplicar regras de autenticação/autorização (`JWT` + ownership).
- Garantir idempotência e tratamento de falhas (DLQ, retry, cleanup).
- Auditar todos os eventos de domínio em Event Store append-only (compliance e replay temporal).

## Endpoints principais

- `POST /api/wallets`
- `GET /api/wallets/{id}`
- `PUT /api/wallets/{id}/deposit`
- `PUT /api/wallets/{id}/withdraw`
- `GET /api/wallets/{id}/transactions`
- `GET /admin/events?aggregateId={id}&until={datetime}` — Auditoria Event Store (`ROLE_ADMIN`)

## Estrutura (alto nível)

```text
src/main/java/com/vibranium/walletservice/
├── application/
├── domain/
├── infrastructure/
├── web/
├── security/
└── config/
```

## Execução

### Desenvolvimento

```bash
# Ambiente completo (infra + serviços)
docker compose --env-file .env -f infra/docker-compose.dev.yml up -d

# Ou via comandos do projeto
# Windows
.\scripts\build.ps1 docker-dev-up
# Linux/macOS
make docker-dev-up
```

### Testes

```bash
# Windows
.\scripts\build.ps1 docker-test

# Linux/macOS
make docker-test

# E2E compose direto
docker compose -f tests/e2e/docker-compose.e2e.yml up --abort-on-container-exit
```

## Navegação técnica

- Arquitetura do MVP: [../../docs/architecture/order-book-mvp.md](../../docs/architecture/order-book-mvp.md)
- Event Store e auditoria: [../../docs/architecture/ddd-cqrs-event-source.md](../../docs/architecture/ddd-cqrs-event-source.md#22-event-store-postgresql--auditoria-e-compliance-at-14)
- Qualidade e observabilidade: [../../docs/architecture/quality-and-tracing.md](../../docs/architecture/quality-and-tracing.md)
- Modelagem de carteira: [../../docs/database/modelagem-banco-wallet.md](../../docs/database/modelagem-banco-wallet.md)
- Secrets e segurança: [../../docs/SECRETS_MANAGEMENT.md](../../docs/SECRETS_MANAGEMENT.md)
- Guia de testes: [../../docs/testing/COMPREHENSIVE_TESTING.md](../../docs/testing/COMPREHENSIVE_TESTING.md)
- Seções detalhadas de testes (Event Store): [../../docs/testing/COMPREHENSIVE_TESTING_SECTIONS.md](../../docs/testing/COMPREHENSIVE_TESTING_SECTIONS.md)

## Histórico preservado

A versão anterior completa deste README foi preservada em:

- [../../docs/archive/wallet-service_README_legacy_2026-03-09.md](../../docs/archive/wallet-service_README_legacy_2026-03-09.md)

## Estado do Documento

- Status: `canônico`
- Última consolidação: 2026-03-11
