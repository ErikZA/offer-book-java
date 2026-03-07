# Gestão de Secrets — Vibranium Platform

## Visão Geral

A plataforma Vibranium utiliza **Docker Secrets** para gerenciar credenciais em ambientes de staging e produção. Variáveis de ambiente (`.env`) continuam suportadas para desenvolvimento local (backward compatible).

### Por que Docker Secrets?

| Aspecto                  | Variáveis de Ambiente (`ENV`)         | Docker Secrets (`/run/secrets/`)      |
|--------------------------|---------------------------------------|---------------------------------------|
| Visibilidade             | `docker inspect`, `ps aux`, logs      | Montados como arquivo tmpfs           |
| Persistência             | Camada da imagem (se em Dockerfile)   | Apenas em memória (tmpfs)             |
| Acesso                   | Todos os processos do container       | Somente processos com acesso ao path  |
| Rotação                  | Requer restart do container           | Atualizar arquivo + restart           |

## Arquitetura

```
┌──────────────────────────────────────────────────┐
│  Host                                            │
│  infra/secrets/*.txt  (chmod 0400)              │
│        │                                         │
│        ▼                                         │
│  docker-compose.yml / staging.yml                │
│    secrets:                                      │
│      postgres_password:                          │
│        file: ./secrets/postgres_password.txt     │
│        │                                         │
│        ▼                                         │
│  Container                                       │
│    /run/secrets/postgres_password  (tmpfs, 0400) │
│        │                                         │
│        ▼                                         │
│  Spring Boot (DockerSecretEnvironmentPostProcessor)│
│    spring.datasource.password = <valor do file>  │
└──────────────────────────────────────────────────┘
```

## Secrets Disponíveis

| Secret File                          | Serviços Consumidores                    | Propriedade Spring                |
|--------------------------------------|------------------------------------------|-----------------------------------|
| `postgres_password.txt`              | PostgreSQL, Kong, Keycloak, wallet-svc   | `spring.datasource.password`      |
| `postgres_replication_password.txt`  | PostgreSQL Primary/Replicas              | N/A (nível infra)                 |
| `rabbitmq_password.txt`             | RabbitMQ, Keycloak, order-svc, wallet-svc| `spring.rabbitmq.password`        |
| `rabbitmq_erlang_cookie.txt`        | RabbitMQ cluster (staging)               | N/A (nível infra)                 |
| `mongo_root_password.txt`           | MongoDB, order-svc                       | Injetado na URI                   |
| `mongo_replica_key.txt`             | MongoDB replica set (staging)            | N/A (nível infra)                 |
| `redis_password.txt`                | Redis, order-svc                         | `spring.data.redis.password`      |
| `redis_kong_password.txt`           | Redis-Kong, Kong                         | N/A (nível infra)                 |
| `keycloak_admin_password.txt`       | Keycloak                                 | N/A (nível infra)                 |
| `keycloak_db_password.txt`          | Keycloak-DB, Keycloak (staging)          | N/A (nível infra)                 |
| `kong_db_password.txt`              | Kong-DB, Kong (staging)                  | N/A (nível infra)                 |

## Setup Inicial

### 1. Criar os arquivos de secret

```bash
cd infra/secrets/

# Gerar senhas aleatórias (recomendado)
openssl rand -hex 32 > postgres_password.txt
openssl rand -hex 32 > postgres_replication_password.txt
openssl rand -hex 32 > rabbitmq_password.txt
openssl rand -hex 32 > rabbitmq_erlang_cookie.txt
openssl rand -hex 32 > mongo_root_password.txt
openssl rand -base64 756 | tr -d '\n' > mongo_replica_key.txt
openssl rand -hex 32 > redis_password.txt
openssl rand -hex 32 > redis_kong_password.txt
openssl rand -hex 32 > keycloak_admin_password.txt
openssl rand -hex 32 > keycloak_db_password.txt
openssl rand -hex 32 > kong_db_password.txt
```

### 2. Ajustar permissões (Linux/macOS)

```bash
chmod 0400 infra/secrets/*.txt
```

### 3. Verificar que estão no .gitignore

```bash
git status infra/secrets/
# Apenas *.txt.example e README.md devem aparecer
```

### 4. Subir o ambiente

```bash
# Staging (com Docker Secrets)
docker compose -f infra/docker-compose.staging.yml up -d

# Produção (com Docker Secrets)
docker compose -f infra/docker-compose.yml up -d

# Desenvolvimento local (continua usando .env — sem mudanças)
docker compose -f infra/docker-compose.dev.yml --env-file .env up -d
```

## Fallback para Desenvolvimento Local

O `docker-compose.dev.yml` **NÃO** usa Docker Secrets. As credenciais continuam sendo passadas via variáveis de ambiente (`.env`), mantendo backward compatibility total.

O `DockerSecretEnvironmentPostProcessor` no Spring Boot detecta automaticamente:
- Se `/run/secrets/` existir → lê credenciais dos arquivos (staging/prod)
- Se `/run/secrets/` não existir → usa variáveis de ambiente (dev)

Isso significa que **nenhuma mudança** é necessária no fluxo de desenvolvimento local.

## Verificação de Segurança

### Confirmar que `docker inspect` não mostra senhas

