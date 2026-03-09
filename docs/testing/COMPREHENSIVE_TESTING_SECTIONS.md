# Índice da Versão Legada de Testes

Mapa de seções da versão completa preservada em:

- [../archive/COMPREHENSIVE_TESTING_legacy_2026-03-09.md](../archive/COMPREHENSIVE_TESTING_legacy_2026-03-09.md)

## Seções Principais (H2)

| Linha | Seção |
|---:|---|
| 3 | 📋 Conteúdo |
| 49 | Visão Geral |
| 63 | Quick Start |
| 117 | Estrutura de Testes |
| 137 | Hierarquia de Classes Base |
| 269 | Tipos de Testes |
| 337 | Padrões de Teste |
| 999 | Executando Testes |
| 1046 | Ferramentas e Bibliotecas |
| 1164 | Ambientes Docker |
| 1196 | Cobertura de Código |
| 1259 | Debug Remoto |
| 1302 | Checklist de Qualidade |
| 1344 | Troubleshooting |
| 1727 | Integração CI/CD |
| 1767 | Testes de Outbox — Polling SKIP LOCKED |
| 1938 | Partial Fill — Requeue Atômico e Idempotência por eventId (US-002) |
| 2086 | Invariantes de Domínio Wallet — Encapsulamento de Agregado (US-005) |
| 2272 | Routing Key Literal Guard — Padronização Arquitetural (AT-02.2) |
| 2352 | Criação Lazy Determinística de OrderDocument (AT-05.1) |
| 2462 | Idempotência Atômica com MongoTemplate (AT-05.2) |
| 2582 | Índice MongoDB history.eventId com sparse (AT-06.1) |
| 2639 | Testes de Segurança Spring Security Test (AT-10.3) |
| 2719 | Saga Timeout + Bean Clock (AT-09.1 + AT-09.2) |
| 2842 | Auto ACK em Listeners de Projeção MongoDB (AT-1.2.1) |
| 2955 | MongoDB Replica Set rs0 no Staging (AT-1.3.2) |
| 3040 | @JsonIgnoreProperties em Todos os Records — Forward Compatibility (AT-5.2.1) |
| 3157 | Rotas GET Orders no Kong — Query Side CQRS via Gateway (Atividade 2) |
| 3310 | Event Store Imutável — Auditoria e Replay de Eventos (AT-14) |
| 3414 | RNF01 — Validação de Alta Escalabilidade (5.000 trades/s) |
| 3641 | Referências |
| 3654 | Segurança de Container — Non-Root User + Shell Form ENTRYPOINT (AT-1.5.1) |
| 3716 | Saga TCC — tryMatch() fora de @Transactional + Compensação Redis (AT-2.1.1) |
| 3829 | DLX nas Filas de Projeção — Mensagens Tóxicas para DLQ (AT-2.2.1) |
| 3939 | DLX na Fila wallet.keycloak.events — DLQ para Registro Keycloak (AT-2.2.2) |
| 4065 | Limpeza de Tabelas de Suporte — OutboxCleanupJob e IdempotencyKeyCleanupJob (AT-2.3.1) |
| 4187 | PRICE_PRECISION 10^8 — Precisão de Preço com 8 Casas Decimais (AT-3.2.1) |
| 4275 | Ownership Check em GET /orders/{orderId} — Proteção IDOR/BOLA (AT-4.1.1) |
| 4368 | @PreAuthorize + Pageable em GET /wallets — Controle de Acesso Admin (AT-4.2.1) |
| 4526 | Externalização de Senhas via Variáveis de Ambiente — Compose Files (AT-4.3.1) |
| 4640 | Multi-Match Loop Atômico no Lua EVAL — Consumo de Liquidez Total (AT-3.1.1) |
| 4768 | Inicialização do Redis Cluster no Staging — redis-cluster-init (AT-5.1.1) |
| 4932 | Deduplicação de Ordens no Redis via Lua — HEXISTS Guard (AT-16) |
| 5025 | Atomicidade Redis+PostgreSQL com Lua Compensatório — undo_match.lua (AT-17) |

## Seções com AT*

| Linha | AT |
|---:|---|
| 2272 | Routing Key Literal Guard — Padronização Arquitetural (AT-02.2) |
| 2352 | Criação Lazy Determinística de OrderDocument (AT-05.1) |
| 2462 | Idempotência Atômica com MongoTemplate (AT-05.2) |
| 2582 | Índice MongoDB history.eventId com sparse (AT-06.1) |
| 2639 | Testes de Segurança Spring Security Test (AT-10.3) |
| 2719 | Saga Timeout + Bean Clock (AT-09.1 + AT-09.2) |
| 2842 | Auto ACK em Listeners de Projeção MongoDB (AT-1.2.1) |
| 2955 | MongoDB Replica Set rs0 no Staging (AT-1.3.2) |
| 3040 | @JsonIgnoreProperties em Todos os Records — Forward Compatibility (AT-5.2.1) |
| 3310 | Event Store Imutável — Auditoria e Replay de Eventos (AT-14) |
| 3654 | Segurança de Container — Non-Root User + Shell Form ENTRYPOINT (AT-1.5.1) |
| 3716 | Saga TCC — tryMatch() fora de @Transactional + Compensação Redis (AT-2.1.1) |
| 3829 | DLX nas Filas de Projeção — Mensagens Tóxicas para DLQ (AT-2.2.1) |
| 3939 | DLX na Fila wallet.keycloak.events — DLQ para Registro Keycloak (AT-2.2.2) |
| 4065 | Limpeza de Tabelas de Suporte — OutboxCleanupJob e IdempotencyKeyCleanupJob (AT-2.3.1) |
| 4187 | PRICE_PRECISION 10^8 — Precisão de Preço com 8 Casas Decimais (AT-3.2.1) |
| 4275 | Ownership Check em GET /orders/{orderId} — Proteção IDOR/BOLA (AT-4.1.1) |
| 4368 | @PreAuthorize + Pageable em GET /wallets — Controle de Acesso Admin (AT-4.2.1) |
| 4526 | Externalização de Senhas via Variáveis de Ambiente — Compose Files (AT-4.3.1) |
| 4640 | Multi-Match Loop Atômico no Lua EVAL — Consumo de Liquidez Total (AT-3.1.1) |
| 4768 | Inicialização do Redis Cluster no Staging — redis-cluster-init (AT-5.1.1) |
| 4932 | Deduplicação de Ordens no Redis via Lua — HEXISTS Guard (AT-16) |
| 5025 | Atomicidade Redis+PostgreSQL com Lua Compensatório — undo_match.lua (AT-17) |
