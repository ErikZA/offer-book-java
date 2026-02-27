# Docker Images for Wallet Service

Este diretório contém os Dockerfiles para o Wallet Service em diferentes ambientes.

## Arquivos

- **Dockerfile** - Imagem de produção (multi-stage build)
  - Build otimizado em container
  - Runtime em JRE alpine
  - JVM tuning para containers
  - Healthcheck configurado

- **Dockerfile.dev** - Imagem de desenvolvimento com hotreload
  - Build e hotreload em container
  - Spring Boot DevTools habilitado
  - Debug remoto via JDWP (porta 5006)
  - Automatic restart ao editar código

> **Nota:** O script de inicialização do banco `vibranium_wallet` foi consolidado em
> `infra/postgres/init-app-databases.sh`, que é executado centralmente pelo
> PostgreSQL de aplicação no primeiro boot. Este serviço usa Flyway para migrations.

## Uso

### Desenvolvimento (com hotreload)

```bash
docker compose -f infra/docker-compose.dev.yml up wallet-service

# Via scripts
make docker-dev-up     (Linux/Mac)
.\build.ps1 docker-dev-up  (Windows)
```

### Produção

```bash
docker build -f apps/wallet-service/docker/Dockerfile -t vibranium-wallet-service:latest .
docker-compose up wallet-service  # Usa imagem pré-built
```

## Volumes Montados (Dev)

- `./apps/wallet-service` → `/app/apps/wallet-service`
- `./libs` → `/app/libs`
- `./pom.xml` → `/app/pom.xml`
- `m2_cache` → `/root/.m2` (Maven cache persistente)

## Variáveis de Ambiente (Dev)

- `SPRING_PROFILES_ACTIVE=dev`
- `JAVA_OPTS=-agentlib:jdwp=...` (debug remoto)

## Mais Informações

- Ver [docker-compose.dev.yml](/docker-compose.dev.yml)
- Ver [TESTING_GUIDE.md](/TESTING_GUIDE.md)
