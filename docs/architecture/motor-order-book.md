# 📑 Guia de Integração e Lógica do Motor de Match

Para atingir a escala de **5.000 trades por segundo**, não podemos confiar em comunicações lentas. Nossa arquitetura utiliza uma mistura de **mensageria assíncrona** (para resiliência) e **scripts atômicos no Redis** (para velocidade).

## 1. Webhooks e Integrações entre Serviços

Diferente de sistemas simples onde um serviço chama o outro e espera a resposta (síncrono), aqui usamos o **RabbitMQ** para que os serviços "dancem" conforme os eventos acontecem.

### Fluxo de Onboarding (Keycloak ➔ Wallet)

* 
**O que acontece:** Quando um novo usuário se cadastra no Keycloak, ele dispara uma notificação (Webhook).


* 
**Ação:** O `Wallet Service` recebe esse aviso e cria automaticamente uma carteira vinculada 1:1 ao ID do usuário.



### Fluxo de Ordem (Order ➔ Wallet)

* 
**O que acontece:** O usuário envia uma ordem de compra pelo `Order Service`.


* 
**Ação:** Antes de tentar negociar, o `Order Service` avisa a `Wallet` via RabbitMQ: "Ei, verifique se esse usuário tem saldo e bloqueie o valor".


* 
**Resposta:** A `Wallet` responde com um evento de `FundosBloqueados` para que a ordem possa seguir para o mercado.



### Fluxo de Liquidação (Order ➔ Wallet)

* 
**O que acontece:** O motor de match encontra um par (Comprador e Vendedor).


* **Ação:** O `Order Service` publica o evento `MatchRealizado`.
* 
**Conclusão:** A `Wallet` ouve isso e faz a troca: tira o Vibranium de um, coloca no outro, e faz o mesmo com os Reais.



---

## 2. O Script Lua: O Motor Obrigatório ⚡

O desafio exige que o **Livro de Ofertas não admita concorrência**. Se dois robôs tentarem "morder" a mesma oferta de venda ao mesmo tempo, apenas um pode ganhar. O Script Lua resolve isso sendo **atômico**.

### Por que ele é obrigatório?

1. **Velocidade Máxima:** O script roda dentro da memória do Redis. Ele não precisa "viajar" pela rede para tomar decisões.


2. **Fila Indiana:** O Redis processa um script Lua por vez. Isso impede que duas ordens de compra "atropelem" a mesma ordem de venda.


3. **Tudo ou Nada:** Ou o match acontece por completo e as ordens são atualizadas, ou nada muda. Não existe meio-termo que cause erro de saldo.

### O que o script faz na prática?

1. **Recebe a Ordem:** O Java envia (ID, Lado, Preço, Quantidade) para o Redis.
2. **Compara o Topo:** Se for uma compra, o script olha o topo da fila de vendas (`asks`).
3. **Verifica o Preço:** Se o preço de compra for igual ou maior que o de venda, ele executa o match.
4. **Limpa a Fila:** Se a quantidade de uma oferta acabar, o script a remove do Redis (`ZREM`).
5. **Reinsere o Residual (atomicamente):** Se a execução for parcial, o residual é reinserido no mesmo `EVAL` — sem janela de concorrência.
6. **Avisa o Java:** O script retorna uma lista com quem cruzou, quanto foi executado, o tipo de fill e a quantidade residual da contraparte.

---

## 3. Partial Fill: Requeue Atômico da Ordem Residual (US-002)

### O problema original

O Lua já removia a contraparte do Sorted Set via `ZREM` antes do US-002, mas **não a reinseriam com a quantidade atualizada** quando o fill era parcial. Resultado: ordens parcialmente executadas simplesmente desapareciam do livro, quebrando a profundidade de mercado.

### Tipos de fill retornados pelo script

| `fillType` | Quem foi consumido | Quem tem residual | Residual vai para |
|---|---|---|---|
| `FULL` | Contraparte (total) | Nenhum residual | — |
| `PARTIAL_ASK` | Ingressante (total) | Contraparte (ASK sobrou) | `{vibranium}:asks` |
| `PARTIAL_BID` | Contraparte (ASK, total) | Ingressante (BID sobrou) | `{vibranium}:bids` |

### Design decision: Lua é a fonte de verdade

Existem duas abordagens possíveis para tratar o requeue:

**Opção A — requeue no Java** (`requeueResidual()` após `tryMatch()`)
```
tryMatch() → detecta PARTIAL → Java chama ZADD
```
Problema: entre o `ZREM` do Lua e o `ZADD` do Java há uma **janela de concorrência**. Um segundo consumidor poderia ler o livro nesse intervalo e não encontrar a contraparte — divergência transitória de estado.

**Opção B — requeue dentro do próprio Lua (adotada)**
```lua
-- Dentro de EVAL (atômico no Redis)
redis.call('ZREM', KEYS[1], askValue)   -- remove original
redis.call('ZADD', KEYS[1], askScore, newAskValue)  -- reinsere com qty atualizada
return {'MATCH', askValue, matchedQty, 'PARTIAL_ASK', remainingQty}
```
O Redis executa scripts Lua serializados: nenhum outro comando pode intercalar entre o `ZREM` e o `ZADD`. **Atomicidade garantida sem locks de aplicação.**

O método Java `requeueResidual()` existe no `RedisMatchEngineAdapter` mas **não é chamado no fluxo normal** — é uma API pública exclusivamente para disaster recovery e replay de eventos fora da Saga.

---

## 4. Hash Tags Redis e Compatibilidade com Redis Cluster (AT-11.1)

As keys do Match Engine usam a hash tag `{vibranium}` para garantir compatibilidade com **Redis Cluster**.

### O problema (antes de AT-11.1)

