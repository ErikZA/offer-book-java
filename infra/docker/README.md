# Docker Images for Testing

Este diretório contém as imagens Docker utilizadas para testes automatizados.

## Estrutura

- **Dockerfile** - Imagem para execução de testes (referenciada por `docker-compose.test.yml`)

## Uso

Os Dockerfiles são automaticamente buildados quando você executa `docker-compose.test.yml`:

```bash
# Build e executa testes
docker-compose -f docker-compose.test.yml up

# Ou via script
make docker-test  (Linux/Mac)
.\build.ps1 docker-test  (Windows)
```

## Referências

- Ver [docker-compose.test.yml](/docker-compose.test.yml) para configuração de testes
- Ver [docs/testing/COMPREHENSIVE_TESTING.md](../../docs/testing/COMPREHENSIVE_TESTING.md) para mais detalhes sobre testes
