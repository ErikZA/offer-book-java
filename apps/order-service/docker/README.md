# Docker Images for Order Service

Este diretório contém os Dockerfiles para o Order Service em diferentes ambientes.

## Arquivos

- **Dockerfile** - Imagem de produção (multi-stage build)
  - Build otimizado em container
  - Runtime em JRE alpine
  - JVM tuning para containers
  - Healthcheck configurado

- **Dockerfile.dev** - Imagem de desenvolvimento com hotreload
  - Build e hotreload em container
  - Spring Boot DevTools habilitado
  - Debug remoto via JDWP (porta 5005)
  - Automatic restart ao editar código

## Uso

### Desenvolvimento (com hotreload)

```bash
docker-compose -f docker-compose.dev.yml up order-service

# Via scripts
make docker-dev-up     (Linux/Mac)
.\build.ps1 docker-dev-up  (Windows)
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

## Mais Informações

- Ver [docker-compose.dev.yml](/docker-compose.dev.yml)
- Ver [TESTING_GUIDE.md](/TESTING_GUIDE.md)
