# Modelagem MongoDB (Guia Conceitual)

Este documento foi consolidado para reduzir redundância com a modelagem completa do `order-service`.

## Documento Canônico

Para modelagem detalhada (schema, estados, dicionário de dados e event store), utilize:

- [modelagem-banco-order.md](./modelagem-banco-order.md)

## Apoio de Arquitetura de Dados

- Visão poliglota de armazenamento: [data-base-storage.md](./data-base-storage.md)
- Arquitetura e projeções: [../architecture/ddd-cqrs-event-source.md](../architecture/ddd-cqrs-event-source.md)

## Conceitos-Chave Mantidos

- MongoDB é o **Read Model** no padrão CQRS (lado de consulta).
- Escrita no Mongo ocorre por **projeção de eventos**, não por comando direto de usuário.
- Histórico embutido (`history[]`) permite rastreabilidade por ordem.
- Índices são obrigatórios para manter baixa latência em alto volume de leitura.

## Histórico Preservado

A versão conceitual completa anterior foi preservada em:

- [../archive/modelagem-mongodb_legacy_2026-03-09.md](../archive/modelagem-mongodb_legacy_2026-03-09.md)

## Estado do Documento

- Status: `ponte`
- Última consolidação: 2026-03-09
