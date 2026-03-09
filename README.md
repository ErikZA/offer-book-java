# Vibranium Order Book Platform

Plataforma de trading orientada a eventos, com microsserviços Spring Boot e fluxo Docker-first.

## Requisito principal

Todo build, desenvolvimento e teste deve rodar via Docker.

## Quick Start

### Windows (PowerShell)

```powershell
# 1) Validar Docker
.\init.ps1

# 2) Configurar ambiente local
copy .env.example .env

# 3) Subir desenvolvimento
.\scripts\build.ps1 docker-dev-up

# 4) Rodar testes
.\scripts\build.ps1 docker-test
```

### Linux/macOS

```bash
# 1) Configurar ambiente local
cp .env.example .env

# 2) Subir desenvolvimento
make docker-dev-up

# 3) Rodar testes
make docker-test
```

### Compose direto

```bash
docker compose -f infra/docker-compose.dev.yml up -d
docker compose -f tests/e2e/docker-compose.e2e.yml up --abort-on-container-exit
```

## Comandos frequentes

```bash
# Windows
.\scripts\build.ps1 docker-dev-up
.\scripts\build.ps1 docker-dev-logs -Service order-service
.\scripts\build.ps1 docker-test
.\scripts\build.ps1 docker-prod-up

# Linux/macOS
make docker-dev-up
make docker-dev-logs SERVICE=order-service
make docker-test
make docker-prod-up
```

## Documentação canônica

- Índice geral: [docs/README.md](docs/README.md)
- Infraestrutura: [infra/README.md](infra/README.md)
- Testes: [docs/testing/README.md](docs/testing/README.md)
- Arquitetura: [docs/architecture/README.md](docs/architecture/README.md)
- Dados/modelagem: [docs/database/README.md](docs/database/README.md)
- Secrets: [docs/SECRETS_MANAGEMENT.md](docs/SECRETS_MANAGEMENT.md)
- Performance: [tests/performance/README.md](tests/performance/README.md)

## READMEs de serviços

- [apps/order-service/README.md](apps/order-service/README.md)
- [apps/wallet-service/README.md](apps/wallet-service/README.md)

## Estrutura do repositório

Veja: [docs/PROJECT_STRUCTURE.md](docs/PROJECT_STRUCTURE.md)

## Histórico preservado

A versão anterior completa deste README foi preservada em:

- [docs/archive/ROOT_README_legacy_2026-03-09.md](docs/archive/ROOT_README_legacy_2026-03-09.md)

## Estado do Documento

- Status: `canônico`
- Última consolidação: 2026-03-09
