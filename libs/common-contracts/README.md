# Common Contracts

Biblioteca compartilhada com eventos, DTOs e contratos de integração entre microsserviços.

## 📋 Conteúdo

### Events (Eventos de Domínio)

```java
// Base para todos os eventos
BaseEvent
├── id              : UUID
├── timestamp       : LocalDateTime
└── source         : String

// Eventos específicos
events/
├── OrderCreatedEvent       # Ordem criada
├── OrderMatchedEvent       # Ordem combinada
├── OrderCancelledEvent     # Ordem cancelada (não implementado)
├── WalletCreditedEvent     # Crédito realizado
├── WalletDebitedEvent      # Débito realizado
└── InsufficientFundsEvent  # Sem fundos (não implementado)
```

## 🏗️ Estrutura

```
src/
└── main/
    └── java/com/vibranium/contracts/
        ├── events/
        │   ├── BaseEvent.java
        │   ├── OrderCreatedEvent.java
        │   ├── OrderMatchedEvent.java
        │   ├── WalletCreditedEvent.java
        │   └── WalletDebitedEvent.java
        └── dtos/ (quando necessário)

pom.xml  # Sem dependencies externas (apenas JDK)
```

## 📦 Sem Dependências Externas!

Esta biblioteca é **intencionalmente simples**:
- ✅ Apenas classes POJO
- ✅ Nenhuma dependência externa
- ✅ Compatível com qualquer framework
- ✅ Serialização JSON fácil

## 🚀 Uso

### Em Outro Microsserviço

1. Adicionar dependência:
```xml
<dependency>
    <groupId>com.vibranium</groupId>
    <artifactId>common-contracts</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

2. Importar e usar:
```java
import com.vibranium.contracts.events.OrderCreatedEvent;

OrderCreatedEvent event = new OrderCreatedEvent();
event.setOrderId("123");
event.setUserId("user-456");
// ... serializar e publicar via RabbitMQ/Kafka
```

## 🧪 Testes

Nenhum teste nesta biblioteca (simplesmente structs).

## 📈 Extensão

Quando adicionar novos eventos:

1. Criar nova classe em `events/`
2. Estender `BaseEvent`
3. Adicionar campos específicos
4. Incrementar versão no `pom.xml`
5. Publicar no Maven central (opcional)

---

**Type**: Shared Library  
**Versão**: 1.0.0-SNAPSHOT  
**Min java Version**: 21
