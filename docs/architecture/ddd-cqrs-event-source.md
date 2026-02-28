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




2. **Contexto do Livro de Ofertas (Order Book):** Teremos um conceito de `Order` que lida com as regras de preço e quantidade.
* O agregado `Order` possui a máquina de estados `PENDING → OPEN → PARTIAL → FILLED` e o invariante `remainingAmount >= 0`.
  * Cada chamada ao método `applyMatch(executedQty)` decrementa `remainingAmount` e transita para `PARTIAL` (se há residual) ou `FILLED` (se zerou).
  * **O Redis Sorted Set (livro) e o PostgreSQL (estado da ordem) ficam sincronizados pelo Script Lua atômico**: o Lua faz o `ZADD` do residual e retorna a quantidade executada; o Java usa a quantidade retornada para chamar `applyMatch` e persistir.
* A comunicação entre a `Wallet` e o `Order Book` não é feita chamando métodos um do outro diretamente, mas sim conversando através de **Eventos** (nosso Event Storming!).



---

## 2. Event Sourcing (Fontes de Eventos) 📜

### O que é e para que serve?

Em um sistema tradicional de CRUD (Create, Read, Update, Delete), quando você altera um dado, o dado anterior é apagado para sempre. Se você tinha R$ 100 e gastou R$ 20, o banco de dados agora só diz: "Saldo: R$ 80". O histórico sumiu.

**Event Sourcing** muda isso. Em vez de salvar o "estado atual", nós salvamos **tudo o que aconteceu (o histórico de eventos)**.

* *Exemplo do dia a dia:* O extrato do seu banco. O banco não salva apenas o seu saldo final; ele salva cada depósito, saque e taxa (os eventos). O seu saldo final é apenas a soma matemática de todos esses eventos.

### Como será aplicado no nosso projeto?

O desafio exige que as transações tenham **rastreabilidade**, ou seja, um histórico claro.

* 
**O Problema:** Em um Livro de Ofertas com robôs operando freneticamente, não podemos perder o rastro de *quando* uma ordem foi criada, *quando* o saldo foi bloqueado, e *quando* o match aconteceu.


* **A Solução no Projeto:** 1. Cada ação executada vai gerar um Evento Imutável (ex: `OrdemVendaRecebida`, `FundosEmTradeBloqueados`, `MatchRealizado`).
2. Todos esses eventos serão enfileirados no **RabbitMQ**.
3. Nós vamos usar o **MongoDB** como nosso grande "Diário de Bordo" (Event Store). Ele vai salvar cada evento como um documento. Assim, se a plataforma precisar auditar porque o saldo de um usuário está errado, podemos olhar o histórico do MongoDB e reconstruir a linha do tempo exata segundo a segundo!

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
          ↓ WAL (Write-Ahead Log)
    ┌─────────────┐
    │   Debezium  │  ← lê CDC do WAL, sem polling
    │  Embedded   │
    └──────┬──────┘
           ↓ claimAndPublish() com UPDATE atômico
    ┌──────────────┐
    │   RabbitMQ   │  exchange: vibranium.events (topic)
    └──────────────┘
