# AT-15 — Redis Cluster para HA do Order Book

## Visão Geral

O Order Book do Vibranium usa Redis como motor de match em tempo real. Em modo standalone (single-node), o Redis é um **single point of failure**: se o nó cair, todas as ordens abertas no livro volátil são perdidas.

O Redis Cluster com 6 nós (3 masters + 3 replicas) resolve este problema:
- **Failover automático** em < 10 segundos (cluster-node-timeout: 5000ms)
- **Dados preservados** via replicação assíncrona master → replica
- **Zero downtime** para o Order Book durante failover

## Arquitetura

```
┌───────────────────────────────────────────────────────────┐
│                   Redis Cluster (6 nodes)                  │
│                                                             │
│  ┌─────────┐    ┌─────────┐    ┌─────────┐                │
│  │ Master 1 │    │ Master 2 │    │ Master 3 │               │
│  │ slots    │    │ slots    │    │ slots    │               │
│  │ 0-5460   │    │ 5461-10922│   │10923-16383│              │
│  └────┬─────┘    └────┬─────┘    └────┬─────┘              │
│       │               │               │                     │
│  ┌────▼─────┐    ┌────▼─────┐    ┌────▼─────┐              │
│  │ Replica 4 │    │ Replica 5 │   │ Replica 6 │             │
│  └───────────┘    └───────────┘   └───────────┘             │
│                                                             │
│  Hash Tag: {vibranium} → CRC16("vibranium") % 16384        │
│  → Todas as keys (asks, bids, order_index) no MESMO slot   │
│  → Script Lua atômico sem CROSSSLOT                         │
└───────────────────────────────────────────────────────────┘
```

### Por que Hash Tags?

O script Lua `match_engine.lua` opera sobre 3 keys simultaneamente:
- `{vibranium}:asks` (Sorted Set)
- `{vibranium}:bids` (Sorted Set)
- `{vibranium}:order_index` (Hash)

O Redis Cluster exige que todas as keys de um `EVAL` estejam no mesmo slot. A hash tag `{vibranium}` garante que o CRC16 é calculado apenas sobre `vibranium`, colocando todas as keys no mesmo slot automaticamente.

**Implicação**: com hash tags, todas as keys do Order Book ficam no **mesmo nó** do cluster. Os outros 2 masters ficam ociosos para estas keys. Isso é intencional — a atomicidade do Lua é mais importante que a distribuição.

## Arquivos Criados

| Arquivo | Descrição |
|---------|-----------|
| `infra/docker-compose.redis-cluster.yml` | Docker Compose com 6 nós + init container |
| `infra/redis/redis-cluster.conf` | Configuração base compartilhada por todos os nós |
| `apps/order-service/src/main/resources/application-cluster.yaml` | Profile Spring para modo cluster |
| `RedisClusterConnectivityIT.java` | Teste: cluster healthy, 6 nós, Lua funciona |
| `RedisClusterFailoverIT.java` | Teste: failover, dados preservados, Lua pós-failover |
| `RedisClusterHashTagValidationIT.java` | Teste: mesmp slot, mesmo nó, remove_from_book.lua |

## Setup

### 1. Iniciar o Cluster

```bash
cd infra
export REDIS_PASSWORD=sua_senha_segura

# Subir 6 nós + init container
docker compose -f docker-compose.redis-cluster.yml up -d

# Aguardar cluster init
docker compose -f docker-compose.redis-cluster.yml logs redis-cluster-init -f
```

### 2. Verificar Status

```bash
# Cluster info
docker exec vibranium-redis-node-1 redis-cli -a $REDIS_PASSWORD cluster info

# Nodes e slots
docker exec vibranium-redis-node-1 redis-cli -a $REDIS_PASSWORD cluster nodes

# Verificar slot da hash tag {vibranium}
docker exec vibranium-redis-node-1 redis-cli -a $REDIS_PASSWORD cluster keyslot vibranium
```

### 3. Ativar Profile Cluster na Aplicação

```bash
# Via variável de ambiente
export SPRING_PROFILES_ACTIVE=cluster

# Via application.yaml
spring:
  profiles:
    active: cluster
```

### 4. Executar Testes

```bash
# Todos os testes de cluster
cd apps/order-service
mvn test -Dgroups=redis-cluster-ha

# Teste específico
mvn test -Dtest=RedisClusterConnectivityIT
mvn test -Dtest=RedisClusterFailoverIT
mvn test -Dtest=RedisClusterHashTagValidationIT
```

## Processo de Failover

### Automático (Produção)

1. Master falha (crash, rede, OOM)
2. Replicas detectam ausência de heartbeat (cluster-node-timeout: 5000ms)
3. Replicas com dados mais recentes inicia eleição (epoch increment)
4. Maioria dos masters vota na replica → **promoção em < 10s**
5. Lettuce detecta MOVED redirect → atualiza topologia (adaptive refresh)
6. Aplicação continua operando sem reinício

### Configurações Críticas

