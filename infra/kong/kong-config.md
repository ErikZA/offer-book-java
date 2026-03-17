# Kong API Gateway — Configuração de Referência

## Visão Geral

O Kong 3.4 atua como API Gateway para os microsserviços da plataforma Vibranium.
Dois modos de operação são suportados:

| Ambiente | Modo         | Arquivo de Config       | Descrição                                    |
| :------- | :----------- | :---------------------- | :------------------------------------------- |
| Dev      | DB-less      | `kong-init.yml`         | Configuração declarativa (decK v3.0)         |
| Staging  | DB mode      | `kong-init.yml`         | Provisionamento via Admin API (imperativo)   |

## Services (Upstreams)

| Service          | URL Upstream                  | Timeout (connect/read/write) | Retries |
| :--------------- | :---------------------------- | :--------------------------- | :------ |
| `order-service`  | `http://order-service:8080`   | 5000 / 10000 / 10000 ms     | 0       |
| `wallet-service` | `http://wallet-service:8081`  | 5000 / 10000 / 10000 ms     | 0       |

## Tabela de Rotas

| Route Name                   | Service          | Método(s)       | Path                              | Rate Limit        | Plugins                     |
| :--------------------------- | :--------------- | :-------------- | :-------------------------------- | :---------------- | :-------------------------- |
| `place-order-route`          | order-service    | POST, OPTIONS   | `/api/v1/orders`                  | 100 req/s, 5000/m | jwt, rate-limiting, cors    |
| `list-orders-route`          | order-service    | GET, OPTIONS    | `/api/v1/orders`                  | 200 req/s, 10000/m| jwt, rate-limiting, cors    |
| `get-order-by-id-route`      | order-service    | GET, OPTIONS    | `~/api/v1/orders/[^/]+$`          | 200 req/s, 10000/m| jwt, rate-limiting, cors    |
| `get-wallet-route`           | wallet-service   | GET, OPTIONS    | `/api/v1/wallets`                 | 200 req/s, 10000/m| jwt, rate-limiting, cors    |
| `update-wallet-balance-route`| wallet-service   | PATCH, OPTIONS  | `~/api/v1/wallets/[^/]+/balance`  | 50 req/s, 2000/m  | jwt, rate-limiting, cors    |

> **Observação**: Todas as rotas usam `strip_path: false` (upstream recebe o path completo).

## Plugins — Configuração Padrão

### JWT (todas as rotas)

| Parâmetro            | Valor                    |
| :------------------- | :----------------------- |
| `header_names`       | `Authorization`          |
| `key_claim_name`     | `iss`                    |
| `claims_to_verify`   | `exp`                    |
| `maximum_expiration` | `3600` (1 hora)          |
| `algorithm`          | RS256 (via credencial)   |
| `run_on_preflight`   | `false`                  |
| `secret_is_base64`   | `false`                  |

### Rate-Limiting

| Contexto                         | second | minute | policy | limit_by |
| :------------------------------- | :----- | :----- | :----- | :------- |
| POST (escrita — orders)          | 100    | 5000   | redis  | ip       |
| GET  (leitura — orders)          | 200    | 10000  | redis  | ip       |
| GET  (leitura — wallets)         | 200    | 10000  | redis  | ip       |
| PATCH (escrita — wallet balance) | 50     | 2000   | redis  | ip       |

- `redis_host`: `redis-kong` | `redis_port`: `6379` | `redis_database`: `1`
- `hide_client_headers: false` — expõe headers `X-RateLimit-*` ao cliente
- `fault_tolerant: true` — Kong não bloqueia tráfego se Redis cair

### CORS (todas as rotas)

| Parâmetro             | Valor                                                                  |
| :-------------------- | :--------------------------------------------------------------------- |
| `origins`             | `*` (restringir em produção)                                           |
| `headers`             | Accept, Authorization, Content-Type, X-Requested-With, X-Correlation-ID|
| `exposed_headers`     | X-Correlation-ID                                                       |
| `credentials`         | `false`                                                                |
| `max_age`             | `3600`                                                                 |
| `preflight_continue`  | `false`                                                                |

## Consumer JWT (Keycloak)

| Campo      | Valor                                                        |
| :--------- | :----------------------------------------------------------- |
| `username` | `keycloak-realm-consumer`                                    |
| `key`      | `http://localhost:8080/realms/orderbook-realm` (campo `iss`) |
| `algorithm`| `RS256`                                                      |
| `rsa_public_key` | Chave pública obtida dinamicamente do JWKS endpoint     |

## Mapeamento Controller → Rota Kong

| Controller                | Método Java       | HTTP     | Path                                | Rota Kong                    |
| :------------------------ | :---------------- | :------- | :---------------------------------- | :--------------------------- |
| `OrderCommandController`  | `placeOrder()`    | `POST`   | `/api/v1/orders`                    | `place-order-route`          |
| `OrderQueryController`    | `listOrders()`    | `GET`    | `/api/v1/orders`                    | `list-orders-route`          |
| `OrderQueryController`    | `getOrder()`      | `GET`    | `/api/v1/orders/{orderId}`          | `get-order-by-id-route`      |
| `WalletController`        | `getByUserId()`   | `GET`    | `/api/v1/wallets/{userId}`          | `get-wallet-route`           |
| `WalletController`        | `listAll()`       | `GET`    | `/api/v1/wallets`                   | `get-wallet-route`           |
| `WalletController`        | `updateBalance()` | `PATCH`  | `/api/v1/wallets/{walletId}/balance`| `update-wallet-balance-route`|

## Arquivos de Configuração

| Arquivo                  | Ambiente | Descrição                                          |
| :----------------------- | :------- | :------------------------------------------------- |
| `kong-init.yml`          | Dev      | Configuração declarativa DB-less (decK v3.0)       |
| `kong-config.md`         | —        | Esta documentação de referência                    |

## Validação

Script de validação: `tests/AT-kong-routes-validation.sh`

```bash
# Verifica todas as rotas e plugins via Kong Admin API
bash tests/AT-kong-routes-validation.sh
```