Redis Cluster distribui keys por **hash slots** (0–16383) usando CRC16. Scripts Lua multi-key (`EVAL`/`EVALSHA`) só executam se **todas as KEYS[] estiverem no mesmo slot**. Sem hash tags:

```
CRC16("vibranium:asks") % 16384 → slot α
CRC16("vibranium:bids") % 16384 → slot β   (α ≠ β)
→ CROSSSLOT Keys in request don't hash to the same slot
```

### A solução — Hash Tags `{vibranium}`

Quando a key contém `{tag}`, o Redis usa **somente o conteúdo entre as chaves** no cálculo CRC16:

```
CRC16("{vibranium}:asks") → CRC16("vibranium") → slot γ
CRC16("{vibranium}:bids") → CRC16("vibranium") → slot γ  ← mesmo slot!
CRC16("{vibranium}:order_index") → slot γ
→ Lua executa sem CROSSSLOT em qualquer nó do cluster
```

### Configuração atual (`application.yaml`)

```yaml
app:
  redis:
    keys:
      asks:        "{vibranium}:asks"
      bids:        "{vibranium}:bids"
      order-index: "{vibranium}:order_index"
```

> **Compatibilidade standalone:** hash tags são ignoradas em modo Redis single-node — zero impacto em desenvolvimento local.

### O 5º elemento do retorno Lua

O script foi estendido para retornar `remainingCounterpartQty` como 5º elemento:

```
Antes (US-001): {"MATCH", counterpartValue, matchedQty, fillType}
Depois (US-002): {"MATCH", counterpartValue, matchedQty, fillType, remainingCounterpartQty}
```

O Java lê o 5º elemento com fallback para `BigDecimal.ZERO` (compatibilidade com versões anteriores do script).

---

## 5. AT-04.2 — Índice Reverso: Remoção O(1) do Livro

### O problema

Cancelar ou expirar uma ordem do livro exige remover seu membro do Sorted Set via `ZREM`.
Porém, `ZREM` precisa do **valor completo do membro** (`orderId|userId|walletId|qty|correlId|epochMs`).
Sem conhecê-lo de antemão, a única alternativa era o `ZSCAN` — operação O(n) que itera o Sorted Set inteiro, ineficiente para livros grandes.

### A solução — Hash de índice reverso

O `match_engine.lua` agora popula atomicamente um hash auxiliar em **cada inserção** no livro:

```lua
redis.call('HSET', KEYS[3], orderId,
           bookKey .. '|' .. score .. '|' .. member)
-- Ex: HSET {vibranium}:order_index <uuid> "{vibranium}:bids|500000000|uuid|..."
```

O script `remove_from_book.lua` executa a remoção em O(1):

```lua
local entry = redis.call('HGET', KEYS[1], orderId)  -- O(1)
-- extrai bookKey e member
redis.call('ZREM', bookKey, member)                  -- O(log N)
redis.call('HDEL', KEYS[1], orderId)                 -- O(1)
```

### Ciclo completo do índice por tipo de fill

| Evento | HSET | HDEL |
|---|---|---|
| `BUY NO_MATCH` | `order_index[bidOrderId] = bids\|score\|member` | — |
| `SELL NO_MATCH` | `order_index[askOrderId] = asks\|score\|member` | — |
| `PARTIAL_ASK` (ASK residual) | Sobrescreve com novo member (qty reduzida) | — |
| `PARTIAL_BID` (BID residual ingressante) | `order_index[bidOrderId] = bids\|score\|newMember` | HDEL do ASK consumido |
| `FULL` (contraparte totalmente consumida) | — | HDEL da contraparte |

### Atomicidade

Todos os `HSET`/`HDEL` e `ZADD`/`ZREM` ocorrem no mesmo `EVAL`, sem possibilidade de leitura inconsistente por concorrentes.

### Fallback ZSCAN

Ordens inseridas diretamente no Sorted Set (fora do Lua pipeline — ex: testes `@BeforeEach` que usam `redisTemplate.opsForZSet().add(...)`) não possuem entrada no índice.
Nesse caso, `remove_from_book.lua` retorna `0` e o `removeFromBook` recorre ao ZSCAN O(n) como fallback, emitindo um `WARN` para sinalizar o caminho lento.

### Impacto em memória

Cada ordem no livro cria 1 campo no hash `order_index`. Para N ordens, o overhead é aproximadamente **N × 100 bytes**. Com 100 mil ordens abertas: ~10 MB — negligível.

---

## 6. Cuidados Críticos (Para não quebrar o sistema)

Para manter a integridade sob pressão de milhares de acessos:

* **Idempotência por `eventId`:** O `FundsReservedEventConsumer` persiste o `eventId` de cada `FundsReservedEvent` na tabela `tb_processed_events` antes de qualquer lógica. Se o broker re-entregar a mesma mensagem, a `DataIntegrityViolationException` (PK duplicada) é capturada e a mensagem é descartada silenciosamente. **Este mecanismo é diferente** da guarda por `order.status != PENDING`: idempotência por status não protege contra re-entrega de mensagens ainda não processadas (janela entre ack e commit).
* **Requeue Atômico no Lua:** A `Wallet` deve ser esperta. Se ela receber o mesmo aviso de "Match" duas vezes por erro de rede, ela deve processar apenas a primeira e ignorar a segunda.
* **Transactional Outbox:** Na `Wallet`, o registro do débito no banco e a mensagem de aviso para o resto do sistema devem ser salvos na **mesma transação**. Se um falhar, o outro não acontece.
* **Virtual Threads (Java 21):** Como teremos milhares de robôs enviando ordens, usaremos as threads virtuais para que o servidor não fique "travado" esperando o Redis ou o RabbitMQ responder.
