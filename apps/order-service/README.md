# Order Service

Microsserviço responsável pelo fluxo de ordens (Command Side) e projeção de histórico (Query Side).

## Responsabilidades

- Receber ordens (`POST /api/v1/orders`) com autenticação JWT.
- Persistir ordem e outbox na mesma transação (Outbox Pattern).
- Consumir eventos financeiros da Wallet e evoluir estado da ordem.
- Executar integração com motor de match em Redis (Lua script).
- Projetar histórico em MongoDB para leitura (`GET /api/v1/orders`).
- Manter rastreabilidade e observabilidade (tracing + métricas).

## Endpoints principais

- `POST /api/v1/orders`
- `GET /api/v1/orders`
- `GET /api/v1/orders/{orderId}`
- `POST /admin/projections/rebuild`
- `GET /admin/events`

## Estrutura (alto nível)

```text
src/main/java/com/vibranium/orderservice/
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
docker compose -f infra/docker-compose.dev.yml up -d

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
- DDD/CQRS/Event Sourcing: [../../docs/architecture/ddd-cqrs-event-source.md](../../docs/architecture/ddd-cqrs-event-source.md)
- Motor Redis e matching: [../../docs/architecture/motor-order-book.md](../../docs/architecture/motor-order-book.md)
- Modelagem do domínio order: [../../docs/database/modelagem-banco-order.md](../../docs/database/modelagem-banco-order.md)
- Guia de testes: [../../docs/testing/COMPREHENSIVE_TESTING.md](../../docs/testing/COMPREHENSIVE_TESTING.md)

## Histórico preservado

A versão anterior completa deste README foi preservada em:

- [../../docs/archive/order-service_README_legacy_2026-03-09.md](../../docs/archive/order-service_README_legacy_2026-03-09.md)

## Estado do Documento

- Status: `canônico`
- Última consolidação: 2026-03-09
