# common-contracts

Biblioteca compartilhada que atua como o **dicionário comum (Linguagem Ubíqua)** da plataforma Vibranium Order Book. Contém os contratos de Comandos (intenções) e Eventos de Domínio (fatos ocorridos) que trafegam pelas filas do RabbitMQ entre os microsserviços `order-service` e `wallet-service`.

---

## Por que este módulo existe?

Em uma arquitetura de microsserviços orientada a eventos (EDA) com coreografia de Sagas, os serviços se comunicam trocando mensagens assíncronas. Sem um contrato compartilhado e tipado, cada serviço definiria suas próprias classes para representar a mesma mensagem, resultando em:

- **Inconsistência semântica** — o mesmo evento com estruturas diferentes em cada serviço
- **Acoplamento implícito** — mudanças de campo quebram silenciosamente o consumidor
- **Ausência de rastreabilidade** — sem metadados padronizados, não há como correlacionar eventos de uma mesma Saga

`common-contracts` resolve isso sendo a **única fonte da verdade** sobre o formato de cada mensagem.

---

## Como foi construído

### Princípios técnicos aplicados

| Decisão | Motivo |
|---|---|
| `record` Java (imutável) | Mensagens em trânsito não devem ser mutáveis após criação |
| Framework-agnostic | A lib não depende de Spring, JPA ou qualquer infraestrutura — pode ser usada em qualquer serviço |
| Interface `DomainEvent` | Contrato mínimo de rastreabilidade exigido por todos os eventos |
| Interface `Command` | Separação semântica clara: intenção (command) vs. fato ocorrido (event) |
| Jakarta Validation (`@NotNull`, `@DecimalMin`) | Garante que contratos inválidos sejam detectados antes de serem publicados |
| Factory methods (`Event.of(...)`) | Garantem que `eventId` e `occurredOn` sejam sempre gerados automaticamente |

### Processo de desenvolvimento (TDD)

1. Definição do `pom.xml` com dependências mínimas (Jackson, Jakarta Validation, JUnit 5, AssertJ, Hibernate Validator no escopo test)
2. Criação dos **testes primeiro** (Fase RED) — 50 casos cobrindo validação, serialização round-trip JSON, unicidade de IDs e propagação de correlação na Saga
3. Implementação dos contratos (Fase GREEN) até todos os testes passarem
4. Resultado: `Tests run: 50, Failures: 0, Errors: 0` em 5.6s

---

## Estrutura do módulo

```
src/main/java/com/vibranium/contracts/
│
├── commands/                              # Intenções (o que DEVE acontecer)
│   ├── Command.java                       ← interface marker com correlationId
│   ├── wallet/
│   │   ├── CreateWalletCommand            ← Criar carteira zerada (onboarding)
│   │   ├── ReserveFundsCommand            ← Bloquear BRL ou VIBRANIUM
│   │   └── SettleFundsCommand             ← Liquidar trade (pós-match)
│   └── order/
│       └── CreateOrderCommand             ← Criar intenção de compra/venda
│
├── events/                                # Fatos (o que JÁ aconteceu)
│   ├── DomainEvent.java                   ← Interface base de rastreabilidade
│   ├── wallet/
│   │   ├── WalletCreatedEvent             ← Carteira criada com sucesso
│   │   ├── FundsReservedEvent             ← Saldo bloqueado ✅
│   │   ├── FundsReservationFailedEvent    ← Saldo insuficiente (compensação) ❌
│   │   ├── FundsSettledEvent              ← Trade liquidado ✅
│   │   └── FundsSettlementFailedEvent     ← Falha na liquidação (incidente) ⚠️
│   └── order/
│       ├── OrderReceivedEvent             ← Entrada da Saga (202 Accepted)
│       ├── OrderAddedToBookEvent          ← Inserida no Redis Sorted Set
│       ├── MatchExecutedEvent             ← EVENTO CRÍTICO — dispara settlement
│       ├── OrderPartiallyFilledEvent      ← Match parcial (ordem continua aberta)
│       ├── OrderFilledEvent               ← Ordem totalmente executada
│       └── OrderCancelledEvent            ← Cancelamento (qualquer motivo)
│
└── enums/
    ├── OrderType      ← BUY / SELL
    ├── AssetType      ← BRL / VIBRANIUM
    ├── OrderStatus    ← PENDING → OPEN → PARTIAL → FILLED / CANCELLED
    └── FailureReason  ← Razões padronizadas de falha (Saga compensatória)
```

---

