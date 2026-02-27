# Wallet Service

Microsserviço responsável pela gestão de carteiras de usuários e transações.

## 📋 Responsabilidades

- ✅ Criar carteira para novo usuário
- ✅ Depositar fundos
- ✅ Sacar fundos
- ✅ Manter saldo consistente
- ✅ Registrar histórico de transações
- ✅ Consumir eventos de ordem para débito/crédito
- ✅ Publicar eventos de transação

## 🏗️ Estrutura

```
src/
├── main/
│   ├── java/com/vibranium/walletservice/
│   │   ├── WalletServiceApplication.java     # Aplicação principal
│   │   ├── controller/WalletController.java  # Endpoints REST
│   │   ├── domain/
│   │   │   ├── Wallet.java                   # Entidade
│   │   │   └── WalletTransaction.java        # Transação
│   │   ├── listener/OrderEventListener.java  # Consome eventos
│   │   ├── service/WalletService.java        # Lógica de negócio
│   │   └── repository/...                    # Persistência
│   └── resources/
│       └── application.yaml                  # Configurações
└── test/
    └── java/.../WalletServiceApplicationTest.java  # Testes

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
docker compose -f docker/docker-compose.dev.yml up wallet-service
```

### Testes via Docker
```bash
# Testes em container
.\build.ps1 docker-test         # Windows  
make docker-test                # Linux/Mac

# Ou direto
docker compose -f docker/docker-compose.test.yml up
```

### Debug Remoto
```bash
# Conecte debugger na porta 5006
# Veja: docs/testing/COMPREHENSIVE_TESTING.md#debug-remoto
```

## 🔗 Integração

### Endpoints
```
POST   /api/wallets              # Criar carteira
GET    /api/wallets/{id}         # Buscar carteira
PUT    /api/wallets/{id}/deposit # Depositar
PUT    /api/wallets/{id}/withdraw # Sacar
GET    /api/wallets/{id}/transactions # Histórico
```

### Eventos Consumidos
- `OrderCreatedEvent` - Para validar fundos
- `OrderMatchedEvent` - Para atualizar saldo
- `OrderCancelledEvent` - Para reembolsar

### Eventos Publicados
- `WalletCreditedEvent` - Quando crédito é realizado
- `WalletDebitedEvent` - Quando débito é realizado
- `InsufficientFundsEvent` - Quando não há fundos

## 📦 Dependências

| Biblioteca | Versão | Uso |
|-----------|--------|-----|
| Spring Boot Web | 3.2.3 | REST APIs |
| Spring Data JPA | 3.2.3 | Persistência |
| Spring Data MongoDB | 3.2.3 | Audit logs |
| PostgreSQL | 16 | Banco de dados |
| JUnit 5 | Por Spring | Testes |
| AssertJ | 3.x | Assertions |
| REST Assured | 5.x | Testes de API |

## 🔍 Debugging

```bash
# Debug remoto na porta 5006
# Conecte seu IDE (IntelliJ IDEA, VS Code, etc)
# Veja: docs/testing/COMPREHENSIVE_TESTING.md#debug-remoto

# Iniciar com variações de debug
docker compose -f docker/docker-compose.dev.yml up wallet-service
```

---

**Service ID**: wallet-service  
**Port**: 8081  
**Debug Port**: 5006
