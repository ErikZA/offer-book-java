# Docker Images for Order Service

Este diretório contém os Dockerfiles para o Order Service em diferentes ambientes.

## Arquivos

- **Dockerfile** - Imagem de produção (multi-stage build)
  - Build otimizado em container
  - Runtime em JRE alpine (`eclipse-temurin:21-jre-alpine`)
  - JVM tuning para containers via `$JAVA_OPTS` (G1GC, MaxRAMPercentage=75, GC pause target)
  - ENTRYPOINT em shell form (`sh -c`) para expansão de variáveis de ambiente
  - Processo roda como usuário não-root `appuser` (AT-1.5.1)
  - Healthcheck configurado

- **Dockerfile.dev** - Imagem de desenvolvimento com hotreload
  - Build e hotreload em container
  - Spring Boot DevTools habilitado
  - Debug remoto via JDWP (porta 5005)
  - Automatic restart ao editar código

- **Dockerfile.e2e** - Imagem para testes E2E (AT-5.3.1)
  - Baseada no Dockerfile de produção com classes E2E injetadas
  - Explode o Spring Boot fat JAR e copia `E2eSecurityConfig` e `E2eDataSeederController`
    de `target/test-classes/` para `BOOT-INF/classes/`
  - Executa via `JarLauncher` no modo explodido
  - Usado pelo `docker-compose.e2e.yml` com `SPRING_PROFILES_ACTIVE=e2e`
  - Garante que classes E2E não existam no JAR de produção

> **Nota:** O script de inicialização do banco `vibranium_orders` foi consolidado em
> `infra/postgres/init-app-databases.sh`, que é executado centralmente pelo
> PostgreSQL de aplicação no primeiro boot. Este serviço usa Flyway para migrations.

## Uso

### Desenvolvimento (com hotreload)

```bash
docker compose -f infra/docker-compose.dev.yml up order-service

# Via scripts
make docker-dev-up     (Linux/Mac)
.\scripts\build.ps1 docker-dev-up  (Windows)
```

### Produção

```bash
docker build -f apps/order-service/docker/Dockerfile -t vibranium-order-service:latest .
docker-compose up order-service  # Usa imagem pré-built
```

## Volumes Montados (Dev)

- `./apps/order-service` → `/app/apps/order-service`
- `./libs` → `/app/libs`
- `./pom.xml` → `/app/pom.xml`
- `m2_cache` → `/root/.m2` (Maven cache persistente)

## Variáveis de Ambiente (Dev)

- `SPRING_PROFILES_ACTIVE=dev`
- `JAVA_OPTS=-agentlib:jdwp=...` (debug remoto)

## Segurança de Runtime (Produção)

A imagem de produção executa o processo Java como usuário não-root:

```dockerfile
# Criado na imagem final (alpine adduser)
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
COPY --from=builder ... app.jar   # COPY como root (permissão de escrita)
USER appuser                       # troca antes do ENTRYPOINT
```

O ENTRYPOINT usa **shell form** para que `$JAVA_OPTS` seja expandido pelo shell:

```dockerfile
# ✅ Shell form — expande $JAVA_OPTS
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

# ❌ Exec form — NÃO expande variáveis de ambiente
# ENTRYPOINT ["java", "-jar", "app.jar"]
```

> **Validação:** `docker run --rm --entrypoint sh <img> -c 'whoami'` → `appuser`  
> **Validação:** `docker run --rm --entrypoint sh <img> -c 'java $JAVA_OPTS -XX:+PrintFlagsFinal 2>&1 | grep MaxRAMPercentage'` → `75.000000 {command line}`

## Mais Informações

- Ver [infra/docker-compose.dev.yml](../../../infra/docker-compose.dev.yml)
- Ver [docs/testing/COMPREHENSIVE_TESTING.md](../../../docs/testing/COMPREHENSIVE_TESTING.md)


