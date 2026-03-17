# JWT Environment Variable Mapping — Vibranium Platform

> **Data:** 07/03/2026  
> **Referência:** BUG-01 — PERFORMANCE_PROMPTS.md

## Variáveis Obrigatórias

| Variável de Ambiente | Descrição |
|---|---|
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI` | Endpoint JWKS para validação de assinatura RS256 |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` | Issuer esperado no claim `iss` do token JWT |

## Mapeamento por Ambiente

| Ambiente | `JWK_SET_URI` | `ISSUER_URI` | Observações |
|---|---|---|---|
| **dev** (local) | `http://localhost:8080/realms/orderbook-realm/protocol/openid-connect/certs` | `http://localhost:8080/realms/orderbook-realm` | Keycloak rodando localmente ou via docker-compose.dev.yml |
| **perf** (single) | `http://keycloak:8080/realms/orderbook-realm/protocol/openid-connect/certs` | `http://keycloak:8080/realms/orderbook-realm` | Dentro da rede Docker `vibranium-perf-network` |
| **perf-flat** (escala) | `http://keycloak:8080/realms/orderbook-realm/protocol/openid-connect/certs` | `http://keycloak:8080/realms/orderbook-realm` | Dentro da rede Docker `vibranium-perf-flat-network` |
| **staging** | `http://keycloak:8080/realms/orderbook-realm/protocol/openid-connect/certs` | `http://keycloak:8080/realms/orderbook-realm` | Dentro da rede Docker `vibranium-staging-network` |
| **prod** | Configurar via secrets management | Configurar via secrets management | Nunca hardcodar — usar Vault, AWS Secrets Manager, etc. |

## Kong 3.4 DB-less & RS256

No modo **DB-less** do Kong 3.4, a validação de tokens RS256 exige que a chave pública RSA seja fornecida explicitamente na configuração declarativa (`jwt_secrets`).

O script `infra/kong/kong-setup.sh` automatiza esse processo:
1. Busca o **JWKS** do Keycloak.
2. Extrai o certificado `x5c` da chave correta.
3. Formata como chave PEM pública.
4. Gera o payload JSON e aplica via endpoint `/config` do Kong Admin API.

Se estiver editando o `kong-init.yml` manualmente, certifique-se de que o campo `rsa_public_key` está preenchido no consumer correspondente.

## Consistência Issuer ↔ Token

O campo `iss` no token JWT **DEVE** coincidir exatamente com o `issuer-uri` configurado no Spring Security.

- **Keycloak emite token com `iss`** baseado na URL usada para obtê-lo:
  - Se obtido via `http://keycloak:8080` → `iss=http://keycloak:8080/realms/orderbook-realm`
  - Se obtido via `http://localhost:8180` → `iss=http://localhost:8180/realms/orderbook-realm`

- **Dentro da rede Docker**, tanto o Gatling quanto os serviços acessam o Keycloak via `http://keycloak:8080`, garantindo consistência.

- **Problema comum:** acessar Keycloak via `localhost:8180` (mapeamento de porta host) gera tokens com `iss=http://localhost:8180/...` que são rejeitados pelos serviços configurados com `issuer-uri=http://keycloak:8080/...`.

## Script de Validação

```bash
# Validar presença das env vars antes do benchmark:
bash tests/validate-jwt-env.sh tests/performance/docker-compose.perf.yml
bash tests/validate-jwt-env.sh tests/performance/docker-compose.perf.flat.yml
```
