# ADR-001 — Debezium Embedded: Suportado Apenas em Deployment Single-Instance

| Campo       | Valor                                 |
|-------------|---------------------------------------|
| **ID**      | ADR-001                               |
| **Status**  | **SUPERSEDED** — Substituído por Polling SKIP LOCKED (março/2026) |
| **Data**    | 2026-03-02                            |
| **Autores** | Equipe de Plataforma / Wallet Service |
| **Ticket**  | AT-08.2                               |

---

## Contexto

O `wallet-service` utiliza **Debezium Embedded Engine** para implementar o padrão
Transactional Outbox: o engine captura INSERTs na tabela `outbox_message` via
**WAL logical replication** do PostgreSQL e encaminha os eventos ao RabbitMQ.

### Como o Debezium Embedded funciona

O `DebeziumOutboxEngine` inicia, durante o bootstrap do Spring, um conector
PostgreSQL que:

1. Cria (ou reutiliza) um **replication slot** no PostgreSQL com nome fixo.
2. Abre uma conexão de replicação lógica permanente (protocolo `pgoutput`).
3. Consome o WAL sequencialmente e emite `ChangeEvent` para cada INSERT na tabela
   monitorada.
4. Persiste o LSN (Log Sequence Number) consumido no `JdbcOffsetBackingStore`
   (tabela `wallet_outbox_offset`) após cada batch processado com sucesso.

### Configuração atual do replication slot

```yaml
# application.yaml (wallet-service)
app:
  outbox:
    debezium:
      slot-name: ${APP_OUTBOX_SLOT_NAME:wallet_outbox_slot}   # nome FIXO por padrão
      drop-slot-on-stop: false
```

- **Nome padrão:** `wallet_outbox_slot` (estático, definido em `OutboxProperties`).
- **Parametrizável:** via variável de ambiente `APP_OUTBOX_SLOT_NAME`.
- **Sem sufixo automático por instância:** nenhum mecanismo gera um nome único por
  pod/hostname automaticamente.

### Ausência de coordenação distribuída

O Debezium Embedded **não possui mecanismo de líder eleito** (*leader election*) nem
bloqueio distribuído de inicialização. O único gate de ativação é:

```java
@ConditionalOnProperty(name = "app.outbox.debezium.enabled", havingValue = "true")
public class DebeziumOutboxEngine implements SmartLifecycle { ... }
```

Isso é suficiente para ambientes single-instance, porém **não é suficiente** para
coordenar múltiplas réplicas.

### Cenários problemáticos com múltiplas instâncias

#### Cenário A — Mesmo slot, duas instâncias

Se duas instâncias forem configuradas com o mesmo `slot-name` (comportamento padrão):

```
Instância-1: conecta ao slot wallet_outbox_slot → OK
Instância-2: tenta conectar ao slot wallet_outbox_slot → ERRO:
             "ERROR: replication slot wallet_outbox_slot is already active"
```

O PostgreSQL bloqueia a segunda conexão com `PSQLException` (SQLState `55006`).
A instância-2 entra em loop de retry, degradando logs e conexões.

#### Cenário B — Slots diferentes por instância (fan-out manual)

Se cada instância criar seu próprio slot (e.g., via `APP_OUTBOX_SLOT_NAME`):

```
Instância-1: slot wallet_outbox_slot_pod1 → recebe todos os eventos
Instância-2: slot wallet_outbox_slot_pod2 → recebe todos os eventos (duplicado)
```

Ambas as instâncias receberão **os mesmos eventos do WAL** (semântica fan-out).
O mecanismo de claim atômico (`claimAndPublish`) evita publicação duplicada no
RabbitMQ, porém:

- O PostgreSQL retém o WAL **até que TODOS os slots confirmem** o LSN consumido.
- Uma instância lenta ou travada bloqueia o avanço do WAL para todos os slots.
- Isso causa **WAL bloat**: crescimento descontrolado dos arquivos WAL em disco,
  podendo esgotar o storage e derrubar todo o cluster PostgreSQL.

#### Cenário C — Instância reinicia com slot ativo

Se uma instância trava sem liberar a conexão de replicação, o slot permanece
marcado como ativo. Uma nova instância (restart ou substituta) não consegue
reconectar ao mesmo slot até o timeout da conexão anterior expirar
(`wal_receiver_timeout`, padrão: 60s).

---

## Decisão

