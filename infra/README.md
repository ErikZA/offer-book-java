# Infrastructure

Configurações de infraestrutura e scripts de setup.

## 📋 Estrutura

### **postgres/**
Configurações e scripts de inicialização do PostgreSQL.

```
init-postgres.sql  # Schema e dados iniciais
```

### **mongo/** (opcional)
MongoDB para audit logs e cache.

```
init-mongo.js      # Collections iniciais
```

### **kong/**
API Gateway Kong (roteamento de requests).

```
kong-config.md     # Configuração de rotas
```

### **keycloak/**
Autenticação e autorização (OAuth 2.0 / OIDC).

```
keycloak-setup.sh  # Deploy do Keycloak
```

## 🔧 Uso

### Desenvolvimento
```bash
# Docker Compose já inclui estas configurações
docker-compose -f docker/docker-compose.dev.yml up
```

### Staging/Produção
```bash
# Configs customizadas por ambiente
docker-compose -f docker/docker-compose.staging.yml up
docker-compose -f docker/docker-compose.yml up
```

## 📦 Serviços

| Serviço | Porta | Uso |
|---------|-------|-----|
| PostgreSQL | 5432 | Banco de dados principal |
| MongoDB | 27017 | Audit/cache (opcional) |
| Redis | 6379 | Cache |
| RabbitMQ | 5672 | Message broker |
| Kong | 8000/8001 | API Gateway |
| Keycloak | 8180 | Auth server |

## 🔐 Segurança

- Credenciais em `.env` (nunca commit)
- Volumes nomeados para persistência
- Health checks em todos os serviços
- Redes isoladas por ambiente

---

**Nota**: Docker Compose dev/test já pré-configura tudo. Não modifique diretamente exceto para staging/prod.
