# Order Service

Microsserviço responsável pelo gerenciamento de ordens de trading (BUY/SELL).

## 📋 Responsabilidades

- ✅ Criar, listar e cancelar ordens
- ✅ Validar ordens
- ✅ Publicar eventos de ordem criada/alterada
- ✅ Integração com Wallet Service para débito

## 🏗️ Estrutura

```
src/
├── main/
│   ├── java/com/vibranium/orderservice/
│   │   ├── OrderServiceApplication.java      # Aplicação principal
│   │   ├── controller/OrderController.java   # Endpoints REST
│   │   ├── domain/Order.java                 # Entidade
│   └── resources/
│       └── application.yaml                  # Configurações
└── test/
    └── java/.../OrderServiceApplicationTest.java  # Testes

docker/
├── Dockerfile                # Build production
└── Dockerfile.dev           # Build desenvolvimento
```

## 🚀 Desenvolvimento

### Executar via Docker (Recomendado)
```bash
# Inicie ambiente de desenvolvimento com hotreload
.\build.ps1 docker-dev-up       # Windows
make docker-dev-up              # Linux/Mac

# Ou direto com Docker Compose
docker compose -f infra/docker-compose.dev.yml up order-service
```

### Testes via Docker
```bash
# Testes em container
.\build.ps1 docker-test         # Windows
make docker-test                # Linux/Mac

# Ou direto
docker compose -f tests/docker-compose.test.yml up
```

### Debug Remoto
```bash
# Conecte debugger na porta 5005
# Veja: docs/testing/COMPREHENSIVE_TESTING.md#debug-remoto
```

## 🔗 Integração

### Endpoints
```
POST   /api/orders              # Criar ordem
GET    /api/orders              # Listar ordens
GET    /api/orders/{id}         # Buscar ordem
PATCH  /api/orders/{id}         # Atualizar ordem
DELETE /api/orders/{id}         # Cancelar ordem
```

### Eventos Publicados
- `OrderCreatedEvent` - Quando ordem é criada
- `OrderMatchedEvent` - Quando há matching
- `OrderCancelledEvent` - Quando ordem é cancelada

### Integração com Wallet
- Publica evento para deduzir saldo na carteira
- Aguarda confirmação de débito

## 📦 Dependências

| Biblioteca | Versão | Uso |
|-----------|--------|-----|
| Spring Boot Web | 3.2.3 | REST APIs |
| Spring Data JPA | 3.2.3 | Persistência |
| PostgreSQL | 16 | Banco de dados |
| JUnit 5 | Por Spring | Testes |
| AssertJ | 3.x | Assertions |
| REST Assured | 5.x | Testes de API |

## 🔍 Debugging

```bash
# Debug remoto na porta 5005
# Conecte seu IDE (IntelliJ IDEA, VS Code, etc)
# Veja: docs/testing/COMPREHENSIVE_TESTING.md#debug-remoto

# Iniciar com variações de debug
docker compose -f infra/docker-compose.dev.yml up order-service
```

---

**Service ID**: order-service  
**Port**: 8080  
**Debug Port**: 5005
