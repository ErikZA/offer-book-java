# 📚 Guia de Arquitetura: DDD, Event Sourcing e CQRS

Quando construímos sistemas complexos e que precisam lidar com muita carga (como 5000 transações por segundo ), não podemos simplesmente colocar todo o código em um único lugar e torcer para funcionar. Precisamos de **padrões de arquitetura**.

Neste guia, vamos desmistificar três dos padrões mais famosos do mercado: **DDD**, **Event Sourcing** e **CQRS**, e mostrar exatamente como eles resolvem os problemas do nosso MVP de negociação de Vibranium.

---

## 1. Domain-Driven Design (DDD) 🧠

### O que é e para que serve?

DDD (Projeto Orientado ao Domínio) é uma forma de pensar e estruturar o código focada no **negócio** (o domínio).
Em vez de começar pensando em tabelas de banco de dados (`TB_USUARIO`, `TB_ORDEM`), você começa pensando em como os especialistas do negócio falam e nas regras que não podem ser violadas.

Dois conceitos essenciais do DDD para iniciantes:

* **Linguagem Ubíqua (Onipresente):** Desenvolvedores e pessoas de negócios devem usar os mesmos termos. Se o negócio chama de "Livro de Ofertas" e "Match", no código teremos classes chamadas `OrderBook` e métodos chamados `executeMatch()`. Nada de inventar nomes técnicos malucos.
* **Bounded Contexts (Contextos Delimitados):** É a divisão do sistema em "mini-mundos" independentes. O mundo da *Carteira* não precisa saber os detalhes de como o mundo do *Livro de Ofertas* faz o cruzamento de preços.
* **Aggregates (Agregados):** São os "guardiões" das regras de negócio. Uma classe raiz que protege a consistência dos dados internos.

### Como será aplicado no nosso projeto?

No nosso MVP, usaremos o DDD para separar claramente as responsabilidades:

1. **Contexto da Carteira (Wallet):** Teremos um agregado chamado `Wallet`. Ele é o guardião do dinheiro (Reais) e do ativo (Vibranium).


* *A Regra (Invariante):* Nunca podemos deixar o saldo ficar negativo. É a classe `Wallet` que vai receber a ordem de bloquear fundos e garantir que o usuário realmente tem o dinheiro antes da ordem ir para o mercado.
* **US-005 — Encapsulamento de Invariantes:** Os setters públicos de saldo (`setBrlLocked`, `setBrlAvailable`, etc.) foram removidos do agregado. Toda mutação de estado passa obrigatoriamente pelos métodos de comportamento:
  * `reserveFunds(AssetType, amount)` — bloqueia saldo antes de uma ordem entrar no livro
  * `applyBuySettlement(brl, vib)` — liquida o trade do lado comprador (libera BRL locked, credita VIB)
  * `applySellSettlement(vib, brl)` — liquida o trade do lado vendedor (libera VIB locked, credita BRL)
  * `adjustBalance(brlDelta, vibDelta)` — ajuste administrativo via delta
  
  Cada método valida suas pré-condições antes de qualquer mutação, lançando `InsufficientFundsException` se a invariante for violada. `Wallet` utiliza **optimistic locking via `@Version`** para detecção de conflitos concorrentes na camada JPA.




2. **Contexto do Livro de Ofertas (Order Book):** Teremos um conceito de `Order` que lida com as regras de preço e quantidade.
* O agregado `Order` possui a máquina de estados `PENDING → OPEN → PARTIAL → FILLED` e impõe suas invariantes internamente:
  * `markAsOpen()` — aceita somente se `status == PENDING`; lança `IllegalStateException` caso contrário.
  * `applyMatch(executedQty)` — aceita `OPEN` ou `PARTIAL`; rejeita `qty ≤ 0`, `qty > remainingAmount` e status terminal (`CANCELLED`, `FILLED`, `PENDING`) com exceção rastreável.
  * `cancel(reason)` — rejeita ordens `FILLED` (liquidação já ocorreu e não pode ser revertida).
  * `transitionTo(OrderStatus)` é **package-private** — disponível apenas para testes de integração que montam cenários arbitrários; nunca chamado em produção.
  * **Invariantes formais do agregado:**
    - `remainingAmount >= 0` — nunca negativo.
    - `FILLED`    → `remainingAmount == 0`.
    - `PARTIAL`   → `0 < remainingAmount < originalAmount`.
    - `OPEN`      → `remainingAmount == originalAmount`.
    - `CANCELLED` → `remainingAmount > 0` (liquidação parcial não ocorreu).
  * **Política DLQ para race conditions:** `applyMatch` chamado em `CANCELLED` (ex: evento de match chegando após cancelamento assíncrono) lança `IllegalStateException` → container RabbitMQ envia NACK → mensagem roteada para DLQ.
  * Cada chamada ao método `applyMatch(executedQty)` decrementa `remainingAmount` e transita para `PARTIAL` (se há residual) ou `FILLED` (se zerou).
  * **O Redis Sorted Set (livro) e o PostgreSQL (estado da ordem) ficam sincronizados pelo Script Lua atômico**: o Lua faz o `ZADD` do residual e retorna a quantidade executada; o Java usa a quantidade retornada para chamar `applyMatch` e persistir.
  * **Compensação Lua (AT-17):** Se a persistência JPA (Fase 3) falhar após um match bem-sucedido no Redis, o `undo_match.lua` reverte atomicamente as contrapartes consumidas/modificadas — restaurando o livro ao estado anterior ao match. Isso complementa o `removeFromBook` do AT-2.1.1 (que só removia a ordem ingressante).
