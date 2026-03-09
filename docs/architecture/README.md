# Arquitetura

Índice da documentação arquitetural do projeto.

## Comece por aqui

- [order-book-mvp.md](./order-book-mvp.md): visão canônica do fluxo do MVP.
- [order-book-mvp-flow.md](./order-book-mvp-flow.md): fluxo visual em Event Storming.
- [order-book-mvp-sequence.md](./order-book-mvp-sequence.md): sequência técnica por fases.

## Arquitetura de aplicação

- [ddd-cqrs-event-source.md](./ddd-cqrs-event-source.md): DDD, CQRS, Event Sourcing e Outbox.
- [motor-order-book.md](./motor-order-book.md): integração e lógica do motor de match.
- [consumer-prefetch-tuning.md](./consumer-prefetch-tuning.md): tuning de consumidores RabbitMQ.
- [adr-001-debezium-single-instance.md](./adr-001-debezium-single-instance.md): decisão arquitetural sobre Debezium embedded.

## Qualidade, observabilidade e stack

- [quality-and-tracing.md](./quality-and-tracing.md): tracing, métricas e qualidade.
- [tools-stack.md](./tools-stack.md): stack tecnológica do MVP.
- [redis-cluster-setup.md](./redis-cluster-setup.md): setup de Redis Cluster para HA.

## Histórico

- [../archive/order-book-mvp_legacy_2026-03-09.md](../archive/order-book-mvp_legacy_2026-03-09.md): versão textual completa anterior da visão MVP.

## Papel deste arquivo

Este arquivo é o índice canônico da pasta `docs/architecture/`.
Os demais documentos da pasta trazem o detalhamento por tópico.
