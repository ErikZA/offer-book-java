# Vibranium Performance Tests — Gatling Benchmark Suite

Suíte de benchmark para validação do SLA **RNF01: 5.000 trades/s** usando [Gatling](https://gatling.io/) com Java DSL.

## Pré-requisitos

| Requisito         | Versão mínima |
|-------------------|---------------|
| Java (JDK)        | 21            |
| Maven             | 3.9+          |
| Docker + Compose  | 24+           |

## Cenários

| Cenário    | Carga              | Duração | Objetivo                                           |
|------------|--------------------|---------|----------------------------------------------------|
| Smoke      | 10 req/s           | 30s     | Validar corretude do pipeline                      |
| Load       | 1.000 req/s        | 60s     | Validar estabilidade sob carga moderada            |
| Stress     | 5.000 req/s        | 120s    | Validar SLA e identificar gargalos                 |
| Soak       | 500 req/s          | 30 min  | Detectar memory leaks e degradação temporal        |
| Validation | 1 user sequencial  | ~5 min  | Validar casamento de ordens com carteiras reais    |

## Critérios de Aceite

| Cenário    | Error Rate | Throughput           | Latência p99   | Validação Extra                        |
|------------|-----------|----------------------|----------------|----------------------------------------|
| Smoke      | < 1%      | —                    | < 5.000ms      | —                                      |
| Load       | < 1%      | ≥ 950 req/s          | < 10.000ms     | —                                      |
| Stress     | < 5%      | Documentar máximo    | Documentar     | —                                      |
| Soak       | < 1%      | —                    | < 10.000ms     | —                                      |
| Validation | < 1%      | —                    | —              | Saldos finais = saldos esperados       |

## Execução Rápida (Docker)

### 1. Subir ambiente de performance

```bash
docker compose -f tests/performance/docker-compose.perf.yml up -d
```

Aguardar todos os serviços ficarem healthy (~2-3 minutos):

```bash
docker compose -f tests/performance/docker-compose.perf.yml ps
```

### 2. Executar benchmark

**Todos os cenários:**

```bash
docker compose -f tests/performance/docker-compose.perf.yml --profile run run --rm gatling
```

**Cenário específico:**

```bash
# Smoke
docker compose -f tests/performance/docker-compose.perf.yml --profile run run --rm \
  -e GATLING_SIMULATION=com.vibranium.performance.SmokeSimulation gatling

# Load
docker compose -f tests/performance/docker-compose.perf.yml --profile run run --rm \
  -e GATLING_SIMULATION=com.vibranium.performance.LoadSimulation gatling

# Stress
docker compose -f tests/performance/docker-compose.perf.yml --profile run run --rm \
  -e GATLING_SIMULATION=com.vibranium.performance.StressSimulation gatling

# Soak
docker compose -f tests/performance/docker-compose.perf.yml --profile run run --rm \
  -e GATLING_SIMULATION=com.vibranium.performance.SoakSimulation gatling

# Validation (casamento de ordens com carteiras reais)
docker compose -f tests/performance/docker-compose.perf.yml --profile run run --rm \
  -e GATLING_SIMULATION=com.vibranium.performance.OrderMatchingValidationSimulation gatling
```

### 3. Ver relatório

Os relatórios HTML são gerados em `tests/performance/results/`. Abra o arquivo `index.html` da execução mais recente no navegador.

### 4. Parar ambiente

```bash
docker compose -f tests/performance/docker-compose.perf.yml --profile run down -v
```

## Execução Local (Maven)

Se os serviços já estiverem rodando (via Docker Compose ou ambiente staging):

```bash
# Definir variáveis de ambiente
export TARGET_BASE_URL=http://localhost:8080
export KEYCLOAK_BASE_URL=http://localhost:8180
export KEYCLOAK_REALM=orderbook-realm
export KEYCLOAK_CLIENT_ID=order-client
export KEYCLOAK_USERNAME=tester
export KEYCLOAK_PASSWORD=test-password

# Executar todos os cenários
mvn gatling:test -pl tests/performance

# Executar cenário específico
mvn gatling:test -pl tests/performance -Psmoke
mvn gatling:test -pl tests/performance -Pload
mvn gatling:test -pl tests/performance -Pstress
mvn gatling:test -pl tests/performance -Psoak
mvn gatling:test -pl tests/performance -Pvalidation
```

## Variáveis de Ambiente

| Variável               | Default                   | Descrição                                |
|------------------------|---------------------------|------------------------------------------|
| `TARGET_BASE_URL`      | `http://localhost:8000`   | URL base do serviço (Kong ou direto)     |
| `KEYCLOAK_BASE_URL`    | `http://keycloak:8080`    | URL do Keycloak para obter JWT           |
| `KEYCLOAK_REALM`       | `orderbook-realm`         | Realm do Keycloak                        |
| `KEYCLOAK_CLIENT_ID`   | `order-client`            | Client ID (public client)                |
| `KEYCLOAK_USERNAME`    | `tester`                  | Usuário para autenticação                |
| `KEYCLOAK_PASSWORD`    | `test-password`           | Senha do usuário                         |
| `KEYCLOAK_ADMIN_USER`  | `admin`                   | Admin Keycloak (cenário Validation)      |
| `KEYCLOAK_ADMIN_PASSWORD`| `perftest`              | Senha admin Keycloak (cenário Validation)|
| `WALLET_SERVICE_URL`   | `http://wallet-service:8081`| URL da wallet-service (cenário Validation)||
| `NUM_ROUNDS`           | `5`                       | Rodadas de compra/venda (Validation)     |
| `SETTLE_PAUSE_SECONDS` | `10`                      | Pausa entre buys e sells (Validation)    |
| `FINAL_SETTLE_WAIT_SECONDS`| `60`                  | Espera para liquidação final (Validation)|

## Como o Benchmark Funciona

### Autenticação

O `KeycloakTokenFeeder` obtém um JWT Bearer token do Keycloak via **Resource Owner Password Credentials Grant** (Direct Access Grant). O token é cacheado e renovado automaticamente 30s antes da expiração. Todas as virtual users do Gatling compartilham o mesmo token (thread-safe).

### Payload das Ordens (Smoke, Load, Stress, Soak)

Cada requisição gera uma ordem com:
- **walletId**: UUID aleatório por requisição
- **orderType**: `BUY` ou `SELL` em proporção ~50/50 (para maximizar matches)
- **price**: Valor aleatório entre R$ 90,00 e R$ 110,00 (faixa estreita para gerar matches)
- **amount**: Quantidade aleatória entre 0,1 e 10,0 VIBRANIUM

> **Nota:** Nos cenários Smoke/Load/Stress/Soak, os walletIds são aleatórios e as ordens são descartadas
> no wallet-service por carteira inexistente. Esses cenários medem throughput e latência do **primeiro hop**.
> Para validação funcional end-to-end com carteiras reais, use o cenário **Validation**.

### Endpoint

```
POST /api/v1/orders
Authorization: Bearer <jwt>
Content-Type: application/json

{
  "walletId": "uuid",
  "orderType": "BUY|SELL",
  "price": 100.50,
  "amount": 1.5
}

→ HTTP 202 Accepted
{
  "orderId": "uuid",
  "correlationId": "uuid",
  "status": "PENDING"
}
```

### Cenário Validation — Casamento de Ordens com Carteiras Reais

O cenário `OrderMatchingValidationSimulation` garante que o fluxo completo — desde a criação de ordens até a liquidação de saldos — funcione corretamente. Diferente dos demais cenários (que usam walletIds aleatórios), este:

1. **Cria 4 usuários reais** no Keycloak via Admin REST API (idempotente)
2. **Aguarda criação automática de carteiras** (Keycloak → RabbitMQ → wallet-service)
3. **Deposita saldo inicial** em cada carteira (10M BRL + 10M VIB) via `PATCH /api/v1/wallets/{id}/balance`
4. **Registra saldos iniciais** para validação posterior
5. **Executa N rodadas** de compra e venda:
   - 10 ordens BUY × 1.50 VIB @ R$ 100,00 = 15 VIB de demanda
   - 15 ordens SELL × 1.00 VIB @ R$ 100,00 = 15 VIB de oferta
   - Volumes iguais garantem casamento completo de todas as ordens de compra
   - Ordens distribuídas aleatoriamente entre os 4 usuários
6. **Aguarda liquidação final** de todas as ordens pendentes
7. **Valida saldos**: compara saldo real de cada carteira com o saldo esperado calculado durante o teste

**Cálculo do saldo esperado por usuário:**
- `BRL final = BRL inicial - (totalBuyVib × preço) + (totalSellVib × preço)`
- `VIB final = VIB inicial + totalBuyVib - totalSellVib`

**Critérios de sucesso:**
- Todas as ordens aceitas (HTTP 202)
- Nenhum fundo travado (`locked = 0`) ao final
- Saldos `brlAvailable` e `vibAvailable` correspondem exatamente aos valores esperados

### Pipeline Assíncrono (~6 hops)

```
HTTP POST → Order Service → PostgreSQL (persist PENDING)
  → Outbox Poller → RabbitMQ (FundsReservedCommand)
  → Wallet Service → PostgreSQL (reserve funds)
  → RabbitMQ (FundsReservedEvent)
  → Order Service → Redis (matching engine)
  → RabbitMQ (SettlementEvent)
```

O Gatling mede apenas a latência do primeiro hop (HTTP 202 Accepted). A latência end-to-end total inclui o outbox delay e todos os hops assíncronos.

## Métricas para Observar Durante o Benchmark

Acessar os dashboards do Grafana em `http://localhost:3000` durante a execução:

| Métrica                       | Fonte       | O que observar                                  |
|-------------------------------|-------------|-------------------------------------------------|
| `vibranium_orders_total`      | Prometheus  | Taxa de criação de ordens (deve acompanhar req/s)|
| `hikaricp_connections_active` | Prometheus  | Pool de conexões PG (não deve saturar)          |
| Redis `ops/s`                 | Redis CLI   | Operações no motor de matching                  |
| RabbitMQ queue depth          | RabbitMQ UI | Filas não devem acumular indefinidamente        |
| `vibranium_outbox_depth`      | Prometheus  | Lag do outbox (deve drenar continuamente)       |
| JVM Heap Used                 | Prometheus  | Deve estabilizar (sem crescimento linear)       |

### Monitoramento via CLI durante o benchmark

```bash
# PostgreSQL: conexões ativas
docker exec vibranium-postgres-perf psql -U postgres -c "SELECT count(*) FROM pg_stat_activity WHERE state = 'active';"

# Redis: operações por segundo
docker exec vibranium-redis-perf redis-cli -a perftest INFO stats | grep instantaneous_ops_per_sec

# RabbitMQ: profundidade das filas
docker exec vibranium-rabbitmq-perf rabbitmqctl list_queues name messages
```

## Estrutura de Arquivos

```
tests/performance/
├── pom.xml                          # Dependências Gatling + Maven plugin
├── docker-compose.perf.yml          # Ambiente dedicado para benchmark
├── README.md                        # Este arquivo
├── results/                         # Relatórios HTML (gerados pelo Gatling)
│   └── <simulation-timestamp>/
│       └── index.html
└── src/test/java/com/vibranium/performance/
    ├── BaseSimulationConfig.java     # Config compartilhada (HTTP, payload, auth)
    ├── SmokeSimulation.java                        # 10 req/s, 30s
    ├── LoadSimulation.java                         # 1.000 req/s, 60s
    ├── StressSimulation.java                       # 5.000 req/s, 120s (escalonado)
    ├── SoakSimulation.java                         # 500 req/s, 30min
    ├── OrderMatchingValidationSimulation.java       # Validação funcional com carteiras reais
    └── helpers/
        ├── KeycloakTokenFeeder.java                # Obtém e cache JWT do Keycloak
        ├── TestUser.java                           # Usuário de teste com JWT e walletId
        ├── KeycloakAdminHelper.java                # Criação de usuários via Keycloak Admin API
        ├── WalletApiHelper.java                    # Operações REST na Wallet Service
        └── BalanceTracker.java                     # Rastreamento de saldos esperados
```

## Interpretando os Resultados

O relatório HTML do Gatling (em `results/`) contém:

1. **Global Information**: Requests total, OK/KO, min/max/mean/p50/p75/p95/p99 response time
2. **Response Time Distribution**: Histograma com faixas de latência
3. **Response Time Percentiles over Time**: Evolução da latência ao longo do teste
4. **Requests per Second**: Throughput real sustentado
5. **Responses per Second**: Taxa de respostas (deve acompanhar o throughput)

### Identificando Gargalos

| Sintoma                            | Gargalo provável                |
|------------------------------------|---------------------------------|
| Throughput plateau + latência sobe  | CPU saturada no order-service   |
| Connection timeout errors           | Pool de conexões PG esgotado    |
| Timeout + Redis slow log            | Contenção no matching engine    |
| Queue depth crescendo               | Consumer não acompanha producer |
| Outbox depth crescendo              | Outbox poller sobrecarregado    |

## Troubleshooting

### Token 401 Unauthorized
- Verificar se o Keycloak está acessível na URL configurada
- Verificar se o realm `orderbook-realm` e o usuário `tester` existem
- Verificar se Direct Access Grants estão habilitados no client `order-client`

### Connection Refused
- Verificar se todos os serviços estão healthy: `docker compose ps`
- Aguardar o start_period do order-service (~90s)

### OutOfMemoryError no Gatling
- Aumentar `MAVEN_OPTS` no container gatling: `-Xms512m -Xmx1g`

### Resultados inconsistentes
- Garantir que nenhuma outra carga está sendo executada no mesmo host
- Verificar recursos do Docker Desktop (mínimo 8GB RAM, 4 CPUs para benchmark)