* A comunicação entre a `Wallet` e o `Order Book` não é feita chamando métodos um do outro diretamente, mas sim conversando através de **Eventos** (nosso Event Storming!).



---

## 2. Event Sourcing (Fontes de Eventos) 📜

### O que é e para que serve?

Em um sistema tradicional de CRUD (Create, Read, Update, Delete), quando você altera um dado, o dado anterior é apagado para sempre. Se você tinha R$ 100 e gastou R$ 20, o banco de dados agora só diz: "Saldo: R$ 80". O histórico sumiu.

**Event Sourcing** muda isso. Em vez de salvar o "estado atual", nós salvamos **tudo o que aconteceu (o histórico de eventos)**.

* *Exemplo do dia a dia:* O extrato do seu banco. O banco não salva apenas o seu saldo final; ele salva cada depósito, saque e taxa (os eventos). O seu saldo final é apenas a soma matemática de todos esses eventos.

### Como está aplicado no projeto (implementação atual)

O Event Sourcing está implementado como **Read Model via Projeção de Eventos** (Projection-Based Event Sourcing),
que é uma forma pragmática do padrão adequada ao MVP:

1. **Cada ação executada gera um Evento Imutável** enfileirado no RabbitMQ:
   - `OrderReceivedEvent` — ordem aceita pelo Command Side
   - `FundsReservedEvent` — fundos bloqueados pelo wallet-service
   - `MatchExecutedEvent` — cruzamento realizado pelo Motor de Match
   - `OrderCancelledEvent` — ordem cancelada (fundos insuficientes)

2. **O MongoDB funciona como Diário de Bordo** (`OrderDocument.history[]`):
   - Cada evento é projetado como uma entrada imutável no array `history[]`
   - O `eventId` garante deduplicação: a mesma mensagem re-entregue é ignorada
   - O status (`PENDING → OPEN → FILLED/PARTIAL/CANCELLED`) reflete o estado atual

3. **Rastreabilidade completa**: `GET /api/v1/orders/{orderId}` retorna o histórico
   completo da ordem — quando foi aceita, quando fundos foram reservados, quando o
   match ocorreu — tudo em um único documento MongoDB, sem JOIN.

**Exemplo de `OrderDocument` após ciclo completo:**
```json
{
  "orderId": "abc-123",
  "status": "FILLED",
  "history": [
    {"eventType": "ORDER_RECEIVED",  "timestamp": "..."},
    {"eventType": "FUNDS_RESERVED",  "timestamp": "..."},
    {"eventType": "MATCH_EXECUTED",  "matchAmount": 0.5, "timestamp": "..."}
  ]
}
```

---

## 2.1 Transactional Outbox Pattern + CDC (como os eventos chegam ao RabbitMQ) 📬

### O problema da dupla escrita

Em sistemas distribuídos existe um problema clássico: o serviço precisa, na mesma operação de negócio, **gravar no banco** (ex.: debitar saldo) e **publicar um evento** (ex.: `FundsReservedEvent`). Se o banco confirmar mas a mensageria cair antes do publish — ou o inverso — o sistema fica inconsistente.

Soluções como 2-phase commit (XA) introduzem latência e lock global incompatíveis com 5 000 trades/s.

### A solução: Transactional Outbox

```
┌──────────────────────────────────────────┐
│  @Transactional (commit atômico no PG)   │
│  1. wallet.update(saldo)                 │
│  2. outbox_message.insert(event_payload) │
└──────────────────────────────────────────┘
          ↓ @Scheduled (polling periódico)
    ┌────────────────────────┐
    │  OutboxPublisherService  │  ← SELECT FOR UPDATE SKIP LOCKED
    │  (N instâncias seguras)  │
    └────────────────────────┘
           ↓ claimAndPublish() com UPDATE atômico
    ┌──────────────┐
    │   RabbitMQ   │  exchange: vibranium.events (topic)
    └──────────────┘
```