| Parâmetro | Valor | Justificativa |
|-----------|-------|---------------|
| `cluster-node-timeout` | 5000ms | Failover em < 10s |
| `cluster-require-full-coverage` | no | Cluster opera com master down |
| `appendonly` | yes | Durabilidade do Order Book |
| `Lettuce adaptive refresh` | true | Detecção imediata de topology change |
| `Lettuce periodic refresh` | 10s | Backup para adaptive |
| `max-redirects` | 3 | Redireciona MOVED/ASK |

## Recovery Manual

### Cenário 1: Master e Replica Falham (Perda de Slot)

```bash
# 1. Verificar estado do cluster
docker exec vibranium-redis-node-1 redis-cli -a $REDIS_PASSWORD cluster info
# cluster_state:ok ou cluster_state:fail

# 2. Se cluster_state:fail, verificar slots sem cobertura
docker exec vibranium-redis-node-1 redis-cli -a $REDIS_PASSWORD cluster nodes
# Procurar nós com flag "fail"

# 3. Reiniciar o nó falhado
docker compose -f docker-compose.redis-cluster.yml restart redis-node-X

# 4. Se o nó reiniciado não rejoin automaticamente:
docker exec vibranium-redis-node-X redis-cli -a $REDIS_PASSWORD cluster meet <ip-do-node-1> 6379

# 5. Verificar recovery
docker exec vibranium-redis-node-1 redis-cli -a $REDIS_PASSWORD cluster info
```

### Cenário 2: Cluster Completo Down (Disaster Recovery)

```bash
# 1. Subir todos os nós
docker compose -f docker-compose.redis-cluster.yml up -d

# 2. Se dados persistidos (AOF/RDB), os nós reconectam automaticamente
# via /data/nodes.conf (cluster config file)

# 3. Se perda total de dados:
#    a. Parar e limpar volumes
docker compose -f docker-compose.redis-cluster.yml down -v

#    b. Recriar cluster do zero
docker compose -f docker-compose.redis-cluster.yml up -d

#    c. Re-hidratar Order Book via replay de eventos
#       (ordens PENDING no PostgreSQL → re-publish via Outbox)
```

### Cenário 3: Verificar Integridade do Order Book

```bash
# Contar ordens no livro
docker exec vibranium-redis-node-1 redis-cli -a $REDIS_PASSWORD \
  zcard {vibranium}:asks

docker exec vibranium-redis-node-1 redis-cli -a $REDIS_PASSWORD \
  zcard {vibranium}:bids

# Verificar índice reverso
docker exec vibranium-redis-node-1 redis-cli -a $REDIS_PASSWORD \
  hlen {vibranium}:order_index

# IMPORTANTE: NÃO usar KEYS * em cluster — usar SCAN
docker exec vibranium-redis-node-1 redis-cli -a $REDIS_PASSWORD \
  --cluster call DBSIZE
```

## Ambientes

| Ambiente | Nós | Replicas | Config |
|----------|-----|----------|--------|
| **Dev** | 1 (standalone) | 0 | `docker-compose.dev.yml` |
| **Staging** | 3 masters | 0 | `docker-compose.staging.yml` |
| **Produção** | 3 masters + 3 replicas | 1 por master | `docker-compose.redis-cluster.yml` |

## Monitoramento

### Métricas Importantes

- `redis_connected_clients` — conexões ativas por nó
- `redis_cluster_state` — health do cluster (ok/fail)
- `redis_cluster_slots_ok` — slots cobertos (deve ser 16384)
- `vibranium.redis.match.latency` — latência do EVALSHA (Micrometer)
- `resilience4j.circuitbreaker.state` — estado do Circuit Breaker

### Alertas Recomendados

| Alerta | Condição | Severidade |
|--------|----------|-----------|
| Cluster degraded | `cluster_slots_ok < 16384` | WARNING |
| Cluster down | `cluster_state = fail` | CRITICAL |
| Failover occurred | `cluster_current_epoch` changed | INFO |
| Match latency high | `redis.match.latency.p99 > 50ms` | WARNING |

## Decisões de Design

### ADR: Por que 6 nós e não 3?

Com 3 nós (masters only), a falha de 1 nó causa perda do slot e indisponibilidade. Com 6 nós (3M+3R), cada master tem uma replica que assume automaticamente — o cluster continua operando com 2/3 masters + 1 replica promovida.

### ADR: Por que `cluster-require-full-coverage no`?

O default `yes` faz o cluster inteiro recusar writes quando qualquer slot está sem cobertura. Com `no`, slots saudáveis continuam operando. Como todas as keys do Order Book estão no mesmo slot (hash tag), só precisamos que **esse** slot específico esteja disponível.

### ADR: Por que Lettuce e não Jedis?

Lettuce (default do Spring Data Redis) tem suporte nativo a Redis Cluster com:
- Adaptive topology refresh (detecta failover via MOVED/ASK)
- Periodic topology refresh (backup)
- Non-blocking I/O (Netty) — melhor throughput com Virtual Threads
- Connection pooling integrado com Spring Boot auto-configuration
