# AT-09 — Consumer Group e Prefetch Tuning

## Visão Geral

Configuração de **prefetch**, **concurrency** e **consumer groups** para otimizar o throughput
do sistema de mensageria RabbitMQ sem comprometer idempotência, ordering e backpressure.

## Parâmetros de Tuning

### Fórmula de Paralelismo Total

```
Paralelismo total = instâncias × max-concurrency × prefetch
```

| Serviço         | Instâncias | Concurrency (min-max) | Prefetch | Mensagens em voo (máx) |
|:----------------|:----------:|:---------------------:|:--------:|:----------------------:|
| wallet-service  |     2      |        1 – 5          |    10    |         100            |
| order-service   |     2      |        1 – 5          |    10    |         100            |
| order-service (projeção) | 2 |       1 – 5          |    50    |         500            |

### Configuração por Tipo de Fila

| Tipo de Fila      | Prefetch | Justificativa                                              |
|:------------------|:--------:|:-----------------------------------------------------------|
| Comando financeiro (reserve/release/settle) | **10** | Idempotente, mas requer ordering por wallet — prefetch ≤ 10 |
| Evento de Saga (funds-reserved, funds-failed) | **10** | Idempotente, operação financeira — máximo 10 |
| Projeção MongoDB (read model) | **50** | Leitura idempotente, sem risco de inconsistência — throughput priorizado |
| Settlement | **10** | Operação financeira crítica — ordering importa |

## Configuração Aplicada

### wallet-service — `application.yaml`

```yaml
spring:
    rabbitmq:
        listener:
            simple:
                acknowledge-mode: manual    # ACK explícito após commit JPA
                prefetch: 10                # AT-09: era 1, agora 10
                concurrency: 1              # AT-09: min threads por instância
                max-concurrency: 5          # AT-09: auto-escala até 5 threads
                default-requeue-rejected: false
```

**Antes (AT-08):** `prefetch: 1` — processava 1 mensagem por vez. Gargalo: ~1 msg/RTT.

**Depois (AT-09):** `prefetch: 10` + `concurrency: 1-5` — throughput até 50x maior por instância.

### order-service — `RabbitMQConfig.java` (Container Factories)

```java
// manualAckContainerFactory — filas de comando/evento financeiro
factory.setPrefetchCount(10);
factory.setConcurrentConsumers(1);
factory.setMaxConcurrentConsumers(5);

// autoAckContainerFactory — filas de projeção (read model)
factory.setPrefetchCount(50);
factory.setConcurrentConsumers(1);
factory.setMaxConcurrentConsumers(5);
```

## Consumer Groups (Round-Robin Nativo)

O RabbitMQ distribui mensagens entre todos os consumers conectados à mesma fila usando **round-robin**.
Não é necessária configuração adicional — basta iniciar múltiplas instâncias do serviço.

```
                  ┌────────────────────┐
                  │   RabbitMQ Broker   │
                  │                    │
                  │  wallet.commands.  │
                  │  reserve-funds     │
                  └─────┬──────┬───────┘
                        │      │
              round-robin │    │ round-robin
                        │      │
               ┌────────▼──┐  ┌▼──────────┐
               │ wallet-1  │  │ wallet-2   │
               │ prefetch=10│ │ prefetch=10│
               │ threads=5 │  │ threads=5  │
               └───────────┘  └────────────┘
               Paralelismo: 2 × 5 × 10 = 100 msgs em voo
```

### Garantias de Idempotência em Multi-Consumer

Cada instância registra `messageId` na tabela `tb_idempotency_key` (wallet-service) ou
`tb_processed_events` (order-service) **antes** de processar o comando.
`DataIntegrityViolationException` na PK indica duplicata e resulta em ACK sem reprocessamento.

**Cenários protegidos:**
- Redelivery após NACK com requeue (network partition)
- Redelivery após timeout do consumer (heartbeat falhou)
- Duplicação por falha de ACK no broker (mensagem re-entregue a outra instância)

## Trade-offs

| Decisão                    | Trade-off                                                              |
|:---------------------------|:-----------------------------------------------------------------------|
| `prefetch: 10` (não 50)   | Ordering por wallet pode ser perdida com prefetch alto — 10 é seguro  |
| `max-concurrency: 5`      | Mais de 5 threads por instância saturam o pool HikariCP (max=20)      |
| Projeção com `prefetch: 50`| Read model é idempotente — throughput priorizado sobre ordering        |
| `acknowledge-mode: manual` | Mantido — ACK somente após commit JPA elimina janela de duplicação    |

## Testes de Validação

### WalletPrefetchConcurrencyTest
- 100 `ReserveFundsCommands` de 100 wallets diferentes
- Todas processadas em < 20s (vs ~100s com `prefetch: 1`)
- Zero erros de concorrência

### MultiConsumerIdempotencyTest
- 50 mensagens únicas → 50 eventos no outbox (1:1)
- 5 mensagens duplicadas (mesmo `messageId`) → 1 evento no outbox
- `IdempotencyKey` garante deduplicação perfeita

### PrefetchBackpressureTest
- 1000 mensagens publicadas com backpressure via `prefetch: 10`
- Todas processadas sem OOM (heap < 512MB)
- Fila esvaziada após processamento completo

## Referências

| Recurso | Link |
|:--------|:-----|
| RabbitMQ Consumer Prefetch | https://www.rabbitmq.com/docs/consumer-prefetch |
| Spring AMQP Listener Concurrency | https://docs.spring.io/spring-amqp/reference/amqp/receiving-messages/async-consumer.html#choose-container |
| RabbitMQ Consumer ACK | https://www.rabbitmq.com/docs/confirms#consumer-acknowledgements |
