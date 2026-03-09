# Docker Images for Testing

Este diretório contém as imagens Docker utilizadas para testes automatizados.

## Estrutura

- **Dockerfile** - Imagem para execução de testes (referenciada por `tests/e2e/docker-compose.e2e.yml`)

## Uso

Os Dockerfiles são automaticamente buildados quando você executa `tests/e2e/docker-compose.e2e.yml`:

```bash
# Build e executa testes
docker-compose -f tests/e2e/docker-compose.e2e.yml up

# Ou via script
make docker-test  (Linux/Mac)
.\scripts\build.ps1 docker-test  (Windows)
```

## Referências

- Ver [tests/e2e/docker-compose.e2e.yml](../../tests/e2e/docker-compose.e2e.yml) para configuração de testes
- Ver [docs/testing/COMPREHENSIVE_TESTING.md](../../docs/testing/COMPREHENSIVE_TESTING.md) para mais detalhes sobre testes