```

1. O `WalletService` persiste o saldo **e** insere um registro em `outbox_message` na **mesma transação ACID** do PostgreSQL.
2. O **Debezium Embedded** (`DebeziumOutboxEngine`) monitora o WAL e captura cada INSERT em `outbox_message` em tempo real (< 100 ms).
3. O `OutboxPublisherService` faz um `UPDATE outbox_message SET processed=true WHERE id=? AND processed=false` **atômico** antes de publicar — garantindo que múltiplas instâncias do serviço não dupliquem a entrega.
4. A publicação no RabbitMQ usa `@Retryable` (Spring Retry) com *backoff* exponencial: 5 tentativas, de 500 ms a 10 s — garantia  **at-least-once** mesmo se o broker estiver temporariamente indisponível.

### Roteamento de eventos (`EventRoute`)

Cada `eventType` no `outbox_message` é mapeado para uma *routing key* na exchange `vibranium.events` (topic):

| eventType | routing key |
|-----------|-------------|
| `FundsReservedEvent` | `wallet.events.funds-reserved` |
| `FundsReservationFailedEvent` | `wallet.events.funds-reservation-failed` |
| `FundsSettledEvent` | `wallet.events.funds-settled` |
| `WalletCreatedEvent` | `wallet.events.wallet-created` |

### Por que Debezium CDC e não polling?

| Critério | `@Scheduled` polling | Debezium CDC |
|----------|---------------------|--------------|
| Latência | Depende do intervalo (>100ms) | < 100 ms (reativo ao WAL) |
| I/O no banco | Leitura periódica mesmo sem eventos | Zero overhead sem eventos |
| Consistência do offset | Precisa de coluna `processed_at` + index | WAL position é o offset (atômico) |
| Multi-instância | Precisa de lock de aplicação | Claim atômico via UPDATE |
| Depende do clock | Sim (race condition com `created_at`) | Não (LSN do WAL é monotônico) |

---

## 3. CQRS (Command Query Responsibility Segregation) 🔀

### O que é e para que serve?

CQRS significa "Segregação de Responsabilidade de Comando e Consulta". O nome é longo, mas a ideia é simples: **Você separa a parte do código que GRAVA dados da parte do código que LÊ dados.**

* **Command (Comandos/Gravação):** São operações que alteram o estado do sistema (ex: Fazer uma compra). É uma operação pesada, que exige validações, bloqueios (locks) no banco e verificações de segurança.
* **Query (Consultas/Leitura):** São operações que apenas devolvem dados para a tela (ex: Ver o saldo atual ou ver a lista de ofertas). É uma operação que precisa ser extremamente rápida e não altera nada.

Em sistemas comuns, usamos o mesmo banco e as mesmas classes para ler e gravar. Mas quando temos 5000 trades por segundo, se quem está lendo concorrer com quem está gravando, o banco trava (Deadlock).

### Como será aplicado no nosso projeto?

Como a performance e a concorrência devem ser muito bem pensadas (milhares de usuários colocando ordens no mesmo milésimo de segundo), aplicaremos CQRS da seguinte forma:

* **O Lado Command (A Escrita via API POST):** Quando o usuário envia uma ordem de compra, essa requisição passa por validações complexas, atualiza o saldo (ACID) no **PostgreSQL** para evitar fraudes, e vai para o **Redis** processar o match. Essa via é otimizada para segurança e cálculos.
* **O Lado Query (A Leitura via API GET):**
Quando o usuário quer ver o histórico de transações dele ou o estado atual do Livro de Ofertas, ele **não** vai bater no banco de dados principal (PostgreSQL) que está ocupado processando compras e vendas. A API de leitura vai buscar os dados consolidados no **MongoDB** ou consultar a lista já pronta e ordenada diretamente no **Redis** (onde não há locks pesados).

### Partial Fill no contexto CQRS

Um cenário importante para o Command Side é o **partial fill**: a ordem entra com `qty=100` mas apenas `qty=40` estão disponíveis no livro.

```
Command Side (write):                     Redis (livro):
  Order.status = PARTIAL                    vibranium:bids
  Order.remainingAmount = 60                  score=500  member="orderId|...|60|..."
```

O ponto crítico: **o Redis e o PostgreSQL precisam ficar consistentes**. O Script Lua trata isso atomicamente:
1. Dentro do `EVAL`: remove a contraparte ASK, calcula o residual, reinsere ASK com `qty=residual`, retorna `matchedQty` e `remainingCounterpartQty`.
2. O Java usa `matchedQty` para chamar `order.applyMatch(matchedQty)` e persiste no PostgreSQL.

Não há two-phase commit — se o PostgreSQL falhar após o Lua, a ordem fica `PENDING` no banco mas o residual já está no Redis. A guarda de idempotência por `eventId` e o reprocessamento da mensagem pelo broker (at-least-once) garantem convergência.

### Resumo Visual do CQRS no MVP:

1. Usuário envia ordem de compra ➡️ **API de Commands** ➡️ Valida no Postgres ➡️ Joga pro Redis.
2. Robô quer ver as 10 melhores ofertas ➡️ **API de Queries** ➡️ Pega direto do Redis quase instantaneamente.

---

### 🚀 Conclusão para o Desafio

Ao juntar esses três padrões, nós criamos um sistema **inquebrável** para o MVP:

* O **DDD** garante que o código faz sentido para o negócio financeiro, blindando as regras na Carteira e no Livro de Ofertas.
* O **Event Sourcing** (via RabbitMQ e Mongo) garante que nenhum dado se perde e temos o extrato histórico de tudo o que os robôs fizeram.


* O **CQRS** garante que os milhares de robôs pesquisando preços não vão derrubar ou atrasar o motor (Redis) que está fazendo as transações de compra e venda ocorrerem em tempo real.