> **O `wallet-service` com Debezium Embedded é suportado exclusivamente em
> deployment single-instance (1 pod / 1 container / 1 réplica).**

Esta restrição é válida enquanto o mecanismo de publicação do Outbox for o
`DebeziumOutboxEngine` (Debezium Embedded).

A decisão é fundamentada em:

1. O Debezium Embedded **não implementa coordenação distribuída** — ele foi
   projetado para rodar embutido em uma única JVM.
2. O nome do slot é **fixo por padrão**, criando colisão imediata em múltiplas
   instâncias.
3. O fan-out via slots distintos é tecnicamente possível, mas introduz risco de
   **WAL bloat** que pode comprometer a disponibilidade do banco de dados inteiro —
   inaceitável em um sistema financeiro.
4. O ambiente atual de deployment (Docker Compose, `replicas: 1`) é single-instance.
   Não há manifesto Kubernetes com HPA ou `replicas > 1` para o `wallet-service`.

---

## Consequências

### Positivas

- **Simplicidade operacional:** um único slot, um único offset, comportamento
  determinístico.
- **Sem WAL bloat:** o PostgreSQL avança o WAL imediatamente após o único slot
  confirmar o LSN.
- **Sem colisão de slot:** o nome fixo `wallet_outbox_slot` é suficiente.
- **Latência mínima:** Debezium Embedded reage ao WAL em < 100ms sem polling.

### Negativas / Restrições

- **Sem escala horizontal:** o componente Debezium é o gargalo de escala do
  serviço. Para escalar throughput de publicação, é necessário migrar para as
  alternativas descritas abaixo.
- **Ponto único de falha no publisher:** se a instância cair, nenhum evento é
  publicado até o restart. A recuperação é garantida pelo `JdbcOffsetBackingStore`
  (AT-08.1), que preserva o LSN e retoma sem duplicatas.
- **Não compatível com zero-downtime rolling deploys** sem tratamento explícito
  do slot ativo: durante uma troca de instância (old → new), o slot pode estar
  ativo na instância antiga enquanto a nova tenta conectar.

### Riscos operacionais monitorados

| Risco                        | Mitigação atual                        | Status   |
|------------------------------|----------------------------------------|----------|
| WAL bloat (slot órfão)       | `drop-slot-on-stop: false` + monitorar | Pendente |
| Slot ativo em restart        | Await 60s ou forçar drop via `dropReplicationSlot()` | Parcial |
| Perda de offset              | `JdbcOffsetBackingStore` (AT-08.1)     | Mitigado |
| Duplicata por reprocessamento | `claimAndPublish` + `idempotency_key` | Mitigado |

---

## Alternativas Consideradas

### Alternativa 1 — Debezium Server (externo)

**Descrição:** migrar o CDC para um processo Debezium Server dedicado, que
alimenta um Kafka topic. O `wallet-service` consomeria do Kafka em vez de processar
o WAL diretamente.

```
PostgreSQL WAL → Debezium Server → Kafka Topic → wallet-service (consumer group)
```

**Vantagens:**
- Debezium Server suporta múltiplos workers via Kafka consumer groups.
- Um único slot de replicação → sem WAL bloat.
- Escalabilidade horizontal real do consumer.

**Desvantagens:**
- Introduz Kafka como dependência de infraestrutura obrigatória.
- Aumenta a complexidade operacional (Kafka + ZooKeeper ou KRaft, Debezium Server).
- Fora do escopo atual do projeto (stack atual: RabbitMQ).

**Decisão:** não adotado neste momento. Caminho evolutivo para HA.

---

### Alternativa 2 — Polling Scheduler com Claim Atômico

**Descrição:** desabilitar o Debezium (`app.outbox.debezium.enabled=false`) e usar
o `OutboxPublisherService` em modo polling via `@Scheduled`, com o método
`claimAndMarkProcessed` garantindo atomicidade distribuída via `SELECT FOR UPDATE SKIP LOCKED`.

```sql
-- claim atômico: apenas UMA instância adquire cada mensagem
SELECT * FROM outbox_message
WHERE status = 'PENDING'
ORDER BY created_at
FOR UPDATE SKIP LOCKED
LIMIT :batchSize;
```

**Vantagens:**
- Compatível com múltiplas instâncias do `wallet-service`.
- Sem replication slot → sem WAL bloat.
- Funciona com o stack atual (PostgreSQL + RabbitMQ).
- Sem dependência do Debezium.