1. O `WalletService` persiste o saldo **e** insere um registro em `outbox_message` na **mesma transação ACID** do PostgreSQL.
2. O **`OutboxPublisherService`** executa periodicamente via `@Scheduled` e busca mensagens pendentes com `SELECT FOR UPDATE SKIP LOCKED`, garantindo que múltiplas instâncias processem lotes diferentes sem duplicatas.
3. O `OutboxPublisherService` faz um `UPDATE outbox_message SET processed=true WHERE id=? AND processed=false` **atômico** antes de publicar — garantindo claim exclusivo.
4. A publicação no RabbitMQ usa `@Retryable` (Spring Retry) com *backoff* exponencial: 5 tentativas, de 500 ms a 10 s — garantia  **at-least-once** mesmo se o broker estiver temporariamente indisponível.

### Roteamento de eventos (`EventRoute`)

Cada `eventType` no `outbox_message` é mapeado para uma *routing key* na exchange `vibranium.events` (topic):

| eventType | routing key |
|-----------|-------------|
| `FundsReservedEvent` | `wallet.events.funds-reserved` |
| `FundsReservationFailedEvent` | `wallet.events.funds-reservation-failed` |
| `FundsSettledEvent` | `wallet.events.funds-settled` |
| `WalletCreatedEvent` | `wallet.events.wallet-created` |

### Por que Polling SKIP LOCKED?

| Critério | `@Scheduled` polling + SKIP LOCKED | Debezium CDC (removido) |
|----------|-------------------------------------|------------------------|
| Latência | Depende do intervalo (≥2 s) | < 100 ms (reativo ao WAL) |
| I/O no banco | Query periódica (baixo custo com índice parcial) | Zero overhead sem eventos |
| Multi-instância | ✅ N instâncias seguras via SKIP LOCKED | ❌ Single-instance (ADR-001) |
| Dependência | Nenhuma adicional | Debezium + wal_level=logical |
| Complexidade operacional | Baixa | Alta (slots, WAL bloat) |

---

## 2.2 Event Store PostgreSQL — Auditoria e Compliance (AT-14)

### O problema

O MongoDB Read Model (`OrderDocument.history[]`) é uma projeção **eventual** — ideal para consultas rápidas, mas não garante imutabilidade (documentos podem ser atualizados). O Transactional Outbox garante delivery, mas mensagens são deletadas após publicação. Nenhum dos dois oferece um **registro permanente e inviolável** para auditoria ou replay.

### A solução: Event Store complementar

A tabela `tb_event_store` no PostgreSQL funciona como um **log append-only** de todos os eventos do domínio:

```
┌──────────────────────────────────────────────────────┐
│  @Transactional (commit atômico no PG)               │
│  1. orderRepository.save(order)          → Estado     │
│  2. outboxRepository.save(outboxMsg)     → Delivery   │
│  3. eventStoreService.append(entry)      → Auditoria  │
└──────────────────────────────────────────────────────┘
```

Os três registros são persistidos **na mesma transação ACID** — se qualquer um falhar, tudo é revertido.

### Imutabilidade via Triggers PostgreSQL

```sql
CREATE OR REPLACE FUNCTION fn_deny_event_store_mutation()
    RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'tb_event_store is append-only: % not allowed', TG_OP;
END;
$$ LANGUAGE plpgsql;
```

Dois triggers (`BEFORE UPDATE`, `BEFORE DELETE`) garantem que registros **nunca** são alterados ou removidos — a proteção é no nível do banco, independente da aplicação.

### Diferença entre Outbox, Event Store e Read Model

| Aspecto | Outbox (`tb_order_outbox`) | Event Store (`tb_event_store`) | Read Model (`history[]` MongoDB) |
|---------|---------------------------|-------------------------------|----------------------------------|
| **Propósito** | Garantir delivery ao RabbitMQ | Registro permanente imutável | Consulta rápida para UI |
| **Mutabilidade** | DELETE após publicação | Append-only (TRIGGER protegido) | Upsert via projeção |
| **Ciclo de vida** | Transiente (publicar → remover) | Permanente (nunca removido) | Eventual (re-projetável) |
| **Consulta** | Polling interno | Replay temporal + por aggregate | Paginação por userId |
| **Garantia** | At-least-once delivery | Auditoria e compliance | Leitura performática |