```bash
# Deve mostrar apenas o NOME do secret, nunca o valor
docker inspect vibranium-postgres-primary --format '{{json .Config.Env}}' | python3 -m json.tool

# Verificar que /run/secrets/ existe no container
docker exec vibranium-postgres-primary ls -la /run/secrets/

# Verificar que o secret file contém o valor (APENAS para debug)
# ⚠️  NUNCA execute este comando em produção ou com logging ativo
docker exec vibranium-postgres-primary cat /run/secrets/postgres_password
```

### Confirmar que o serviço Spring Boot lê os secrets

```bash
# Verificar nos logs do Spring Boot (NÃO loga o valor, apenas o nome)
docker logs vibranium-order-service-1 2>&1 | grep "Docker secret"
# Esperado: "Docker secret 'redis_password' → property 'spring.data.redis.password'"
```

## Rotação de Secrets

### Procedimento de Rotação

A rotação de secrets segue o padrão **Blue-Green** para minimizar downtime:

#### 1. Gerar nova credencial

```bash
# Exemplo: rotacionar senha do PostgreSQL
openssl rand -hex 32 > infra/secrets/postgres_password_new.txt
chmod 0400 infra/secrets/postgres_password_new.txt
```

#### 2. Atualizar a credencial no serviço de destino

```bash
# Para PostgreSQL: alterar senha do usuário antes de atualizar o secret
docker exec vibranium-postgres-primary psql -U postgres -c \
    "ALTER USER postgres PASSWORD '$(cat infra/secrets/postgres_password_new.txt)';"
```

#### 3. Substituir o arquivo de secret

```bash
mv infra/secrets/postgres_password_new.txt infra/secrets/postgres_password.txt
chmod 0400 infra/secrets/postgres_password.txt
```

#### 4. Restart dos containers que usam o secret

```bash
# Restart rolling para minimizar downtime
docker compose -f infra/docker-compose.staging.yml restart wallet-service-1
docker compose -f infra/docker-compose.staging.yml restart wallet-service-2
docker compose -f infra/docker-compose.staging.yml restart wallet-service-3
```

#### 5. Verificar conectividade

```bash
# Verificar health de cada serviço após restart
docker compose -f infra/docker-compose.staging.yml ps
curl -f http://localhost:8083/actuator/health
curl -f http://localhost:8084/actuator/health
curl -f http://localhost:8085/actuator/health
```

### Rotação por Serviço

| Secret                    | Passo Adicional Antes do Restart                                   |
|---------------------------|--------------------------------------------------------------------|
| `postgres_password`       | `ALTER USER postgres PASSWORD '<nova>';` no primary                |
| `rabbitmq_password`       | `rabbitmqctl change_password <user> <nova>` no nó 1               |
| `redis_password`          | `redis-cli CONFIG SET requirepass <nova>` em cada nó               |
| `mongo_root_password`     | `db.changeUserPassword("admin", "<nova>")` no primary              |
| `keycloak_admin_password` | Restart do Keycloak (senha lida no boot)                           |
| `keycloak_db_password`    | `ALTER USER keycloak PASSWORD '<nova>';` + restart Keycloak        |
| `kong_db_password`        | `ALTER USER kong PASSWORD '<nova>';` + restart Kong                |

### Frequência Recomendada

| Ambiente   | Frequência        | Automação                              |
|------------|-------------------|----------------------------------------|
| Staging    | A cada sprint     | Script manual (`scripts/rotate-secrets.sh`) |
| Produção   | A cada 90 dias    | CI/CD pipeline com Vault/AWS Secrets Manager |

## Integração Spring Boot

### DockerSecretEnvironmentPostProcessor

O `DockerSecretEnvironmentPostProcessor` (em `libs/common-utils`) é registrado automaticamente via `META-INF/spring.factories` e executa **antes** do contexto Spring ser criado.

**Mapeamento automático:**

| Arquivo em `/run/secrets/` | Propriedade Spring Boot        |
|----------------------------|--------------------------------|
| `postgres_password`        | `spring.datasource.password`   |
| `rabbitmq_password`        | `spring.rabbitmq.password`     |
| `redis_password`           | `spring.data.redis.password`   |
| `keycloak_db_password`     | `spring.datasource.password`   |

**Prioridade:** Docker Secret > variável de ambiente > `application.yaml`

### SecretFileReader (uso programático)

Para leitura direta em código Java (ex: construção de MongoDB URI):

```java
import com.vibranium.utils.secret.SecretFileReader;

// Prioridade: arquivo > env var > null
String mongoPass = SecretFileReader.readSecretWithFallback(
    "mongo_root_password",
    "MONGO_ROOT_PASSWORD"
);
```

## Troubleshooting

### Secret não encontrado no container

```bash
# Verificar se o secret está declarado no compose
grep -A2 "secrets:" infra/docker-compose.staging.yml

# Verificar se o arquivo existe no host
ls -la infra/secrets/postgres_password.txt

# Verificar se está montado no container
docker exec <container> ls -la /run/secrets/
```

### Spring Boot não lê o secret

1. Verificar que `common-utils` está no classpath (dependência no `pom.xml`)
2. Verificar logs: `Docker secrets directory not found` indica fallback para env vars
3. Verificar que o nome do arquivo corresponde ao mapeamento em `DockerSecretEnvironmentPostProcessor`

### Permissão negada

```bash
# No host
chmod 0400 infra/secrets/*.txt

# Docker monta os secrets como tmpfs com uid:gid do container
# Se o processo roda como non-root, verificar permissões no compose:
# secrets:
#   - source: postgres_password
#     target: /run/secrets/postgres_password
#     uid: '999'    # uid do processo postgres
#     gid: '999'
#     mode: 0400
```
