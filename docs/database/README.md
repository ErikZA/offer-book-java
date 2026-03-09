# Dados e Modelagem

Índice da documentação de dados e modelagem do projeto.

## Visão geral

- [data-base-storage.md](./data-base-storage.md): visão de armazenamento por contexto (PostgreSQL, MongoDB, Redis, Keycloak, Kong).

## Modelagem por contexto

- [modelagem-banco-wallet.md](./modelagem-banco-wallet.md): modelagem relacional do wallet-service.
- [modelagem-banco-order.md](./modelagem-banco-order.md): modelagem detalhada do order-service (MongoDB + event store).
- [modelagem-mongodb.md](./modelagem-mongodb.md): ponte conceitual consolidada para MongoDB Read Model.
- [modelagem-motor-redis.md](./modelagem-motor-redis.md): estrutura e estratégia do motor de match no Redis.
- [modelagem-kong-keycloak.md](./modelagem-kong-keycloak.md): persistência de Kong e Keycloak.

## Histórico

- [../archive/modelagem-mongodb_legacy_2026-03-09.md](../archive/modelagem-mongodb_legacy_2026-03-09.md): versão conceitual completa anterior de modelagem MongoDB.

## Papel deste arquivo

Este arquivo é o índice canônico da pasta `docs/database/`.
Os demais documentos da pasta trazem o detalhamento por tecnologia/contexto.