### Replay Temporal

O Event Store permite reconstruir o estado de qualquer ordem em qualquer ponto no tempo:

```java
// Replay até um instante específico
List<EventStoreEntry> events = eventStoreRepository
    .findByAggregateIdAndOccurredOnLessThanEqualOrderBySequenceIdAsc(orderId, until);
```

**Caso de uso prático:** "Prove que a ordem `abc-123` estava no estado `OPEN` às 14:32:45Z":
1. Query com `aggregateId=abc-123` e `until=2026-03-07T14:32:45Z`
2. Aplique os eventos em sequência: `PENDING → OPEN`
3. O último evento antes do cutoff comprova o estado

---

## 3. CQRS (Command Query Responsibility Segregation) 🔀

### O que é e para que serve?

CQRS significa "Segregação de Responsabilidade de Comando e Consulta". O nome é longo, mas a ideia é simples: **Você separa a parte do código que GRAVA dados da parte do código que LÊ dados.**

* **Command (Comandos/Gravação):** São operações que alteram o estado do sistema (ex: Fazer uma compra). É uma operação pesada, que exige validações, bloqueios (locks) no banco e verificações de segurança.
* **Query (Consultas/Leitura):** São operações que apenas devolvem dados para a tela (ex: Ver o saldo atual ou ver a lista de ofertas). É uma operação que precisa ser extremamente rápida e não altera nada.

Em sistemas comuns, usamos o mesmo banco e as mesmas classes para ler e gravar. Mas quando temos 5000 trades por segundo, se quem está lendo concorrer com quem está gravando, o banco trava (Deadlock).

### Como está aplicado no projeto (implementação atual)

O CQRS está implementado nos dois eixos que a arquitetura define:

**Command Side (escrita)** \u2014 `POST /api/v1/orders`:
1. Valida o usuário contra `tb_user_registry` (PostgreSQL)
2. Persiste `Order{PENDING}` em `tb_orders` (PostgreSQL, ACID)
3. Publica `ReserveFundsCommand` para o wallet-service (RabbitMQ)
4. Publica `OrderReceivedEvent` em Virtual Thread (não bloqueia a transação JPA)
5. O wallet-service reserva fundos → Motor de Match Redis (Script Lua atômico)

**Query Side (leitura)** \u2014 `GET /api/v1/orders` / `GET /api/v1/orders/{id}`:
1. `OrderEventProjectionConsumer` ouve 4 tipos de evento via RabbitMQ
2. Projeta os eventos em `OrderDocument` no MongoDB (consistência eventual)
3. `OrderQueryController` lê direto do MongoDB — sem JOIN, sem lock no PostgreSQL

### Partial Fill no contexto CQRS

Um cenário importante para o Command Side é o **partial fill**: a ordem entra com `qty=100` mas apenas `qty=40` estão disponíveis no livro.

```
Command Side (write):                     Redis (livro):
  Order.status = PARTIAL                    {vibranium}:bids
  Order.remainingAmount = 60                  score=500  member="orderId|...|60|..."
```