## Contrato de rastreabilidade (`DomainEvent`)

Todo evento implementa `DomainEvent`, que exige quatro metadados obrigatórios:

```java
public interface DomainEvent {
    UUID    eventId();       // Idempotência: consumidor descarta duplicatas pelo eventId
    UUID    correlationId(); // Rastreabilidade: mesmo ID do início ao fim da Saga
    String  aggregateId();   // Agregado afetado (orderId, walletId, matchId)
    Instant occurredOn();    // Timestamp UTC para ordenação e auditoria
}
```

### Configuração obrigatória do `ObjectMapper` nos microsserviços

`Instant` é serializado como **epoch-milliseconds** (long). Configure o `ObjectMapper` assim:

```java
objectMapper.registerModule(new JavaTimeModule())
            .enable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(SerializationFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS)
            .disable(DeserializationFeature.READ_DATE_TIMESTAMPS_AS_NANOSECONDS);
```

> **Próximo passo**: essa fábrica de `ObjectMapper` será centralizada em `libs/common-utils` para evitar que cada microsserviço duplique esta configuração.

---

## Uso nos microsserviços

Adicionar a dependência:

```xml
<dependency>
    <groupId>com.vibranium</groupId>
    <artifactId>common-contracts</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

Exemplo de publicação no `order-service`:

```java
// Entrada da Saga: order-service publica ao receber POST /orders
OrderReceivedEvent event = OrderReceivedEvent.of(
    correlationId, orderId, userId, walletId,
    OrderType.BUY, price, amount
);
rabbitTemplate.convertAndSend(exchange, routingKey, event);
```

Exemplo de consumo no `wallet-service`:

```java
@RabbitListener(queues = "order.received")
public void onOrderReceived(OrderReceivedEvent event) {
    // correlationId já está no evento — propague para todos os eventos gerados
    reserveFundsUseCase.execute(ReserveFundsCommand(..., event.correlationId(), ...));
}
```

---

## Testes

```
Tests run: 50, Failures: 0, Errors: 0, Skipped: 0
```

| Classe de teste | Tipo | O que valida |
|---|---|---|
| `CommandValidationTest` | Unitário (JSR-380) | Constraints `@NotNull`, `@DecimalMin` sem Spring |
| `DomainEventContractTest` | Unitário | `eventId` único, `correlationId` presente, `occurredOn` recente |
| `MatchExecutedEventTest` | Unitário | Evento crítico: integridade, imutabilidade, unicidade em 1000 instâncias |
| `WalletEventsSerializationTest` | Round-trip JSON | BigDecimal com alta precisão, Instant, enums |
| `OrderEventsSerializationTest` | Round-trip JSON | Todos os eventos de order sobrevivem ao ciclo serialização → bytes → deserialização |
| `SagaChoreographyContractIT` | Integração | `correlationId` propagado do `CreateOrderCommand` até `OrderFilledEvent` e nos caminhos de compensação |

---

## Por que o evento de criação de usuário do Keycloak NÃO está aqui?

O Keycloak publica um evento no RabbitMQ ao criar um usuário (via Event SPI/webhook). Esse evento aciona a criação de uma carteira no `wallet-service`.

**A decisão**: o contrato bruto do Keycloak **não pertence a esta biblioteca** pelos seguintes motivos:

1. **Contexto externo, não controlado**: o formato do evento é definido pelo Keycloak. Uma atualização da ferramenta pode renomear campos (ex: `userId` → `sub`), quebrando toda a lib sem aviso.

2. **Violação da Linguagem Ubíqua**: `common-contracts` fala apenas o idioma do domínio Vibranium. Um evento do Keycloak pertence ao domínio de IAM — um *Bounded Context* externo e independente.

3. **Padrão correto — Anti-Corruption Layer (ACL)**: o `wallet-service` é o único serviço que consome o evento do Keycloak, portanto é o único responsável por traduzir esse "idioma externo".

O fluxo correto é:

```
[Keycloak Event — formato externo]
        ↓
[ACL: KeycloakEventAdapter no wallet-service]  ← tradução do contrato externo
        ↓
[CreateWalletCommand]                          ← contrato interno (está aqui ✅)
        ↓
[WalletCreatedEvent]                           ← fato de domínio (está aqui ✅)
```

**Resumo**: `CreateWalletCommand` e `WalletCreatedEvent` estão nesta lib porque outros serviços podem precisar reagir à criação de uma carteira. O evento bruto do Keycloak fica encapsulado como detalhe de infraestrutura exclusivo do `wallet-service`.
