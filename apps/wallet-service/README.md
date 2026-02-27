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
docker compose -f infra/docker-compose.dev.yml up wallet-service
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

#### Eventos de Autenticação (Keycloak → RabbitMQ)

O `wallet-service` deve consumir eventos publicados pelo **plugin `keycloak-event-listener-rabbitmq`** no exchange `amq.topic` do RabbitMQ. Esses eventos permitem reagir de forma reativa a mudanças de ciclo de vida do usuário, sem acoplamento direto ao Keycloak.

| Evento Keycloak | Routing Key AMQP | Ação esperada no wallet-service |
|---|---|---|
| `REGISTER` | `KK.EVENT.REALM.orderbook-realm.REGISTER` | **Criar carteira automaticamente** para o novo usuário com saldo zero |
| `LOGIN` | `KK.EVENT.REALM.orderbook-realm.LOGIN` | (Opcional) Registrar último acesso na carteira para auditoria |
| `LOGOUT` | `KK.EVENT.REALM.orderbook-realm.LOGOUT` | (Opcional) Invalidar cache local de sessão |
| `DELETE_ACCOUNT` | `KK.EVENT.REALM.orderbook-realm.DELETE_ACCOUNT` | Iniciar processo de encerramento de conta / bloqueio de carteira |

O payload publicado no broker segue o formato:

```json
{
  "id": "<uuid-do-evento>",
  "time": 1709041200000,
  "realmId": "orderbook-realm",
  "type": "REGISTER",
  "userId": "<keycloak-user-id>",
  "clientId": "order-client",
  "ipAddress": "192.168.1.1",
  "details": {
    "username": "tester",
    "email": "tester@vibranium.com"
  }
}
```

**Exemplo de listener Spring AMQP** (a implementar):

```java
// Bind na fila ao routing key de registro do realm
@RabbitListener(bindings = @QueueBinding(
    value = @Queue("wallet.keycloak.register"),
    exchange = @Exchange(value = "amq.topic", type = "topic"),
    key = "KK.EVENT.REALM.orderbook-realm.REGISTER"
))
public void onUserRegistered(KeycloakEvent event) {
    // Criar carteira para o novo usuário com userId = event.getUserId()
    walletService.createWallet(event.getUserId());
}
```

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
docker compose -f infra/docker-compose.dev.yml up wallet-service
```

---

**Service ID**: wallet-service  
**Port**: 8081  
**Debug Port**: 5006
