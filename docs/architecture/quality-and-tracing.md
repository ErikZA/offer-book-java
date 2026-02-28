# 🛡️ Guia de Qualidade e Observabilidade: Confiança em Larga Escala

Em um sistema de missão crítica que envolve negociação de ativos como o Vibranium, não podemos confiar apenas em testes manuais. Precisamos de uma infraestrutura que valide a resiliência e a rastreabilidade exigidas.

## 1. Testes de Integração Reais (Testcontainers)

Tradicionalmente, desenvolvedores usam bancos de dados "falsos" (H2 ou mocks) para testes. No entanto, o comportamento de um **Redis Sorted Set** ou de uma transação **ACID no PostgreSQL** é difícil de simular perfeitamente.

* **O Conceito**: O *Testcontainers* permite que, durante a execução dos testes, o sistema suba instâncias reais (em contêineres Docker efêmeros) de todas as nossas bases de dados e mensageria.
* **A Aplicação**: Garantimos que o código Java interaja corretamente com as versões exatas do Postgres, Mongo, Redis e RabbitMQ que serão usadas em produção, eliminando o "na minha máquina funciona".

### 1.1 Configuração especial para CDC (Debezium)

O Debezium exige replicação lógica ativa no PostgreSQL. O container de testes é iniciado com flags adicionais:

```java
// AbstractIntegrationTest.java
static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine")
    .withCommand(
        "postgres",
        "-c", "wal_level=logical",
        "-c", "max_replication_slots=10",
        "-c", "max_wal_senders=10"
    );
```

Sem essas flags o conector Debezium falha ao criar o slot de replicação com:
```
ERROR: logical replication slot requires wal_level >= logical
```

### 1.2 Race condition: slot de replicação não estava ativo

**Problema encontrado em produção de testes:** O `DebeziumEngine` é iniciado em um VirtualThread de forma **assíncrona**. O método `SmartLifecycle.start()` retornava imediatamente após `executor.execute(engine)`, mas o Debezium levava 1–3 segundos para conectar ao PostgreSQL e criar o slot de replicação. O primeiro INSERT de cada teste chegava nessa **janela cega** e nunca era capturado pelo WAL, resultando em `ConditionTimeoutException` flaky nas runs longas.

**Solução implementada — `awaitSlotActive()`:**

```java
// DebeziumOutboxEngine.java — método executado no start() após executor.execute(engine)
private void awaitSlotActive(String slotName) {
    final String sql =
        "SELECT active FROM pg_replication_slots WHERE slot_name = ?";

    for (int i = 0; i < 60; i++) {   // máx 30 s (60 × 500 ms)
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, slotName);
            var rs = ps.executeQuery();
            if (rs.next() && rs.getBoolean("active")) {
                return;  // slot ativo: Debezium está capturando WAL
            }
        } catch (Exception ignored) { }
        Thread.sleep(500);
    }
    logger.warn("Slot '{}' não ficou ativo em 30 s.", slotName);
}
```

**Lição:** Sempre que um componente de infraestrutura for iniciado de forma assíncrona e outros testes dependerem de seu estado, implemente uma barreira explícita (poll + timeout) antes de devolver o controle ao framework de testes.

---

## 2. Governança Arquitetural (ArchUnit)

Em um monorepo com múltiplos microsserviços e bibliotecas compartilhadas, é fácil um desenvolvedor acidentalmente misturar as camadas (ex: usar uma regra de negócio dentro de uma query de leitura).

* **O Conceito**: O *ArchUnit* é uma biblioteca de teste que analisa a estrutura do código (o bytecode).
* **A Aplicação**: Criamos "testes de arquitetura" que falham o build se alguém tentar, por exemplo, importar uma classe da pasta `command/` dentro da pasta `query/`. Isso garante que o padrão **CQRS** seja respeitado por todo o time para sempre.

## 3. Rastreabilidade Distribuída (Micrometer Tracing)

Com ordens sendo enviadas freneticamente por robôs, entender por que uma transação falhou em um ecossistema de microsserviços é um desafio.

* **O Conceito**: Cada requisição recebe um **Trace ID** único no momento em que entra pelo API Gateway (Kong).
* **A Aplicação**: Esse ID viaja junto com a mensagem pelo RabbitMQ e por todos os bancos de dados. Se uma compra for concretizada na Wallet, mas a ordem não for atualizada no Mongo, podemos buscar pelo Trace ID e ver exatamente onde a cadeia de eventos quebrou.



## 4. Tolerância a Falhas (Resilience4j)

O desafio pede para compreender o que acontece quando diferentes componentes falham.

* **O Conceito**: Implementamos padrões de "disjuntor" (Circuit Breaker) e limites de taxa (Rate Limiting).
* **A Aplicação**: Se o banco de dados PostgreSQL ficar lento sob carga extrema, o *Resilience4j* "abre o circuito" para evitar que o serviço de Wallet trave completamente o sistema, permitindo uma falha graciosa ou uma resposta de erro rápida enquanto o banco se recupera.

---

## 5. Isolamento de Contexto Spring em Testes de Integração

### O problema: `@DataJpaTest` carregando beans de produção

O `OutboxPublisherService` e o `DebeziumOutboxEngine` são beans `@Service`/`@Component` que dependem de `RabbitTemplate` e `DataSource`. Quando o Spring carrega um slice de JPA (`@DataJpaTest`), esses beans seriam instanciados mesmo sem o broker RabbitMQ disponível — causando falha na inicialização do contexto.

**Solução: `@ConditionalOnProperty`**

```java
@Service
@ConditionalOnProperty(
    name     = "app.outbox.debezium.enabled",
    havingValue  = "true",
    matchIfMissing = false   // ← desativado por padrão
)
public class OutboxPublisherService { ... }
```

Com `matchIfMissing = false`, os beans do Outbox CDC só são criados quando explicitamente habilitados via `application-test.yaml` ou `@TestPropertySource`:

```yaml
# application-test.yaml (padrão para @DataJpaTest e @SpringBootTest convencionais)
app.outbox.debezium.enabled: false
```

```java
// OutboxPublisherIntegrationTest.java — contexto dedicado com tudo ativo
@SpringBootTest
@TestPropertySource(properties = "app.outbox.debezium.enabled=true")
class OutboxPublisherIntegrationTest { ... }
```

### O problema: teste de carga interferindo nos testes unitários do relay

O `shouldHandle500ConcurrentOutboxMessages` insere 500 registros na `outbox_message`. Quando rodado antes dos testes de 5-eventos, o Debezium ainda está processando o backlog de 500 mensagens e não entrega a nova mensagem dentro do timeout.

**Solução: `@TestMethodOrder` + `@Order`**

```java
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OutboxPublisherIntegrationTest {

    @Test @Order(1) void shouldPublishFundsReservedEvent() { ... }
    @Test @Order(2) void shouldPublishFundsReservationFailedEvent() { ... }
    @Test @Order(3) void shouldPublishFundsSettledEvent() { ... }
    @Test @Order(4) void shouldNotPublishSameMessageTwice() { ... }
    @Test @Order(5) void shouldHandle500ConcurrentOutboxMessages() { ... } // ← SEMPRE POR ÚLTIMO
}
```

**Regra geral:** testes de carga (volume ≥ 100 registros) sempre devem ter o `@Order` mais alto dentro da classe ou ser extraídos para uma classe própria com `@Tag("load")`.

---
