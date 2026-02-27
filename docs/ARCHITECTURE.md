# Arquitetura da Plataforma Vibranium Order Book

## 🏗️ Overview

A Vibranium Order Book Platform é uma arquitetura **event-driven** de alta escala para matching de ordens e gerenciamento de carteiras digitais.

```
┌─────────────────────────────────────────────────────────────────────┐
│                        API Gateway (Kong)                            │
│                    + JWT Validation (Keycloak)                       │
└────────────────┬──────────────────────────────┬──────────────────────┘
                 │                              │
        ┌────────▼────────┐           ┌────────▼────────┐
        │  Order Service  │           │  Wallet Service │
        │   (MongoDB/RSS) │           │   (PostgreSQL)  │
        │     (CQRS)      │           │  (Idempotência) │
        └────────┬────────┘           └────────┬────────┘
                 │                              │
                 └──────────────┬───────────────┘
                                │
                     ┌──────────▼──────────┐
                     │   RabbitMQ (Topic)  │
                     │    Event Broker     │
                     └────────────────────┘
                                │
                      ┌─────────┴─────────┐
                      │                   │
            ┌─────────▼────┐   ┌─────────▼────┐
            │  Redis (Cache)│   │  Monitoring  │
            │  Order Books  │   │   (Metrics)  │
            │  (Sorted Sets)│   └──────────────┘
            └───────────────┘
```

## 📋 Componentes

### 1. **Order Service (apps/order-service)**

**Responsabilidade:** Gerenciar o catálogo de ordens e realizar matching em tempo real.

**Arquitetura CQRS:**
- **Command Side (Write):** Recebe ordens via REST, valida e atomicamente:
  1. Persiste em MongoDB
  2. Adiciona ao matching engine (Redis Sorted Sets)
  3. Publica evento `OrderCreatedEvent`
  
- **Query Side (Read):** Queries rápidas via MongoDB:
  - Buscar ordem por ID
  - Listar ordens do usuário
  - Visualizar order book (bids/asks)

**Banco de Dados:**
- **MongoDB:** Persistência durável de ordens e histórico
- **Redis Sorted Sets:** Motor de matching em memória (sub-milissegundo)
  - Key: `order_book:{SYMBOL}:{BUY|SELL}`
  - Score: Preço (DESC para BUY, ASC para SELL)

**Fluxo de Uma Ordem BUY:**
```
POST /api/v1/orders
└─ CreateOrderCommand
   ├─ Salvar em MongoDB
   ├─ Adicionar ao Redis: order_book:EUR/USD:BUY
   ├─ Buscar matches em order_book:EUR/USD:SELL
   └─ Publicar OrderCreatedEvent (RabbitMQ)
```

### 2. **Wallet Service (apps/wallet-service)**

**Responsabilidade:** Gerenciar saldos de carteiras com garantias ACID.

**Persistência:**
- **PostgreSQL:** Transações ACID com Optimistic Locking (@Version)
- **Tabela wallet_transactions:** Log idempotente via event_id

**Idempotência:**
Todo evento RabbitMQ contém `event_id` único. A wallet service valida antes de processar:
```java
if (transactionRepository.findByEventId(eventId).isPresent()) {
    return; // Já processou, ignora (não duplica)
}
```

**Isolamento de Transação:**
```java
@Transactional(isolation = Isolation.SERIALIZABLE)
```
Previne race conditions em operações simultâneas.

**Fluxo de Débito (BUY Order):**
```
OrderCreatedEvent (BUY)
└─ Wallet Listener
   ├─ Verifica idempotência (event_id)
   ├─ Lock pessimista: findByUserIdAndCurrencyForUpdate
   ├─ Valida saldo ≥ amount
   ├─ Debita available_balance
   └─ Registra em wallet_transactions
```

### 3. **RabbitMQ (Event Bus)**

Exchange de tipo **topic** com rotas estruturadas:

| Evento | Topic | Rota | Listeners |
|--------|-------|------|-----------|
| OrderCreatedEvent | vibranium.orders | orders.created | wallet-service (debita buyer) |
| OrderMatchedEvent | vibranium.orders | orders.matched | wallet-service (credita seller) |
| WalletDebitedEvent | vibranium.wallet | wallet.debited | order-service (valida saldo) |
| WalletCreditedEvent | vibranium.wallet | wallet.credited | order-service (atualiza status) |

**Garantias:**
- **Exactly-Once Semantics:** Idempotência via event_id
- **Manual ACK:** Acknowledge apenas após persistência bem-sucedida

### 4. **Kong API Gateway**

Camada de entrada que:
- ✅ Roteia requisições para serviços internos
- ✅ Valida JWT do Keycloak
- ✅ Rate limiting
- ✅ Logging centralizado

**Rutas Configuradas:**
- `/api/v1/orders/*` → Order Service
- `/api/v1/wallets/*` → Wallet Service

