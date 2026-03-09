# Arquitetura do Order Book (MVP)

Documento canônico de visão geral do MVP de Order Book.

## Objetivo

Apresentar o fluxo fim a fim da plataforma de negociação de Vibranium e orientar onde está cada detalhe técnico da arquitetura orientada a eventos.

## Visão Geral por Fases

1. **Onboarding e relação 1:1 (Usuário ↔ Carteira)**
- Usuário é registrado no IAM (Keycloak).
- `wallet-service` cria a carteira inicial associada ao usuário.

2. **Recepção da ordem e garantia de saldo**
- `order-service` recebe intenção de compra/venda.
- `wallet-service` valida e bloqueia fundos para a operação.

3. **Livro de ofertas e match**
- Ordem elegível é processada no motor de match (Redis).
- Match total/parcial gera eventos de domínio.

4. **Liquidação e histórico**
- Saldos são consolidados com semântica transacional.
- Read model/histórico é atualizado para consulta.

## Catálogo Simplificado

### Comandos (intenção)

- `SolicitarRegistroUsuario`
- `CriarCarteira`
- `SolicitarOrdemCompra`
- `SolicitarOrdemVenda`
- `BloquearFundosEmTrade`
- `ExecutarMatch`

### Eventos (fato ocorrido)

- `UsuarioRegistrado`
- `CarteiraCriada`
- `OrdemCompraRecebida` / `OrdemVendaRecebida`
- `FundosEmTradeBloqueados`
- `OrdemAdicionadaAoLivro`
- `MatchRealizado`
- `CompraConcretizada` / `VendaConcretizada`

## Navegação de Detalhes

- Fluxo visual (Event Storming): [order-book-mvp-flow.md](./order-book-mvp-flow.md)
- Sequência técnica por fase: [order-book-mvp-sequence.md](./order-book-mvp-sequence.md)
- DDD/CQRS/Event Sourcing: [ddd-cqrs-event-source.md](./ddd-cqrs-event-source.md)
- Motor de match (Redis + Lua): [motor-order-book.md](./motor-order-book.md)
- Qualidade e observabilidade: [quality-and-tracing.md](./quality-and-tracing.md)

## Histórico Preservado

A versão textual completa anterior deste documento foi preservada em:

- [../archive/order-book-mvp_legacy_2026-03-09.md](../archive/order-book-mvp_legacy_2026-03-09.md)

## Estado do Documento

- Status: `canônico`
- Última consolidação: 2026-03-09