> **AT-11.1:** As keys do Redis usam hash tags `{vibranium}` para compatibilitar com Redis Cluster.
> Ver [motor-order-book.md](motor-order-book.md#4-hash-tags-redis-e-compatibilidade-com-redis-cluster-at-111).

O ponto crítico: **o Redis e o PostgreSQL precisam ficar consistentes**. O Script Lua trata isso atomicamente:
1. Dentro do `EVAL`: remove a contraparte ASK, calcula o residual, reinsere ASK com `qty=residual`, retorna `matchedQty` e `remainingCounterpartQty`.
2. O Java usa `matchedQty` para chamar `order.applyMatch(matchedQty)` e persiste no PostgreSQL.

Não há two-phase commit — se o PostgreSQL falhar após o Lua, a ordem fica `PENDING` no banco mas o residual já está no Redis. A guarda de idempotência por `eventId` e o reprocessamento da mensagem pelo broker (at-least-once) garantem convergência.

### Resumo Visual do CQRS no MVP:

**Topologia do Read Model:**
```
vibranium.events (TopicExchange)
  │
  ├── order.projection.received   → onOrderReceived   → OrderDocument{PENDING}
  ├── order.projection.funds-reserved → onFundsReserved → status → OPEN
  ├── order.projection.match-executed → onMatchExecuted → FILLED/PARTIAL
  └── order.projection.cancelled  → onOrderCancelled  → status CANCELLED
```

**Por que MongoDB para o Read Model?**
- Documentos desnormalizados com `history[]` embutido → um único `findById()` retorna o rastreio completo
- Sem locks de leitura: PostgreSQL permanece dedicado às escritas da Saga
- `@CompoundIndex({userId: 1, createdAt: -1})` suporta a query paginada por usuário em O(log n)

**Idempotência no Read Model:**

Cada entrada no `history[]` usa o `eventId` do evento como chave de deduplicação.
Re-entregas do RabbitMQ (broker restart, NACK etc.) são descartadas silenciosamente
se o `eventId` já existir no array — garantindo `exactly-once` na projeção.

### Resumo Visual do CQRS no MVP (implementado):

```
POST /api/v1/orders ──► Command Side ──► PostgreSQL (ACID)
                                    └──► Redis (Match)
                                    └──► RabbitMQ ──► MongoDB (Read Model)
                                         (Virtual Thread)

GET  /api/v1/orders ──► Query Side  ──► MongoDB (OrderDocument)
```

**Replay Mechanism — Reconstrução da Projeção (AT-08):**

Se o MongoDB for corrompido ou perder dados, o Read Model pode ser completamente reconstruído:
```
POST /admin/projections/rebuild (ROLE_ADMIN)
   → Stream<Order> com cursor PostgreSQL (fetch size 500) — memória constante
   → Para cada Order: upsert OrderDocument + histórico do tb_order_outbox
   → ?mode=incremental — processa apenas ordens modificadas desde último rebuild
```

- **Idempotente:** upsert garante que 2x rebuild = mesmo resultado
- **Non-blocking:** leituras continuam durante o rebuild (sem delete + recreate)
- **Timeout:** configurável via `app.projection.rebuild.timeout-minutes` (padrão: 30)
- **Incremental automático:** `@Scheduled` com cron configurável (desabilitado por padrão)

1. Usuário envia ordem ➡️ **Command Side** ➡️ Valida no Postgres ➡️ Joga pro Redis.
2. Usuário quer ver histórico ➡️ **Query Side** ➡️ Lê do MongoDB com resposta < 50ms.
3. Robô quer ver as 10 melhores ofertas ➡️ **Query Side** ➡️ Pega direto do Redis quase instantaneamente.

---

### 🚀 Conclusão — Estado Atual do MVP

Ao juntar esses três padrões, criamos um sistema **robusto e escalável** para o MVP:

* O **DDD** garante que o código faz sentido para o negócio financeiro, blindando as regras na Carteira e no Livro de Ofertas.
* O **Event Sourcing** (via RabbitMQ + MongoDB `history[]`) garante que nenhum evento se perde e temos o extrato histórico auditável de tudo que aconteceu com cada ordem.
* O **CQRS** garante que os milhares de robôs pesquisando histórico de ordens (Query Side) não concorrem com o Motor de Match Redis nem com as escritas ACID no PostgreSQL (Command Side).

**Status de implementação (28/02/2026):**

| Padrão         | Status | Detalhe                                                              |
|----------------|--------|----------------------------------------------------------------------|
| DDD            | ✅     | `Order`, `UserRegistry`, `Wallet` como agregados; Bounded Contexts separados |
| DDD — State Machine | ✅ | `Order` impõe máquina de estados internamente (US-008); `transitionTo` package-private; guards em `markAsOpen`, `applyMatch`, `cancel` |
| DDD Wallet Invariants | ✅ | US-005: setters removidos; `applyBuySettlement`, `applySellSettlement`, `@Version` |
| Event Sourcing | ✅     | `OrderDocument.history[]` no MongoDB; idempotência por `eventId`     |
| CQRS Command   | ✅     | `POST /api/v1/orders` → PostgreSQL + Redis + RabbitMQ                |
| CQRS Query     | ✅     | `GET /api/v1/orders` → MongoDB (eventual consistency)                |
| Saga           | ✅     | Reserve Funds → Match Engine → FILLED/OPEN/CANCELLED                 |
| Optimistic Lock| ✅     | `@Version` em `Order` detecta conflitos de escrita concorrente        |
| DLQ Policy     | ✅     | `applyMatch` em status terminal → `IllegalStateException` → NACK → DLQ |
| Lazy Projection (AT-05.1) | ✅ | `createMinimalPending()` + `enrichFields()`; zero `IllegalStateException` por evento out-of-order; zero descarte silencioso |
| SLA 200ms p99  | ✅     | Virtual Threads + isolamento de containers em testes                  |
| Projection Rebuild (AT-08) | ✅ | `POST /admin/projections/rebuild` reconstrói Read Model MongoDB a partir do PG + Outbox; `Stream<Order>` cursor (fetch 500); upsert idempotente; rebuild incremental via `@Scheduled` |