### 5. **Keycloak (Identity Provider)**

Servidor de autenticação:
- Realm: `vibranium`
- Clients: `api-gateway`, `order-service`, `wallet-service`
- Token: JWT com claims de `user_id` e `roles`

## 🔄 Fluxo End-to-End

### Cenário: Usuário cria ordem de compra EUR 100 @ 1.10

```
1. Usuário obtém JWT do Keycloak
2. POST /api/v1/orders
   Header: Authorization: Bearer {JWT}
   Body: {
     userId: "trader-1",
     symbol: "EUR/USD",
     side: "BUY",
     quantity: 100,
     price: 1.10
   }

3. Kong valida JWT, roteia para Order Service

4. Order Service (Command):
   - Cria Order em MongoDB com status PENDING
   - Adiciona ao Redis (order_book:EUR/USD:BUY, score=-1.10)
   - Emite OrderCreatedEvent (eventId: UUID)
   
5. RabbitMQ publica para wallet-service queue

6. Wallet Service (Listener):
   - Verifica se eventId já foi processado
   - Lookup pessimista: wallet(trader-1, EUR)
   - Valida: available ≥ 110 EUR
   - Debita: available -= 110 EUR
   - Registra WalletTransaction com eventId
   
7. Response: 201 Created + order_id

8. Order Service (Query):
   - GET /api/v1/orders/{order_id}
   - Retorna status atual
   
9. Se houver ordem de venda @ ≤ 1.10:
   OrderMatchingEngine encontra match
   - Emite OrderMatchedEvent
   - Wallet Service credita vendedor
   - Wallet Service debita diferença se necessário
```

## 💾 Banco de Dados

### MongoDB (Order Service)

**Coleção: orders**
```json
{
  "_id": "uuid",
  "userId": "trader-1",
  "symbol": "EUR/USD",
  "side": "BUY",
  "quantity": 100.00000000,
  "price": 1.10000000,
  "status": "PENDING",
  "filledQuantity": 0.00000000,
  "correlationId": "uuid",
  "createdAt": ISODate("2026-02-26T12:00:00Z"),
  "updatedAt": ISODate("2026-02-26T12:00:00Z")
}
```

**Índices:**
- userId, symbol, status (ordem book queries)
- createdAt (histórico)
- symbol + side + status (composto para profundidade do livro)

### PostgreSQL (Wallet Service)

**Tabela: wallets**
```sql
id | user_id | currency | balance | available_balance | reserved_balance | version
```

**Tabela: wallet_transactions**
```sql
id | event_id (UNIQUE) | wallet_id | type | amount | status | created_at
```

Exemplo de transação bem-sucedida:
```
event_id: "e123-456-789"
wallet_id: 42
user_id: "trader-1"
currency: "EUR"
amount: 110.00
type: "DEBIT"
status: "COMPLETED"
balance_before: 1000.00
balance_after: 890.00
```

## 🚀 Performance

### Order Matching

- **Algoritmo:** Sorted Sets do Redis (O(log N) insert, O(1) top lookup)
- **Latência:** < 100ms por order no volume típico
- **Throughput:** 10k+ orders/segundo (com 3 nós Redis)

### Wallet Transactions

- **Isolamento:** SERIALIZABLE via PostgreSQL
- **Idempotência:** Verificação de event_id antes de commit
- **Throughput:** 1k+ débitos/segundo (com connection pool)

### Escalabilidade

| Componente | Dev | Staging | Produção |
|-----------|-----|---------|----------|
| Order Service | 1 replica | 3 replicas | Autoscale (2-10) |
| Wallet Service | 1 replica | 3 replicas | Autoscale (2-10) |
| MongoDB | 1 node | 1 node | 3-node replica set |
| PostgreSQL | 1 primary | 1 primary + 2 replicas | 1 primary + 3+ replicas |
| Redis | 1 node | 3 nodes (cluster) | 6 nodes (cluster + sentinel) |
| RabbitMQ | 1 node | 3 nodes (cluster) | 5+ nodes + federation |

## 🔒 Segurança

1. **Autenticação:** JSON Web Token (JWT) via Keycloak
2. **Autorização:** Kong valida scopes antes de rotear
3. **Validação:**  Toda entrada é validada com Jakarta Validation
4. **Transações:** ACID com isolamento SERIALIZABLE
5. **Idempotência:** Event ID único previne duplicação

## 📊 Monitoramento

**Health Checks:**
- `/actuator/health` por serviço
- Verificação de conectividade com dependências

**Métricas (Micrometer):**
- HTTP requests (latência, throughput)
- Database connections
- Queue message count

**Integração Futuro:**
- Prometheus (coleta)
- Grafana (visualização)
- ELK Stack (logs)
- Jaeger (distributed tracing)

---

**Versão:** 1.0.0 | **Data:** 2026-02-26 | **Manutenção:** Vibranium DevOps Team