**Desvantagens:**
- Latência maior: depende do intervalo do scheduler (trade-off throughput × latência).
- Polling periódico adiciona carga I/O ao banco mesmo quando não há mensagens.

**Decisão:** disponível como fallback. Recomendado para cenários com múltiplas
instâncias enquanto Debezium Server não é adotado.

---

### Alternativa 3 — Debezium Embedded com Leader Election (ShedLock / Zookeeper)

**Descrição:** implementar um lock distribuído (e.g., ShedLock over JDBC) que
garanta que apenas UMA instância inicie o `DebeziumOutboxEngine` por vez.

**Vantagens:**
- Mantém a simplicidade do Debezium Embedded.
- Permite múltiplas réplicas sem WAL bloat.

**Desvantagens:**
- Introduz complexidade de lock distribuído na camada de aplicação.
- A instância que perde o lock fica com o CDC inativo (reduz disponibilidade do
  publisher).
- Não elimina o problema do slot ativo durante failover.

**Decisão:** não adotado. A complexidade não justifica o ganho no contexto atual.

---

## Caminho Evolutivo para Alta Disponibilidade

```
Fase 1 (atual)       → Single-instance + Debezium Embedded
                         Suficiente para MVP / staging.

Fase 2 (curto prazo) → Multi-instance + Polling Scheduler (SKIP LOCKED)
                         Zero alteração de infraestrutura.

Fase 3 (médio prazo) → Debezium Server + Kafka
                         Desacopla CDC do serviço, escala consumer group.
```

---

## Explicação Técnica Detalhada

### Por que replication slots não são horizontalmente escaláveis no Embedded

Um **replication slot** no PostgreSQL é um cursor persistente no WAL. O banco
retém todos os segmentos WAL a partir do LSN mais antigo não confirmado por
qualquer slot ativo. Isso significa:

- **1 slot lento** bloqueia o avanço do WAL para todo o cluster.
- **N slots por instância** criam N cursores independentes; se uma instância
  travar, o WAL cresce indefinidamente (*WAL bloat*) até que o slot seja dropado
  manualmente via `pg_drop_replication_slot()`.
- O Debezium Embedded **não implementa health check do slot** — ele não dropa
  slots órfãos automaticamente.

### Debezium Embedded vs Debezium Server

| Característica              | Debezium Embedded           | Debezium Server             |
|-----------------------------|-----------------------------|-----------------------------|
| Processo                    | Embutido na JVM da app      | Processo separado           |
| Coordenação distribuída     | Nenhuma                     | Via Kafka consumer group    |
| Número de slots necessários | 1 por instância da app      | 1 global                    |
| Escalabilidade              | Single-instance only        | Multi-instance (consumer)   |
| Dependência de infraestrutura | Nenhuma adicional          | Kafka obrigatório           |
| Latência                    | < 100ms (direto do WAL)     | < 100ms (via Kafka)         |
| Complexity operacional      | Baixa                       | Alta                        |

### Riscos de WAL bloat

O WAL bloat ocorre quando um replication slot para de confirmar LSNs. O PostgreSQL
**não pode reciclar** segmentos WAL enquanto houver algum slot com LSN atrasado.

Sintomas:
```
FATAL: terminating walreceiver process due to replication timeout
LOG: replication slot "wallet_outbox_slot" has no consumer
```

Monitoramento recomendado:
```sql
-- Verificar lag de cada slot (bytes retidos)
SELECT slot_name, active, pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn) AS lag_bytes
FROM pg_replication_slots;
```

Ação corretiva:
```sql
-- Dropar slot órfão (IRREVERSÍVEL — Debezium reiniciará do LSN atual)
SELECT pg_drop_replication_slot('wallet_outbox_slot');
```

---

## Referências

- [Debezium Embedded Engine Documentation](https://debezium.io/documentation/reference/stable/development/engine.html)
- [PostgreSQL — Replication Slots](https://www.postgresql.org/docs/current/warm-standby.html#STREAMING-REPLICATION-SLOTS)
- [Debezium Server — Architecture](https://debezium.io/documentation/reference/stable/operations/debezium-server.html)
- `apps/wallet-service/src/main/java/com/vibranium/walletservice/infrastructure/outbox/DebeziumOutboxEngine.java`
- `apps/wallet-service/src/main/resources/application.yaml` — seção `app.outbox.debezium`
- ADR relacionado: AT-08.1 — JdbcOffsetBackingStore para persistência de offset
