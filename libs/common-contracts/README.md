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
| Interface `VersionedContract` | Âncora única para o campo `schemaVersion` em todos os contratos (AT-16.1) |
| Interface `DomainEvent` | Contrato mínimo de rastreabilidade exigido por todos os eventos |
| Interface `Command` | Separação semântica clara: intenção (command) vs. fato ocorrido (event) |
| Jakarta Validation (`@NotNull`, `@DecimalMin`) | Garante que contratos inválidos sejam detectados antes de serem publicados |
| Factory methods (`Event.of(...)`) | Garantem que `eventId`, `occurredOn` e `schemaVersion=1` sejam sempre gerados automaticamente |
| `int schemaVersion` + compact constructor | Versionamento de contrato: default=1 garante backward compatibility com payloads antigos |

### Processo de desenvolvimento (TDD)

1. Definição do `pom.xml` com dependências mínimas (Jackson, Jakarta Validation, JUnit 5, AssertJ, Hibernate Validator no escopo test)
2. Criação dos **testes primeiro** (Fase RED) — cobrindo validação, serialização round-trip JSON, unicidade de IDs e propagação de correlação na Saga
3. Implementação dos contratos (Fase GREEN) até todos os testes passarem
4. **AT-16.1 (Fase RED→GREEN)** — `ContractSchemaVersionTest` adicionado para cobrir backward/forward compatibility com `schemaVersion`
5. **1.1.1 (Fase RED→GREEN)** — contratos compensatórios de release adicionados: `ReleaseFundsCommand`, `FundsReleasedEvent`, `FundsReleaseFailedEvent` + `RELEASE_DB_ERROR`
6. Resultado atual: `Tests run: 63, Failures: 0, Errors: 0`

---

## Estrutura do módulo

```
src/main/java/com/vibranium/contracts/
│
├── VersionedContract.java                 ← interface de versionamento: schemaVersion() (AT-16.1)
│
├── commands/                              # Intenções (o que DEVE acontecer)
│   ├── Command.java                       ← interface marker com correlationId (extends VersionedContract)
│   ├── wallet/
│   │   ├── CreateWalletCommand            ← Criar carteira zerada (onboarding)
│   │   ├── ReserveFundsCommand            ← Bloquear BRL ou VIBRANIUM
│   │   ├── ReleaseFundsCommand            ← Desbloquear BRL ou VIBRANIUM (compensação)
│   │   └── SettleFundsCommand             ← Liquidar trade (pós-match)
│   └── order/
│       └── CreateOrderCommand             ← Criar intenção de compra/venda
│
├── events/                                # Fatos (o que JÁ aconteceu)
│   ├── DomainEvent.java                   ← Interface base de rastreabilidade (extends VersionedContract)
│   ├── wallet/
│   │   ├── WalletCreatedEvent             ← Carteira criada com sucesso
│   │   ├── FundsReservedEvent             ← Saldo bloqueado ✅
│   │   ├── FundsReservationFailedEvent    ← Saldo insuficiente (compensação) ❌
│   │   ├── FundsReleasedEvent             ← Saldo desbloqueado com sucesso ✅
│   │   ├── FundsReleaseFailedEvent        ← Falha ao desbloquear (incidente) ⚠️
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
    └── FailureReason  ← Razões padronizadas de falha (INSUFFICIENT_FUNDS, WALLET_NOT_FOUND,
                         SETTLEMENT_DB_ERROR, RELEASE_DB_ERROR, SAGA_TIMEOUT, DUPLICATE_MESSAGE,
                         INTERNAL_ERROR)
```

---

## Contrato de rastreabilidade (`DomainEvent`)

Todo evento implementa `DomainEvent`, que exige cinco metadados obrigatórios:

```java
public interface DomainEvent extends VersionedContract {
    UUID    eventId();       // Idempotência: consumidor descarta duplicatas pelo eventId
    UUID    correlationId(); // Rastreabilidade: mesmo ID do início ao fim da Saga
    String  aggregateId();   // Agregado afetado (orderId, walletId, matchId)
    Instant occurredOn();    // Timestamp UTC para ordenação e auditoria
    int     schemaVersion(); // Versionamento do contrato (AT-16.1) — padrão: 1
}
```

## Versionamento de contrato (`schemaVersion`) — AT-16.1

Todos os eventos e comandos possuem o campo `int schemaVersion` (default `1`). Ele habilita **deploy independente** entre produtor e consumidor sem coordenação:

| Cenário | Comportamento |
|---|---|
| **Backward compat** — consumer novo, payload antigo (sem `schemaVersion`) | Compact constructor assume `schemaVersion = 1` automaticamente |
| **Forward compat** — consumer antigo, payload novo (com campos extras) | `FAIL_ON_UNKNOWN_PROPERTIES=false` ignora silenciosamente |
| **Round-trip** — factory method → serializa → deserializa | `schemaVersion=1` preservado na íntegra |

**Padrão obrigatório para novos records:**
```java
public record MeuEvento(
        // ... campos de domínio ...
        int schemaVersion         // sempre o último campo
) implements DomainEvent {
    public MeuEvento {
        if (schemaVersion == 0) schemaVersion = 1; // backward compat
    }
    public static MeuEvento of(...) {
        return new MeuEvento(..., 1); // factory sempre passa schemaVersion=1
    }
}
```

### Configuração obrigatória do `ObjectMapper` nos microsserviços

`Instant` é serializado como **epoch-milliseconds** (long), e campos desconhecidos são tolerados para forward compatibility. A configuração canônica está centralizada em `libs/common-utils` (`VibraniumJacksonConfig`):

```java
objectMapper.registerModule(new JavaTimeModule())
            .enable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(SerializationFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS)
            .disable(DeserializationFeature.READ_DATE_TIMESTAMPS_AS_NANOSECONDS)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false); // forward compat
```

> **`FAIL_ON_UNKNOWN_PROPERTIES=false`** é obrigatório para que um consumer antigo não quebre ao receber um payload de um producer mais novo com campos adicionais. Sem essa configuração, um campo novo causaria `UnrecognizedPropertyException` e rollback da mensagem.

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
Tests run: 63, Failures: 0, Errors: 0, Skipped: 0
```

| Classe de teste | Tipo | O que valida |
|---|---|---|
| `CommandValidationTest` | Unitário (JSR-380) | Constraints `@NotNull`, `@DecimalMin` para todos os Commands (incl. `ReleaseFundsCommand`) |
| `DomainEventContractTest` | Unitário | `eventId` único, `correlationId` presente, `occurredOn` recente |
| `MatchExecutedEventTest` | Unitário | Evento crítico: integridade, imutabilidade, unicidade em 1000 instâncias |
| `WalletEventsSerializationTest` | Round-trip JSON | BigDecimal com alta precisão, Instant, enums (incl. `FundsReleasedEvent`, `FundsReleaseFailedEvent`) |
| `OrderEventsSerializationTest` | Round-trip JSON | Todos os eventos de order sobrevivem ao ciclo serialização → bytes → deserialização |
| `SagaChoreographyContractIT` | Integração | `correlationId` propagado em 5 cenários: happy path, saldo insuficiente, falha de liquidação, idempotência e **compensação com release** |
| `ContractSchemaVersionTest` | Unitário (AT-16.1) | backward compat (payload sem `schemaVersion` → default 1), forward compat (campo desconhecido ignorado), round-trip com `schemaVersion=1` |

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
