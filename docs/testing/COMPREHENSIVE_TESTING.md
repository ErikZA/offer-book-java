# 📚 Guia Completo de Testes - Consolidado

## 📋 Conteúdo

1. [Visão Geral](#visão-geral)
2. [Quick Start](#quick-start)
3. [Estrutura de Testes](#estrutura-de-testes)
4. [Hierarquia de Classes Base](#hierarquia-de-classes-base)
5. [Tipos de Testes](#tipos-de-testes)
6. [Padrões de Teste](#padrões-de-teste)
7. [Executando Testes](#executando-testes)
8. [Ferramentas e Bibliotecas](#ferramentas-e-bibliotecas)
9. [Ambientes Docker](#ambientes-docker)
10. [Cobertura de Código](#cobertura-de-código)
11. [Debug Remoto](#debug-remoto)
12. [Checklist de Qualidade](#checklist-de-qualidade)
13. [Troubleshooting](#troubleshooting)
14. [Testes de CDC — Debezium Outbox](#testes-de-cdc--debezium-outbox)
15. [Partial Fill — Requeue Atômico e Idempotência por eventId (US-002)](#partial-fill--requeue-atômico-e-idempotência-por-eventid-us-002)
16. [Invariantes de Domínio Wallet — Encapsulamento de Agregado (US-005)](#invariantes-de-domínio-wallet--encapsulamento-de-agregado-us-005)
17. [Referências](#referências)

---

## Visão Geral

Este projeto está configurado com um ambiente completo de **Test-Driven Development (TDD)** utilizando as melhores bibliotecas de testes do mercado:

- **JUnit 5 (Jupiter)** - Framework de testes moderno
- **Mockito** - Framework de mocking
- **AssertJ** - Biblioteca de assertions fluentes
- **REST Assured** - Testes de APIs REST
- **TestContainers** - Testes com containers Docker
- **WireMock** - Mocking de serviços HTTP
- **Jacoco** - Cobertura de código

---

## Quick Start

✅ **Todos os testes execute via Docker** - Não instale Maven/Java na sua máquina!

### Windows (PowerShell)

```powershell
# 1. Validar Docker
.\init.ps1

# 2. Executar testes
.\build.ps1 docker-test

# 3. Iniciar desenvolvimento com hotreload
.\build.ps1 docker-dev-up

# Ver logs de um serviço específico
.\build.ps1 docker-dev-logs -Service order-service
```

### Linux / macOS

```bash
# 1. Validar Docker
make docker-status

# 2. Executar testes
make docker-test

# 3. Iniciar desenvolvimento
make docker-dev-up

# Ver logs de um serviço específico
make docker-dev-logs SERVICE=order-service
```

### Direto com Docker Compose

```bash
# Executar testes e mostrar saída
docker compose -f tests/docker-compose.test.yml up

# Desenvolvimento (detached, sem ver logs)
docker compose -f infra/docker-compose.dev.yml up -d

# Ver logs depois de iniciar
docker compose -f infra/docker-compose.dev.yml logs -f

# Parar tudo
docker compose -f infra/docker-compose.dev.yml down
```

---

## Estrutura de Testes

### Padrão de Nomenclatura

```
src/test/java/
├── com/vibranium/orderservice/
│   ├── OrderServiceTest.java          # Testes unitários (*Test.java)
│   ├── OrderControllerIT.java         # Testes de integração (*IT.java)
│   └── ...
└── com/vibranium/walletservice/
    ├── WalletServiceTest.java         # Testes unitários
    ├── WalletControllerIT.java        # Testes de integração
    └── ...
```

**Convenção de Nomenclatura**:
- **`*Test.java`** - Testes unitários (sem Spring Context)
- **`*IT.java`** - Testes de integração (com Spring Context)

## Hierarquia de Classes Base

### Problema: SLA vs. Containers Docker

Ao adicionar o MongoDB como 4º container Testcontainers ao ambiente de teste, testes de
concorrência que validam SLA de latência (`p99 ≤ 200ms`) começaram a falhar
consistentemente com `p99` na faixa de **350–900ms**.

**Causa raiz:** 4 containers Docker (PostgreSQL + RabbitMQ + Redis + MongoDB) rodando
simultaneamente no mesmo host causam contenção de CPU, TCP stack e memória durante
o teste de 50 requests concorrentes. A latência adicional era de Docker overhead,
not do código da aplicação.

**Diagnóstico confirmado:** p99 > 200ms mesmo com o código de publicação de eventos
completamente removido — provando que a causa era infra, não código.

### Solução: Hierarquia de Classes Base com Isolamento de Containers

A suite foi dividida em **duas hierarquias**, cada uma com um conjunto mínimo de containers:

```
AbstractIntegrationTest          ← Command Side (3 containers: PG + MQ + Redis)
       │
       ├── OrderCommandControllerTest      # SLA test: p99 ≤ 200ms ✔
       ├── OrderSagaConcurrencyTest
       ├── MatchEngineRedisIntegrationTest
       ├── KeycloakUserRegistryIntegrationTest
       └── OrderServiceApplicationTest
       │
       └── AbstractMongoIntegrationTest    ← Query Side (4 containers: PG + MQ + Redis + Mongo)
              └── OrderQueryControllerTest
```

**`AbstractIntegrationTest`** — exclui auto-configuration MongoDB:
```java
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = {
        "spring.autoconfigure.exclude=" +
            "...MongoAutoConfiguration," +
            "...MongoDataAutoConfiguration," +
            "...MongoRepositoriesAutoConfiguration",
        // Desabilita beans do Query Side (@ConditionalOnProperty)
        "app.mongodb.enabled=false"
    }
)
```

**`AbstractMongoIntegrationTest`** — adiciona MongoDB ao contexto:
```java
@SpringBootTest(webEnvironment = RANDOM_PORT)  // sem exclusões
public abstract class AbstractMongoIntegrationTest extends AbstractIntegrationTest {

    static final MongoDBContainer MONGODB =
        new MongoDBContainer(DockerImageName.parse("mongo:7.0")).withReuse(true);

    static { MONGODB.start(); }

    @DynamicPropertySource
    static void registerMongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", MONGODB::getReplicaSetUrl);
    }

    @Autowired
    protected MongoTemplate mongoTemplate;
}
```

### @ConditionalOnProperty para Beans do Query Side

Beans que dependem de MongoDB são anotados com `@ConditionalOnProperty` para que
não sejam criados quando `app.mongodb.enabled=false`:

```java
// OrderEventProjectionConsumer.java
@Component
@ConditionalOnProperty(name = "app.mongodb.enabled", matchIfMissing = true)
public class OrderEventProjectionConsumer { ... }

// OrderQueryController.java
@RestController
@ConditionalOnProperty(name = "app.mongodb.enabled", matchIfMissing = true)
public class OrderQueryController { ... }

// MongoIndexConfig.java
@Bean
@ConditionalOnProperty(name = "app.mongodb.enabled", matchIfMissing = true)
SmartInitializingSingleton mongoOrdersIndexInitializer(MongoTemplate mongoTemplate) { ... }
```

> **Por que `matchIfMissing = true`?**  
> Em produção, a propriedade não existe no `application.yaml` — o comportamento deve ser
> criar os beans (MongoDB é nécessário em prod). Apenas nos testes do Command Side
> `app.mongodb.enabled=false` é explicitamente definido.

> **Por que não usar `@ConditionalOnBean(MongoTemplate.class)`?**  
> `@ConditionalOnBean` em `@Component` é avaliado pelo `BeanFactory` antes dos
> auto-configures criarem o `MongoTemplate` — a avaliação é não-determinista e pode
> falhar com resultados inconsistentes. `@ConditionalOnProperty` é resolvido contra
> o `Environment`, que já tem os valores dos `@DynamicPropertySource` antes do
> contexto ser criado.

### Resultado da Segregação

| Suite anterior (4 containers everywhere) | Suite atual (hierarquia isolada)     |
|------------------------------------------|--------------------------------------|
| SLA p99: 350–900ms (🚨 FAIL)              | SLA p99: < 200ms (✅ PASS)            |
| 31 testes, 1 falha                       | 31 testes, 0 falhas                  |
| MongoDB no SLA test class                | MongoDB só em `AbstractMongo*`       |

---

## Tipos de Testes

### 1. Testes Unitários (`*Test.java`)

Testam componentes isolados (Services, Utilities) sem Spring.

```java
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {
    
    @Mock
    private OrderRepository repository;
    
    @InjectMocks
    private OrderService service;
    
    @Test
    @DisplayName("deve criar um pedido com sucesso")
    void shouldCreateOrderSuccessfully() {
        // Arrange
        Order order = new Order();
        when(repository.save(any())).thenReturn(order);
        
        // Act
        Order result = service.createOrder(order);
        
        // Assert
        assertThat(result).isNotNull();
        verify(repository, times(1)).save(any());
    }
}
```

### 2. Testes de Integração (`*IT.java`)

Testam APIs REST e fluxos completos com Spring.

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OrderControllerIT {
    
    @LocalServerPort
    private int port;
    
    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        RestAssured.basePath = "/api";
    }
    
    @Test
    @DisplayName("deve criar um pedido via API")
    void shouldCreateOrderViaAPI() {
        given()
            .contentType(ContentType.JSON)
            .body(createOrderPayload())
            .when()
                .post("/orders")
            .then()
                .statusCode(201)
                .body("id", notNullValue());
    }
}
```

---

## Padrões de Teste

### Padrão AAA (Arrange-Act-Assert)

Estrutura recomendada para **todos** os testes:

```java
@Test
@DisplayName("deve processar pagamento com sucesso")
void shouldProcessPaymentSuccessfully() {
    // ARRANGE - Preparar dados e configurações
    Payment payment = new Payment();
    payment.setAmount(BigDecimal.valueOf(100.0));
    payment.setCardNumber("1234567890123456");
    
    when(paymentGateway.process(payment))
        .thenReturn(PaymentResult.SUCCESS);

    // ACT - Executar ação a ser testada
    PaymentResult result = service.processPayment(payment);

    // ASSERT - Verificar resultado
    assertThat(result)
        .isEqualTo(PaymentResult.SUCCESS);
    
    verify(paymentGateway).process(payment);
    verify(emailService).sendConfirmation(payment);
}
```

### Testes Unitários - Service com Repository

```java
@ExtendWith(MockitoExtension.class)
class UserServiceTest {
    
    @Mock
    private UserRepository userRepository;
    
    @Mock
    private EmailService emailService;
    
    @InjectMocks
    private UserService userService;
    
    @Test
    @DisplayName("deve criar usuário e enviar email de boas-vindas")
    void shouldCreateUserAndSendWelcomeEmail() {
        // ARRANGE
        User newUser = User.builder()
            .email("john@example.com")
            .name("John Doe")
            .build();
        
        User savedUser = User.builder()
            .id(1L)
            .email("john@example.com")
            .name("John Doe")
            .build();
        
        when(userRepository.save(any(User.class)))
            .thenReturn(savedUser);
        
        // ACT
        User result = userService.createUser(newUser);
        
        // ASSERT
        assertThat(result)
            .isNotNull()
            .satisfies(user -> {
                assertThat(user.getId()).isEqualTo(1L);
                assertThat(user.getEmail()).isEqualTo("john@example.com");
            });
        
        verify(emailService, times(1))
            .sendWelcomeEmail("john@example.com");
    }
}
```

### Tratamento de Exceções

```java
@Test
@DisplayName("deve lançar exception ao buscar usuário inexistente")
void shouldThrowExceptionWhenUserNotFound() {
    // ARRANGE
    when(userRepository.findById(99L))
        .thenThrow(new UserNotFoundException("User 99 not found"));
    
    // ACT & ASSERT
    assertThatThrownBy(() -> userService.getUser(99L))
        .isInstanceOf(UserNotFoundException.class)
        .hasMessage("User 99 not found")
        .hasNoCause();
}
```

### ArgumentCaptor para Verificar Argumentos

```java
@Test
@DisplayName("deve salvar usuário com dados corretos")
void shouldSaveUserWithCorrectData() {
    // ARRANGE
    User user = new User("john@example.com");
    
    // ACT
    userService.createUser(user);
    
    // ASSERT - Capturar o argumento passado para save()
    ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
    verify(userRepository).save(userCaptor.capture());
    
    User capturedUser = userCaptor.getValue();
    assertThat(capturedUser.getEmail()).isEqualTo("john@example.com");
}
```

### Múltiplas Chamadas com Diferentes Retornos

```java
@Test
@DisplayName("deve retornar coleção vazia na primeira chamada e com dados na segunda")
void shouldReturnDifferentValuesOnMultipleCalls() {
    // ARRANGE
    when(userRepository.findAll())
        .thenReturn(Collections.emptyList())      // Primeira chamada
        .thenReturn(Arrays.asList(user1, user2)); // Segunda chamada
    
    // ACT
    List<User> firstCall = userService.getAllUsers();
    List<User> secondCall = userService.getAllUsers();
    
    // ASSERT
    assertThat(firstCall).isEmpty();
    assertThat(secondCall).hasSize(2);
}
```

### Testes de Integração - Criar Recurso

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class UserControllerIT {
    
    @LocalServerPort
    private int port;
    
    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        RestAssured.basePath = "/api";
    }
    
    @Test
    @DisplayName("deve criar usuário com sucesso")
    void shouldCreateUserSuccessfully() {
        // ARRANGE
        String createUserRequest = """
            {
                "name": "John Doe",
                "email": "john@example.com",
                "age": 30
            }
            """;
        
        // ACT & ASSERT
        given()
            .contentType(ContentType.JSON)
            .body(createUserRequest)
            .when()
                .post("/users")
            .then()
                .statusCode(201)
                .contentType(ContentType.JSON)
                .body("id", notNullValue())
                .body("name", equalTo("John Doe"))
                .body("email", equalTo("john@example.com"));
    }
}
```

### Atualizar Recurso

```java
@Test
@DisplayName("deve atualizar usuário")
void shouldUpdateUser() {
    // ARRANGE - Criar usuário
    String createRequest = """
        {"name": "John", "email": "john@example.com"}
        """;
    
    Long userId = given()
        .contentType(ContentType.JSON)
        .body(createRequest)
        .when().post("/users")
        .then().statusCode(201)
        .extract().path("id");
    
    // ARRANGE - Preparar atualização
    String updateRequest = """
        {"name": "John Updated", "email": "john.new@example.com"}
        """;
    
    // ACT & ASSERT
    given()
        .contentType(ContentType.JSON)
        .body(updateRequest)
        .when()
            .put("/users/{id}", userId)
        .then()
            .statusCode(200)
            .body("name", equalTo("John Updated"))
            .body("email", equalTo("john.new@example.com"));
}
```

### Deletar Recurso

```java
@Test
@DisplayName("deve deletar usuário")
void shouldDeleteUser() {
    // ARRANGE - Criar usuário
    Long userId = criarUsuario();
    
    // ACT & ASSERT - Deletar
    given()
        .when()
            .delete("/users/{id}", userId)
        .then()
            .statusCode(204);
    
    // Verificar que foi deletado
    given()
        .when()
            .get("/users/{id}", userId)
        .then()
            .statusCode(404);
}
```

### Validações de Entrada

```java
@Test
@DisplayName("deve validar email em formato inválido")
void shouldValidateInvalidEmail() {
    // ARRANGE
    String invalidEmailRequest = """
        {
            "name": "John Doe",
            "email": "invalid-email",
            "age": 30
        }
        """;
    
    // ACT & ASSERT
    given()
        .contentType(ContentType.JSON)
        .body(invalidEmailRequest)
        .when()
            .post("/users")
        .then()
            .statusCode(400)
            .body("error", notNullValue())
            .body("message", containsStringIgnoringCase("email"));
}
```

### Paginação

```java
@Test
@DisplayName("deve retornar usuários com paginação")
void shouldReturnUsersPaginated() {
    // ACT & ASSERT - Primeira página
    given()
        .queryParam("page", 0)
        .queryParam("size", 10)
        .queryParam("sort", "name,asc")
        .when()
            .get("/users")
        .then()
            .statusCode(200)
            .body("content", notNullValue())
            .body("totalElements", greaterThanOrEqualTo(0))
            .body("totalPages", greaterThanOrEqualTo(0))
            .body("number", equalTo(0));
}
```

### Rest Assured - BDD (Given-When-Then)

```java
@Test
void shouldGetUserById() {
    given()
        .baseUri("http://localhost:8080")
        .basePath("/api/users")
        .queryParam("id", 1)
        .header("Authorization", "Bearer token")
    .when()
        .get()
    .then()
        .statusCode(200)
        .contentType(ContentType.JSON)
        .body("name", equalTo("John"))
        .body("age", greaterThan(18));
}
```

### Mock de APIs Externas - WireMock

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ExternalApiIntegrationIT {
    
    @org.springframework.cloud.contract.wiremock.AutoConfigureWireMock(port = 8888)
    @Test
    @DisplayName("deve chamar API externa com sucesso")
    void shouldCallExternalApiSuccessfully() {
        // ARRANGE - Configurar mock da API externa
        stubFor(get(urlEqualTo("/api/external/data"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                        "id": 1,
                        "value": "test data"
                    }
                    """)));
        
        // ACT - Chamar serviço que usa API externa
        ExternalData result = externalApiService.getData();
        
        // ASSERT
        assertThat(result)
            .isNotNull()
            .satisfies(data -> {
                assertThat(data.getId()).isEqualTo(1);
                assertThat(data.getValue()).isEqualTo("test data");
            });
        
        verify(getRequestedFor(urlEqualTo("/api/external/data")));
    }
}
```

### Testes de Transações

```java
@SpringBootTest
@Transactional
class UserServiceTransactionIT {
    
    @Autowired
    private UserRepository userRepository;
    
    @Autowired
    private UserService userService;
    
    @Test
    @DisplayName("deve criar usuário em transação")
    void shouldCreateUserInTransaction() {
        // ARRANGE
        User user = new User("john@example.com");
        
        // ACT
        User saved = userService.createUser(user);
        
        // ASSERT
        assertThat(saved.getId()).isNotNull();
        // Transação sofre rollback automaticamente
    }
}
```

### Testes de Validação JSR-380

```java
@SpringBootTest
class UserValidationIT {
    
    @Autowired
    private Validator validator;
    
    @Test
    @DisplayName("deve validar constraints do usuário")
    void shouldValidateUserConstraints() {
        // ARRANGE
        User invalidUser = User.builder()
            .name("") // Vazio (violação @NotBlank)
            .email("invalid") // Formato inválido (@Email)
            .age(-5) // Negativo (@Min)
            .build();
        
        // ACT
        Set<ConstraintViolation<User>> violations = validator.validate(invalidUser);
        
        // ASSERT
        assertThat(violations).hasSize(3);
        assertThat(violations)
            .extracting(ConstraintViolation::getMessage)
            .contains("must not be blank", "must be a well-formed email address");
    }
}
```

### Testes de Performance

```java
@Test
@DisplayName("deve responder em menos de 200ms")
void shouldRespondWithinTimeLimit() {
    given()
        .when()
            .get("/users")
        .then()
            .statusCode(200)
            .time(lessThan(200L), TimeUnit.MILLISECONDS);
}
```

### Testes de Concorrência

```java
@Test
@DisplayName("deve lidar com múltiplas requisições concorrentes")
void shouldHandleConcurrentRequests() {
    // ARRANGE
    int numberOfRequests = 100;
    ExecutorService executor = Executors.newFixedThreadPool(10);
    
    // ACT
    for (int i = 0; i < numberOfRequests; i++) {
        executor.submit(() ->
            given()
                .when().get("/users")
                .then().statusCode(200)
        );
    }
    
    // ASSERT
    executor.shutdown();
    assertTrue(executor.awaitTermination(60, TimeUnit.SECONDS));
}
```

### SLA de Latência com Virtual Threads

O teste `whenFiftyConcurrentOrdersFromSameUser_thenAllAcceptedWithinSLA` valida que
50 requests concorrentes completam com `p99 ≤ 200ms` — SLA derivado do requisito de
5000 trades/segundo.

**Implementação:**
```java
@Test
@DisplayName("Dado 50 ordens concorrentes do mesmo usuário, todas devem receber 202 em < 200ms p99")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
void whenFiftyConcurrentOrdersFromSameUser_thenAllAcceptedWithinSLA() throws Exception {
    int concurrency = 50;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch  = new CountDownLatch(concurrency);
    List<Long> latenciesMs    = new ArrayList<>();

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        List<Future<Long>> futures = new ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            futures.add(executor.submit(() -> {
                startLatch.await(); // todos iniciam simultaneamente
                long start = System.currentTimeMillis();
                mockMvc.perform(post("/api/v1/orders")
                    .with(jwt().jwt(b -> b.subject(userId.toString())))
                    .contentType(APPLICATION_JSON)
                    .content(requestJson))
                    .andExpect(status().isAccepted());
                doneLatch.countDown();
                return System.currentTimeMillis() - start;
            }));
        }
        startLatch.countDown(); // larga todos ao mesmo tempo
        doneLatch.await(25, TimeUnit.SECONDS);
        for (Future<Long> f : futures) latenciesMs.add(f.get());
    }

    List<Long> sorted = latenciesMs.stream().sorted().toList();
    long p99 = sorted.get((int) Math.ceil(concurrency * 0.99) - 1);
    assertThat(p99).as("p99 deve ser ≤ 200ms").isLessThanOrEqualTo(200L);
}
```

**Cuidados ao criar testes de SLA:**
- Não inclua containers desnecessários na classe de teste (ver seção [Hierarquia de Classes Base](#hierarquia-de-classes-base))
- Use `Executors.newVirtualThreadPerTaskExecutor()` para simular o comportamento real do servidor
- Use `CountDownLatch` de arranque para garantir concorrência real (não escalonada)
- O SLA de 200ms é válido apenas com 3 containers (Command Side). Com 4+ containers, o overhead Docker aumenta a latência.

### Isolamento de Filas AMQP no `@BeforeEach`

Testes de integração que consomem mensagens RabbitMQ precisam de uma limpeza ativa da fila
no `@BeforeEach`. Apenas limpar o banco de dados não é suficiente: mensagens em trânsito
(entregues ao consumer antes do `deleteAll()`) continuam sendo processadas assincronamente
no mesmo thread pool, causando estado inconsistente no próximo teste.

**Padrão recomendado** (extraído de `KeycloakUserRegistryIntegrationTest`):

```java
@BeforeEach
void cleanDatabase() throws InterruptedException {
    // 1. Drena a fila para evitar que mensagens do teste anterior
    //    sejam processadas depois do deleteAll()
    while (rabbitTemplate.receive(QUEUE_KEYCLOAK_REG, 200) != null) {
        // descarta mensagens residuais (timeout 200ms por poll)
    }

    // 2. Aguarda consumers in-flight finalizarem sua transação atual
    Thread.sleep(300);

    // 3. Limpa o banco COM limpeza que ignora @Version
    //    Use deleteAllInBatch() (não deleteAll()) para evitar
    //    OptimisticLockingFailureException quando a consumer modifica
    //    a entidade entre o carregamento e o delete
    userRegistryRepository.deleteAll();
}
```

> ⚠️ **Por que não `deleteAll()` direto?**  
> `deleteAll()` carrega as entidades, checa `@Version` e deleta individualmente pela PK.  
> Se um consumer assíncrono modificou a entidade desde o carregamento, o Hibernate  
> lança `ObjectOptimisticLockingFailureException`.  
> Use `deleteAllInBatch()` que executa um único `DELETE FROM tabela` sem verificação de versão.

---

## Executando Testes

✅ **Todos os testes executam em Docker** - Não use Maven localmente!

### Opção 1: Usar Scripts (Recomendado)

**Windows**:
```powershell
.\build.ps1 docker-test
```

**Linux/macOS**:
```bash
make docker-test
```

### Opção 2: Docker Compose Direto

```bash
# Executar todos os testes
docker compose -f tests/docker-compose.test.yml up

# Executar e parar automaticamente após finalizar
docker compose -f tests/docker-compose.test.yml up --exit-code-from test-runner

# Ver logs detalhados
docker compose -f tests/docker-compose.test.yml logs -f test-runner

# Limpar ambiente de testes
docker compose -f tests/docker-compose.test.yml down -v
```

### Ver Resultados dos Testes

Após executar os testes, você verá no terminal:
- ✅ Testes passando
- ❌ Testes falhando (com stack trace)
- 📊 Resumo de cobertura de código

Os resultados também estarão disponíveis em:
```
target/surefire-reports/      # Resultados dos testes
target/site/jacoco/index.html # Cobertura de código
```

---

## Ferramentas e Bibliotecas

### AssertJ - Assertions Fluentes

```java
// Null checks
assertThat(result).isNull();
assertThat(result).isNotNull();

// Comparações
assertThat(amount).isEqualTo(100);
assertThat(amount).isGreaterThan(50);
assertThat(amount).isBetween(0, 100);

// Strings
assertThat(name).isEqualTo("John");
assertThat(name).startsWith("Jo");
assertThat(name).containsIgnoringCase("john");

// Coleções
assertThat(list).hasSize(3);
assertThat(list).contains("item1", "item2");
assertThat(list).containsOnly("item1", "item2");

// Objeto
assertThat(user)
    .isNotNull()
    .satisfies(u -> {
        assertThat(u.getName()).isEqualTo("John");
        assertThat(u.getAge()).isGreaterThan(18);
    });

// Extracting
assertThat(users)
    .extracting("name")
    .contains("John", "Jane");

// Exceções
assertThatThrownBy(() -> service.deleteUser(-1))
    .isInstanceOf(IllegalArgumentException.class)
    .hasMessage("ID inválido");
```

### Mockito - Anotações

```java
@ExtendWith(MockitoExtension.class)
class ServiceTest {
    
    @Mock
    private Repository repository;
    
    @Spy
    private Helper helper;
    
    @InjectMocks
    private Service service;
    
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void testWithMultipleValues(int value) {
        // ...
    }
}
```

### Mockito - Comportamentos (Stubs)

```java
// Retorno simples
when(repository.findById(1L))
    .thenReturn(Optional.of(user));

// Exceção
when(repository.save(any()))
    .thenThrow(new DatabaseException("Error"));

// Argumentos customizados
when(repository.findByName(argThat(name -> name.startsWith("J"))))
    .thenReturn(Arrays.asList(user1, user2));

// Retorno sequencial
when(repository.findAll())
    .thenReturn(list1)
    .thenReturn(list2);

// Resposta customizada
when(repository.save(any()))
    .thenAnswer(invocation -> {
        User savedUser = invocation.getArgument(0);
        savedUser.setId(1L);
        return savedUser;
    });
```

### Mockito - Verificação (Verify)

```java
// Verificar chamada
verify(repository, times(1)).save(any());

// Verificar não foi chamado
verify(repository, never()).delete(any());

// Verificar sequência
InOrder inOrder = inOrder(repository, cache);
inOrder.verify(repository).findById(1L);
inOrder.verify(cache).put(1L, user);

// Verificar argumentos
ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
verify(repository).save(captor.capture());
User savedUser = captor.getValue();
assertThat(savedUser.getName()).isEqualTo("John");
```

---

## Ambientes Docker

### Desenvolvimento (docker-compose.dev.yml)

```bash
# Iniciar
docker-compose -f docker-compose.dev.yml up

# Com hotreload automático
# - Mudanças no código são detectadas automaticamente
# - Debug ports: 5005 (order-service), 5006 (wallet-service)
# - MongoDB, PostgreSQL, Redis, RabbitMQ disponíveis

# Parar
docker-compose -f docker-compose.dev.yml down
```

### Testes (docker-compose.test.yml)

```bash
# Executar testes e parar automaticamente
docker-compose -f docker-compose.test.yml up --exit-code-from test-runner

# Ver logs dos testes
docker-compose -f docker-compose.test.yml logs test-runner

# Parar ambiente
docker-compose -f docker-compose.test.yml down
```

---

## Cobertura de Código

Relatório de cobertura com **Jacoco** (automático em containers):

```bash
# Executar testes com cobertura (automático)
docker compose -f tests/docker-compose.test.yml up

# Relatório será gerado em: target/site/jacoco/index.html
# (Após testes finalizarem no container)
```

### Configurar Mínimo de Cobertura

No `pom.xml` (root):

```xml
<plugin>
    <groupId>org.jacoco</groupId>
    <artifactId>jacoco-maven-plugin</artifactId>
    <version>0.8.10</version>
    <executions>
        <execution>
            <goals>
                <goal>prepare-agent</goal>
            </goals>
        </execution>
        <execution>
            <id>report</id>
            <phase>test</phase>
            <goals>
                <goal>report</goal>
            </goals>
        </execution>
        <execution>
            <id>jacoco-check</id>
            <goals>
                <goal>check</goal>
            </goals>
            <configuration>
                <rules>
                    <rule>
                        <element>PACKAGE</element>
                        <excludes>
                            <exclude>*Test</exclude>
                        </excludes>
                        <limits>
                            <limit>
                                <counter>LINE</counter>
                                <value>COVEREDRATIO</value>
                                <minimum>0.80</minimum>
                            </limit>
                        </limits>
                    </rule>
                </rules>
            </configuration>
        </execution>
    </executions>
</plugin>
```

---

## Debug Remoto

### VSCode

Adicionar à `.vscode/launch.json`:

```json
{
    "version": "0.2.0",
    "configurations": [
        {
            "name": "Order Service Debug",
            "type": "java",
            "name": "Debug Order Service",
            "request": "attach",
            "hostName": "localhost",
            "port": 5005,
            "preLaunchTask": "docker-dev-up"
        },
        {
            "name": "Wallet Service Debug",
            "type": "java",
            "request": "attach",
            "hostName": "localhost",
            "port": 5006
        }
    ]
}
```

### IntelliJ IDEA

1. **Run** → **Edit Configurations**
2. Clique em **+** (Add New Configuration)
3. Selecione **Remote**
4. Configure:
   - **Name**: Order Service Debug
   - **Host**: localhost
   - **Port**: 5005
5. Clique em **Debug** (atalho: Shift+F9)

---

## Checklist de Qualidade

### Para Novos Testes

- [ ] Nome descritivo em `@DisplayName`
- [ ] Padrão AAA (Arrange-Act-Assert) seguido
- [ ] Apenas um conceito testado por método
- [ ] Assertions significativas (não apenas `notNull()`)
- [ ] Mocks configurados corretamente
- [ ] Verificações (`verify()`) quando apropriado
- [ ] Testes independentes (sem dependência entre testes)
- [ ] Tempo de execução < 100ms para testes unitários
- [ ] Documentação de casos complexos
- [ ] Sem dados hardcoded que não sejam necessários
- [ ] **Routing keys de RabbitMQ referenciadas exclusivamente via `RabbitMQConfig.RK_*` ou `RabbitMQConfig.QUEUE_*`** (guard AT-02.2)

### Pontos de Entrada para Novo Desenvolvedor

```
1. README.md (raiz) - Visão geral (5 min leitura)
   ↓
2. docker/README.md - Setup com Docker
   ↓
3. .\init.ps1; .\build.ps1 docker-test - Validar
   ↓
4. docs/testing/COMPREHENSIVE_TESTING.md - Este documento
   ↓
5. apps/seu-service/README.md - Entender seu serviço
```

### Para Testes

```
1. README.md (raiz) - Overview
   ↓
2. docs/testing/COMPREHENSIVE_TESTING.md - Guia e padrões
   ↓
3. Escrever testes seguindo padrões deste documento
```

---

## Troubleshooting

### Erro: "Port 8080 already in use"

```bash
# Encontrar processo usando a porta
netstat -ano | findstr :8080

# Ou no Linux/Mac
lsof -i :8080

# Parar o processo ou usar porta diferente
docker-compose -f docker-compose.dev.yml down
```

### Erro: "Database connection failed"

```bash
# Verificar se containers estão rodando
docker-compose -f docker-compose.dev.yml ps

# Verificar logs
docker-compose -f docker-compose.dev.yml logs postgres

# Reiniciar
docker-compose -f docker-compose.dev.yml restart postgres
```

### Erro: "Mockito can't mock this class"

- Usar `@ExtendWith(MockitoExtension.class)` ou `MockitoAnnotations.openMocks(this)`
- Verificar se a classe é `final` (Mockito não consegue mockar classes finais)
- Usar `mock(Class.class)` manualmente se anotação não funcionar

### MongoDB: Pre-criação de índices e Connection Pool

O driver Java do MongoDB cresce o connection pool **lazily** (minSize=0 por padrão).
Isso causa spikes de latência na primeira carga concorrente enquanto novas conexões
são criadas (overhead: 300–900ms em Testcontainers).

A classe `MongoIndexConfig` resolve ambos os probelmas no startup:

```java
// 1. Pre-cria conexões no pool (evita crescimento lazy)
@Bean
@ConditionalOnProperty(name = "app.mongodb.enabled", matchIfMissing = true)
MongoClientSettingsBuilderCustomizer mongoConnectionPoolCustomizer() {
    return builder -> builder.applyToConnectionPoolSettings(
        pool -> pool.minSize(10).maxSize(100)
    );
}

// 2. Pre-cria coleção + índice no startup (evita criação lazy no primeiro write)
@Bean
@ConditionalOnProperty(name = "app.mongodb.enabled", matchIfMissing = true)
SmartInitializingSingleton mongoOrdersIndexInitializer(MongoTemplate mongoTemplate) {
    return () -> {
        if (!mongoTemplate.collectionExists("orders")) {
            mongoTemplate.createCollection("orders");
        }
        mongoTemplate.indexOps("orders").ensureIndex(
            new CompoundIndexDefinition(new Document("userId", 1).append("createdAt", -1))
                .named("idx_userId_createdAt")
        );
    };
}
```

> `SmartInitializingSingleton` é invocado após todos os singletons do contexto estarem
> prontos — garantindo que `MongoTemplate` está completamente configurado antes de
> `ensureIndex()` ser chamado.

### Troubleshooting: SLA test falhando após adicionar novo container

Se um teste de concorrência (SLA) começar a falhar após adicionar um novo container
Testcontainers ao ambiente:

1. **Verifique a hierarquia:** o novo container deve estar em `AbstractMongoIntegrationTest`
   (ou classe equivalente dedicada), não em `AbstractIntegrationTest`.
2. **Diagnóstico:** remova temporariamente toda a lógica de aplicação do método testado
   e veja se o p99 ainda extrapola. Se sim, a causa é Docker overhead, não código.
3. **Não ajuste o threshold de SLA** como primeira opção — isso mascara o problema.
   A correção correta é isolar containers por hierarquia de teste.
4. **Atenção ao `@ConditionalOnProperty`:** se os beans do conteúdo novo não estiverem
   condicionados, eles tentarão se conectar a infra inexistente e falharão no startup.

### Mensagens não chegam na DLQ

Se mensagens rejeitadas (NACK sem requeue) não aparecem na fila `order.dead-letter`,
verifique a seguinte cadeia completa:

1. **Fila com `x-dead-letter-exchange`?**  
   Cada fila consumida precisa declarar:
   ```java
   args.put("x-dead-letter-exchange", "vibranium.dlq");
   args.put("x-dead-letter-routing-key", "order.dead-letter");
   ```

2. **Binding no DLX declarado explicitamente?**  
   O exchange `vibranium.dlq` é do tipo `direct` — ele **não** roteia para `order.dead-letter`
   automaticamente. É obrigatório declarar o binding:
   ```java
   // Em RabbitMQConfig.java
   @Bean
   public Binding deadLetterBinding() {
       // Sem este binding, mensagens mortas são descartadas silenciosamente (unroutable)
       return BindingBuilder
               .bind(deadLetterQueue())         // order.dead-letter
               .to(deadLetterExchange())         // vibranium.dlq
               .with(ROUTING_KEY_DEAD_LETTER); // "order.dead-letter"
   }
   ```

3. **Retry esgotado antes de mandar para DLX?**  
   O Spring AMQP só move para DLX após esgotar as tentativas em `spring.rabbitmq.listener.simple.retry.max-attempts`.
   Para testar em ambiente local, reduza para `max-attempts: 1`.

4. **Inspeção via Management UI:**  
   Acesse `http://localhost:15672` → Queues → `order.dead-letter` → Get messages.

### Testes com `@Disabled` (Toxiproxy / resiliência de infraestrutura)

Alguns testes de integração exigem injeção de falhas de rede (latência, timeout, partição)
que não podem ser simuladas com o container Redis / RabbitMQ compartilhado da suite.
Esses testes são marcados com `@Disabled` e rastreados como dívida técnica.

**Exemplo atual:**

```
apps/order-service/src/test/java/.../integration/MatchEngineRedisIntegrationTest.java
  → whenRedisUnavailable_thenOrderIsCancelled()  @Disabled
```

**Razão:** Simular indisponibilidade do Redis requer um proxy de rede intermediário
([Toxiproxy](https://github.com/Shopify/toxiproxy)) para introduzir falhas sem derrubar
ios outros containers que usam o mesmo Redis.

**Como habilitar no futuro:**

1. Adicionar `ghcr.io/shopify/toxiproxy` ao `docker-compose-test.yml` como proxy na frente do Redis.
2. Usar `toxiproxy-java` para criar proxies programáticos dentro do teste.
3. Remover `@Disabled` e implementar o corpo do teste com `ToxiproxyContainer`:
   ```java
   // Exemplo (ainda não implementado)
   @Test
   void whenRedisUnavailable_thenOrderIsCancelled() {
       // 1. Introduzir latência de 5s no proxy Redis
       redisProxy.toxics().latency("redis-down", ToxicDirection.DOWNSTREAM, 5000);
       // 2. Submeter ordem
       // 3. Aguardar compensação (CANCELLED)
       // 4. Remover toxic
       redisProxy.toxics().get("redis-down").remove();
   }
   ```

> **Política:** Testes `@Disabled` não devem permanecer sem issue de rastreamento.
> Inclua sempre o motivo técnico no parâmetro da anotação e abra uma issue para
> a sprint de resiliência.

---

## Integração CI/CD

### GitHub Actions (`.github/workflows/test.yml`)

```yaml
name: Tests

on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    
    services:
      postgres:
        image: postgres:16-alpine
        env:
          POSTGRES_PASSWORD: postgres
        options: >-
          --health-cmd pg_isready
          --health-interval 10s
          --health-timeout 5s
          --health-retries 5

    steps:
      - uses: actions/checkout@v3
      - uses: docker/setup-buildx-action@v2
      
      - name: Run tests in containers
        run: |
          docker compose -f tests/docker-compose.test.yml up --abort-on-container-exit
          
      - name: Upload coverage
        uses: codecov/codecov-action@v3
        with:
          files: ./target/site/jacoco/jacoco.xml
```

---

## Testes de CDC — Debezium Outbox

Esta seção cobre dois padrões complementares do Outbox Pattern no `order-service`:

### AT-01.1 — Refatoração de Transacionalidade (Eliminação de Dual Write)

O `OrderCommandServiceTest` valida, em camada unitária pura (Mockito, sem containers), que
`placeOrder()` persiste **duas entradas atomicamente** em `tb_order_outbox` dentro da mesma
`@Transactional`, eliminando o anti-pattern de `Thread.ofVirtual()` + `RabbitTemplate` fora da transação:

| Cenário | Resultado esperado |
|---------|-------------------|
| Happy path BUY/SELL | `orderRepository.save` × 1, `outboxRepository.save` × 2 |
| Jackson falha no `ReserveFundsCommand` | `IllegalStateException`, nenhum `outboxRepository.save` |
| Jackson falha no `OrderReceivedEvent` | `IllegalStateException`, `outboxRepository.save` × 1 (antes da falha) |
| Construtor sem `RabbitTemplate` | Instanciável com 4 parâmetros; `parameterCount == 4` por reflexão |
| `keycloakId` não registrado | `UserNotRegisteredException`, nenhum save |

Schema das duas entradas no outbox (conforme [Debezium Outbox Event Router](https://debezium.io/documentation/reference/3.5/transformations/outbox-event-router)):

```
aggregate_type = "Order"
aggregate_id   = orderId
event_type     = "ReserveFundsCommand"  |  "OrderReceivedEvent"
exchange       = vibranium.commands     |  vibranium.events
routing_key    = wallet.commands.reserve-funds  |  order.events.order-received
payload        = JSON serializado do comando/evento
```

### Outbox Publisher — Relay por Scheduler

O `OrderOutboxIntegrationTest` (Testcontainers — PostgreSQL + RabbitMQ) valida o relay pelo
`OrderOutboxPublisherService` (`@Scheduled`). Após a refatoração AT-01.1, cada `placeOrder()`
gera **2 entradas** no outbox, e os testes de integração foram atualizados para refletir isso:

```java
// placeOrder() único → 2 mensagens no outbox
assertThat(outboxRepository.findAll()).hasSize(2);
assertThat(eventTypes).containsExactlyInAnyOrder("ReserveFundsCommand", "OrderReceivedEvent");

// 2 usuários distintos → 4 mensagens no outbox
assertThat(outboxRepository.findAll()).hasSize(4);
```

O `OutboxPublisherIntegrationTest` valida o caminho completo do evento desde a inserção na tabela `outbox_message` até a chegada na fila RabbitMQ via Debezium CDC. Esta seção documenta os padrões e armadilhas encontrados.

### Por que um contexto Spring separado

O `DebeziumOutboxEngine` e o `OutboxPublisherService` são protegidos por `@ConditionalOnProperty(name = "app.outbox.debezium.enabled", havingValue = "true", matchIfMissing = false)`. Isso garante que os beans **não são carregados** em testes `@DataJpaTest` ou `@SpringBootTest` convencionais que não precisam do broker RabbitMQ.

Para os testes que precisam do relay completo, um contexto isolado é ativado via:

```java
@SpringBootTest
@TestPropertySource(properties = "app.outbox.debezium.enabled=true")
class OutboxPublisherIntegrationTest extends AbstractIntegrationTest { ... }
```

### Padrão: aguardar mensagem na fila (Awaitility)

O Debezium é assíncrono por natureza. Nunca use `Thread.sleep()` diretamente — use Awaitility para fazer polling do RabbitMQ:

```java
import static org.awaitility.Awaitility.await;
import java.util.concurrent.TimeUnit;

@Test
@DisplayName("Deve publicar FundsReservedEvent na exchange vibranium.events")
void shouldPublishFundsReservedEvent() {
    // Arrange — inserção direta simula WalletService
    UUID eventId  = UUID.randomUUID();
    UUID walletId = UUID.randomUUID();
    jdbcTemplate.update("""
            INSERT INTO outbox_message (id, event_type, aggregate_id, payload,
                                        created_at, processed)
            VALUES (?, ?, ?, ?, ?, false)
            """,
            eventId, "FundsReservedEvent", walletId.toString(),
            "{\"amount\":\"100.00\"}", Timestamp.from(Instant.now()));

    // Act + Assert — Debezium entrega em até 10 s
    await().atMost(10, TimeUnit.SECONDS)
           .pollInterval(200, TimeUnit.MILLISECONDS)
           .untilAsserted(() -> {
               Message msg = rabbitTemplate.receive("test.outbox.funds-reserved", 100);
               assertThat(msg).isNotNull();
               assertThat(msg.getMessageProperties().getMessageId())
                   .isEqualTo(eventId.toString());
           });

    // Idempotência: processed=true no banco
    assertThat(outboxRepository.findById(eventId).orElseThrow().isProcessed()).isTrue();
}
```

**Por que 10 s e não 5 s?** Quando o suite completo roda, outros testes antes deste criam registros em `outbox_message`. O `processExistingPendingMessages()` processa esse backlog na inicialização do contexto Debezium. O overhead de processar dezenas de eventos pendentes pode levar 3–5 s a mais — daí a margem de 10 s para evitar falsos negativos.

### Padrão: declarar filas de teste duráveis

`rabbitTemplate.receive()` usa `basic.get` (pull síncrono). Filas `autoDelete=true` são deletadas assim que não há consumers ativos — ou seja, antes de o `receive()` chegar. Use filas **duráveis e não-auto-delete** nos testes:

```java
private void declareAndBindQueue(String queueName, String routingKey) {
    // autoDelete=false, durable=true — obrigatório para rabbitTemplate.receive()
    Queue queue = new Queue(queueName, /*durable=*/true, /*exclusive=*/false, /*autoDelete=*/false);
    rabbitAdmin.declareQueue(queue);

    // Declara explicitamente o exchange (pode não existir ainda se o contexto for novo)
    rabbitAdmin.declareExchange(new TopicExchange("vibranium.events", true, false));

    Binding binding = BindingBuilder.bind(queue)
            .to(new TopicExchange("vibranium.events"))
            .with(routingKey);
    rabbitAdmin.declareBinding(binding);
}
```

### Padrão: slot de replicação deve estar ativo antes dos testes

O `DebeziumOutboxEngine` aguarda o slot ficar ativo **antes** de devolver o controle ao Spring (`SmartLifecycle.start()`). Sem essa barreira, INSERTs feitos imediatamente após o contexto subir caem numa janela cega onde o Debezium ainda não escuta o WAL.

Se você implementar outro serviço com Debezium Embedded, copie o padrão `awaitSlotActive()` para o seu `start()`:

```java
@Override
public void start() {
    // ... cria e submete o DebeziumEngine ...
    executor.execute(engine);

    // BARREIRA OBRIGATÓRIA — garante que o WAL está sendo monitorado
    awaitSlotActive(properties.debezium().slotName());

    // Só então processa backlog e devolve o controle
    processExistingPendingMessages();
    running = true;
}
```

### Padrão: isolamento de testes de carga

Testes de carga (500+ registros) **devem ser os últimos** da classe. Se rodarem antes dos testes de verificação unitários, o Debezium estará processando um backlog volumoso exatamente quando o teste de funcionalidade espera uma entrega rápida:

```java
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)   // ← obrigatório na classe
class OutboxPublisherIntegrationTest {

    @Test @Order(1) void shouldPublishFundsReservedEvent()     { ... }
    @Test @Order(2) void shouldPublishFundsFailedEvent()       { ... }
    @Test @Order(3) void shouldPublishFundsSettledEvent()      { ... }
    @Test @Order(4) void shouldNotPublishSameMessageTwice()    { ... }
    @Test @Order(5) void shouldHandle500ConcurrentMessages()   { ... }  // ← SEMPRE ÚLTIMO
}
```

**Alternativa para suites muito grandes:** mover testes de carga para uma classe separada anotada com `@Tag("load")` e excluí-los do ciclo `mvn test` padrão:

```xml
<!-- pom.xml / maven-surefire-plugin -->
<configuration>
    <excludedGroups>load</excludedGroups>   <!-- roda apenas na pipeline de perf -->
</configuration>
```

### PostgreSQL: configuração mínima para Debezium em testes

```java
// AbstractIntegrationTest.java
static final PostgreSQLContainer<?> POSTGRES =
    new PostgreSQLContainer<>("postgres:15-alpine")
        .withCommand(
            "postgres",
            "-c", "wal_level=logical",          // obrigatório para CDC
            "-c", "max_replication_slots=10",    // 1 por instância do engine
            "-c", "max_wal_senders=10"
        );
```

As propriedades de conexão do Debezium são injetadas no contexto de teste via `@DynamicPropertySource`:

```java
@DynamicPropertySource
static void debeziumProperties(DynamicPropertyRegistry registry) {
    registry.add("app.outbox.debezium.db-host",     POSTGRES::getHost);
    registry.add("app.outbox.debezium.db-port",     () -> String.valueOf(POSTGRES.getMappedPort(5432)));
    registry.add("app.outbox.debezium.db-name",     POSTGRES::getDatabaseName);
    registry.add("app.outbox.debezium.db-user",     POSTGRES::getUsername);
    registry.add("app.outbox.debezium.db-password", POSTGRES::getPassword);
}
```

---

## Partial Fill — Requeue Atômico e Idempotência por eventId (US-002)

Esta seção documenta os padrões de teste para a US-002: **requeue da ordem residual no livro de ofertas após match parcial** e **idempotência por `eventId`**.

### Cenários cobertos

| Cenário | fillType | O que verifica |
|---|---|---|
| BID 100 vs ASK 40 | `PARTIAL_BID` | BID residual 60 em `vibranium:bids`; Order→`PARTIAL` |
| SELL 100 vs BID 40 | `PARTIAL_ASK` | ASK residual 60 em `vibranium:asks`; Order→`PARTIAL` |
| 3×BID-10 vs ASK-30 | `FULL` ×3 | Livro zerado; todas as 3 ordens `FILLED` |
| Mesmo `eventId` entregue 2× | — | 2ª entrega descartada; estado não muda |

### Padrão: verificar residual no Sorted Set

Apsaós enviar o evento pelo RabbitMQ, use `StringRedisTemplate` para inspecionar diretamente o Sorted Set Redis e validar a quantidade residual:

```java
@Test
@DisplayName("PARTIAL_BID: BID 100 vs ASK 40 → BID residual 60 em bids, Order→PARTIAL")
void whenBidPartiallyMatchesAsk_thenBidResidualRemainsInBook() {
    // Arrange — ASK de 40 VIB pré-existente
    redisTemplate.opsForZSet().add(asksKey,
            buildRedisValue(askId, userId, walletId, new BigDecimal("40"), correlId),
            priceToScore(new BigDecimal("500.00")));

    // Act — BID de 100 VIB
    rabbitTemplate.convertAndSend("vibranium.events", FUNDS_RESERVED_RK, event);

    // Assert: Order no PostgreSQL
    await().atMost(8, TimeUnit.SECONDS).untilAsserted(() -> {
        Order updated = orderRepository.findById(bidOrderId).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(OrderStatus.PARTIAL);
        assertThat(updated.getRemainingAmount())
                .isEqualByComparingTo(new BigDecimal("60.00000000"));
    });

    // Assert: Redis — ASK consumido; BID residual no livro
    await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
        assertThat(redisTemplate.opsForZSet().zCard(asksKey)).isZero();
        assertThat(redisTemplate.opsForZSet().zCard(bidsKey)).isEqualTo(1L);

        String bidValue = redisTemplate.opsForZSet()
                .rangeByScore(bidsKey, priceToScore(price), priceToScore(price))
                .stream().findFirst().orElse(null);

        assertThat(bidValue).isNotNull();
        // Extrai campo qty (posicao 3 no pipe-delimited)
        assertThat(new BigDecimal(bidValue.split("\\|")[3]))
                .isEqualByComparingTo(new BigDecimal("60.00000000"));
        // orderId deve ser preservado
        assertThat(UUID.fromString(bidValue.split("\\|")[0])).isEqualTo(bidOrderId);
    });
}
```

**Por que `await()` separado para Redis?**  
O consumidor async persiste o `Order` no PostgreSQL **e depois** retorna do listener.  
O requeue Redis já aconteceu dentro do Lua (antes do commit JPA), mas due ao scheduling  
de threads virtuais pode chegar ao Assert antes do Awaitility do Postgres terminar.  
Usar dois `await()` sequenciais é mais legível e evita assertions compostas frutáveis.

### Por que `MatchResult.remainingCounterpartQty()` != qty residual no Redis

Os valores são intencionalmente diferentes para os casos `PARTIAL_BID` e `PARTIAL_ASK`:

| fillType | Quem tem residual | `remainingCounterpartQty` | qty no Sorted Set |
|---|---|---|---|
| `PARTIAL_ASK` | contraparte (ASK) | positivo (ex: 7) | idem — ASK reinserido com nova qty |
| `PARTIAL_BID` | ingressante (BID) | ZERO | positivo (ex: 60) — **BID é a ordem ingressante** |

Para `PARTIAL_BID`, a contraparte (ASK) foi totalmente consumida — `remainingCounterpartQty = 0`.
O residual do **BID ingressante** nunca está em `remainingCounterpartQty`; ele está em
`Order.remainingAmount` (banco) e no Sorted Set `vibranium:bids` (Redis).

### Padrão: idempotência por `eventId`

Re-entrega controlada: constroi o evento **com `eventId` fixo** e envia duas vezes.
O segundo envio deve ser descartado sem alterar o estado observável.

```java
@Test
@DisplayName("Idempotência: mesmo eventId entregue 2× → processado somente uma vez")
void whenDuplicateFundsReservedEvent_thenProcessedOnlyOnce() {
    // ASK + Order pré-existente ...

    // Mesmo objeto event (mesmo eventId UUID)
    FundsReservedEvent event = FundsReservedEvent.of(correlId, orderId, walletId, ...);

    // 1ª entrega
    rabbitTemplate.convertAndSend("vibranium.events", FUNDS_RESERVED_RK, event);
    await().atMost(8, TimeUnit.SECONDS)
           .untilAsserted(() -> assertThat(
               orderRepository.findById(orderId).orElseThrow().getStatus())
               .isEqualTo(OrderStatus.FILLED));

    // 2ª entrega — deve ser descartada
    rabbitTemplate.convertAndSend("vibranium.events", FUNDS_RESERVED_RK, event);

    // Esperar 2s e confirmar que não houve segunda execução
    await().during(2, TimeUnit.SECONDS)
           .atMost(4, TimeUnit.SECONDS)
           .untilAsserted(() -> {
               assertThat(orderRepository.findById(orderId).orElseThrow().getStatus())
                   .isEqualTo(OrderStatus.FILLED); // não mudou
               assertThat(redisTemplate.opsForZSet().zCard(asksKey)).isZero();
           });
}
```

**Armadilha:** `await().during(X).atMost(Y)` falha se a condição não for verdadeira  
durante TODO o período `X`. Use-o apenas para afirmar **ausência de mudança de estado**  
(invariante temporal), não para aguardar algo acontecer.

### Padrão: múltiplos partial fills convergindo o livro

3 ordens BUY de 10 VIB cada contra 1 ASK de 30 VIB. Cada BID resultam em `PARTIAL_ASK`
(o ASK perde 10 por ciclo) até o ASK ser totalmente consumido na 3ª execução (`FULL`).

```java
// Envio SEQUENCIAL (não concorrente), para que cada match veja o residual
// corretamente atualizado no Redis antes do próximo evento ser processado
for (int i = 0; i < 3; i++) {
    rabbitTemplate.convertAndSend("vibranium.events", FUNDS_RESERVED_RK, events.get(i));
}

// Aguarda TODAS as ordens ficarem FILLED
await().atMost(15, TimeUnit.SECONDS)
       .untilAsserted(() -> {
           for (UUID oid : orderIds) {
               assertThat(orderRepository.findById(oid).orElseThrow().getStatus())
                   .isEqualTo(OrderStatus.FILLED);
           }
       });

// Verifica invariante final do livro
assertThat(redisTemplate.opsForZSet().zCard(asksKey)).isZero();
assertThat(redisTemplate.opsForZSet().zCard(bidsKey)).isZero();
```

**Por que usar `concurrency = "5"` com envio sequencial pode causar interleaving?**  
O listener tem 5 threads. Se você enviar 3 eventos muito rapidamente, threads diferentes  
podem tentar ler o mesmo ASK residual. O Lua é atômico, mas a assertão de qty residual  
pode ser lida entre dois matches parciais. Para o teste de livro convergindo, envie  
sequencialmente e use o `await()` do `FILLED` como barreira implícita.

---

## Invariantes de Domínio Wallet — Encapsulamento de Agregado (US-005)

Esta seção documenta os padrões de teste para a US-005: **encapsulamento das invariantes de negócio no agregado `Wallet`** — eliminando setters públicos e garantindo que toda mutação de saldo passe pelos métodos de domínio.

### Contexto do problema

Antes da US-005, `WalletService.settleFunds()` manipulava saldos diretamente via setters:

```java
// ❌ Anti-padrão: qualquer camada pode bypassar as regras de negócio
buyer.setBrlLocked(buyer.getBrlLocked().subtract(totalBrl));
buyer.setVibAvailable(buyer.getVibAvailable().add(cmd.matchAmount()));
```

Isso permitia que código fora do domínio contornasse as invariantes (ex: saldo negativo).

### Solução: métodos de comportamento no agregado

```java
// ✅ Correto: o agregado valida suas próprias pré-condições
buyer.applyBuySettlement(totalBrl, cmd.matchAmount());   // lança InsufficientFundsException se brlLocked < totalBrl
seller.applySellSettlement(cmd.matchAmount(), totalBrl); // lança InsufficientFundsException se vibLocked < amount
```

### Invariantes declaradas em `Wallet`

```
- Nenhum saldo pode ser negativo
- Locked nunca pode exceder o available anterior à reserva
- Toda operação deve preservar consistência interna
- Wallet é aggregate root e controla seu próprio estado
- Wallet utiliza optimistic locking via @Version
```

---

### Padrão: teste unitário de domínio puro (sem Spring)

Testes em `WalletDomainTest` validam o agregado de forma isolada — sem container, sem banco — seguindo o ciclo TDD RED → GREEN.

```java
@DisplayName("WalletDomain — Invariantes do agregado Wallet")
class WalletDomainTest {

    // Helper: cria estado pós-reserva usando o próprio domínio (sem setter)
    private Wallet walletWithBrlLocked(BigDecimal brlLocked) {
        Wallet w = Wallet.create(UUID.randomUUID(), brlLocked, BigDecimal.ZERO);
        w.reserveFunds(AssetType.BRL, brlLocked);
        return w;
    }

    @Test
    @DisplayName("reserveFunds: com saldo suficiente reduz available e aumenta locked")
    void reserveFunds_withSufficientBalance_reducesAvailableAndIncreasesLocked() {
        Wallet wallet = Wallet.create(UUID.randomUUID(), new BigDecimal("500.00"), BigDecimal.ZERO);

        wallet.reserveFunds(AssetType.BRL, new BigDecimal("150.00"));

        assertThat(wallet.getBrlAvailable()).isEqualByComparingTo("350.00");
        assertThat(wallet.getBrlLocked()).isEqualByComparingTo("150.00");
    }

    @Test
    @DisplayName("reserveFunds: com saldo insuficiente lança exceção e não altera estado")
    void reserveFunds_withInsufficientBalance_throwsInsufficientFundsException() {
        Wallet wallet = Wallet.create(UUID.randomUUID(), new BigDecimal("100.00"), BigDecimal.ZERO);

        assertThatThrownBy(() -> wallet.reserveFunds(AssetType.BRL, new BigDecimal("200.00")))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessageContaining("saldo BRL insuficiente");

        // Invariante: estado não é modificado após exceção
        assertThat(wallet.getBrlAvailable()).isEqualByComparingTo("100.00");
        assertThat(wallet.getBrlLocked()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
```

**Por que usar factory helper em vez de setters no teste?**
Com os setters removidos, a única forma de construir um estado pré-reserva é chamar `reserveFunds()` — o que é semanticamente correto e garante que o próprio teste respeite as invariantes do agregado.

---

### Padrão: `applyBuySettlement` — liquidação do comprador

```java
@Test
@DisplayName("applyBuySettlement: com BRL locked válido transfere corretamente")
void applyBuySettlement_withValidLock_transfersCorrectly() {
    // Arrange — comprador com R$200 bloqueados (via domínio)
    Wallet buyer = walletWithBrlLocked(new BigDecimal("200.00"));

    // Act — match de 10 VIB a R$20 = R$200 total
    buyer.applyBuySettlement(new BigDecimal("200.00"), new BigDecimal("10.00"));

    // Assert
    assertThat(buyer.getBrlLocked()).isEqualByComparingTo("0.00");
    assertThat(buyer.getVibAvailable()).isEqualByComparingTo("10.00");
    assertThat(buyer.getBrlAvailable()).isEqualByComparingTo("0.00"); // não muda para comprador
}

@Test
@DisplayName("applyBuySettlement: BRL locked insuficiente lança exceção sem alterar estado")
void applyBuySettlement_withInsufficientLock_throwsException() {
    Wallet buyer = walletWithBrlLocked(new BigDecimal("50.00"));

    assertThatThrownBy(() -> buyer.applyBuySettlement(new BigDecimal("200.00"), new BigDecimal("10.00")))
            .isInstanceOf(InsufficientFundsException.class)
            .hasMessageContaining("BRL locked insuficiente");

    // Estado preservado
    assertThat(buyer.getBrlLocked()).isEqualByComparingTo("50.00");
    assertThat(buyer.getVibAvailable()).isEqualByComparingTo("0.00");
}
```

---

### Padrão: `applySellSettlement` — liquidação do vendedor

```java
@Test
@DisplayName("applySellSettlement: com VIB locked válido transfere corretamente")
void applySellSettlement_withValidLock_transfersCorrectly() {
    Wallet seller = walletWithVibLocked(new BigDecimal("10.00"));

    seller.applySellSettlement(new BigDecimal("10.00"), new BigDecimal("200.00"));

    assertThat(seller.getVibLocked()).isEqualByComparingTo("0.00");
    assertThat(seller.getBrlAvailable()).isEqualByComparingTo("200.00");
    assertThat(seller.getVibAvailable()).isEqualByComparingTo("0.00"); // não muda para vendedor
}

@Test
@DisplayName("applySellSettlement: VIB locked insuficiente lança exceção")
void applySellSettlement_releasesBelowZero_throwsException() {
    Wallet seller = walletWithVibLocked(new BigDecimal("5.00"));

    assertThatThrownBy(() -> seller.applySellSettlement(new BigDecimal("10.00"), new BigDecimal("200.00")))
            .isInstanceOf(InsufficientFundsException.class)
            .hasMessageContaining("VIB locked insuficiente");

    assertThat(seller.getVibLocked()).isEqualByComparingTo("5.00");
}
```

---

### Padrão: setup de integração com domínio (sem setters)

Testes de integração que precisam construir um estado "pós-reserva" devem usar `reserveFunds()`:

```java
@BeforeEach
void setupWallets() {
    // ✅ Cria e reserva via domínio — sem setBrlLocked()
    buyerWallet = Wallet.create(UUID.randomUUID(), new BigDecimal("200.00"), BigDecimal.ZERO);
    buyerWallet.reserveFunds(AssetType.BRL, new BigDecimal("200.00"));
    buyerWallet = walletRepository.save(buyerWallet);

    sellerWallet = Wallet.create(UUID.randomUUID(), BigDecimal.ZERO, new BigDecimal("10"));
    sellerWallet.reserveFunds(AssetType.VIBRANIUM, new BigDecimal("10"));
    sellerWallet = walletRepository.save(sellerWallet);
}
```

**Por que não usar setters no setup de testes de integração?**
Os setters foram removidos (US-005). Além disso, usar `reserveFunds()` no setup cria um estado que reflete o ciclo real da Saga (a reserva sempre precede o settlement), tornando os testes mais fiéis ao cenário de produção.

---

### Artefatos gerados pela US-005

| Artefato | Tipo | Descrição |
|---|---|---|
| `WalletDomainTest` | Novo | 6 testes unitários de domínio puro (sem Spring) |
| `Wallet.applyBuySettlement()` | Novo | Liquida trade do lado comprador com validação |
| `Wallet.applySellSettlement()` | Novo | Liquida trade do lado vendedor com validação |
| `Wallet.@Version` | Novo | Optimistic locking JPA |
| `V4__add_wallet_version.sql` | Novo | Flyway migration para coluna `version` |
| `WalletService.settleFunds()` | Refatorado | Substituídos 5 setter/validações por 2 chamadas de domínio |
| `WalletSettleFundsIntegrationTest` | Refatorado | Setup usa `reserveFunds()` em vez de setters removidos |
| Setters `setBrlLocked` etc. | Removido | 4 setters públicos de saldo eliminados |

---

## Routing Key Literal Guard — Padronização Arquitetural (AT-02.2)

### Contexto

Strings literais de routing key (ex: `"order.events.order-received"`) espalhadas pelo código
introduzem risco silencioso de **drift de configuração**: uma alteração em `RabbitMQConfig`
não será propagada para os locais que usam strings hardcoded, causando falhas em runtime
invisíveis em tempo de compilação.

### Regra Arquitetural

> **Nenhum arquivo `.java` fora de `RabbitMQConfig.java` pode conter a substring
> `"order.events."`** como string literal. Toda referência a routing key deve usar as
> constantes estáticas declaradas em `RabbitMQConfig`.

### Constantes Autorizadas

| Constante | Valor | Uso |
|---|---|---|
| `RabbitMQConfig.RK_ORDER_RECEIVED` | `order.events.order-received` | Publicação do `OrderReceivedEvent` |
| `RabbitMQConfig.RK_MATCH_EXECUTED` | `order.events.match-executed` | Publicação do `MatchExecutedEvent` |
| `RabbitMQConfig.RK_ORDER_ADDED_TO_BOOK` | `order.events.order-added-to-book` | Publicação do `OrderAddedToBookEvent` |
| `RabbitMQConfig.RK_ORDER_CANCELLED` | `order.events.order-cancelled` | Publicação do `OrderCancelledEvent` |
| `RabbitMQConfig.QUEUE_FUNDS_RESERVED` | `order.events.funds-reserved` | Fila consumida pelo Command Side |
| `RabbitMQConfig.QUEUE_FUNDS_FAILED` | `order.events.funds-failed` | Fila consumida pelo Command Side |

### Teste de Guarda: `RoutingKeyLiteralTest`

Localização: `apps/order-service/src/test/java/.../architecture/RoutingKeyLiteralTest.java`

O teste percorre todos os arquivos `.java` sob `src/` e falha se qualquer arquivo
**(exceto `RabbitMQConfig.java` e ele próprio)** contiver a substring `"order.events."`.

```java
@Test
@DisplayName("Nenhum arquivo .java fora de RabbitMQConfig deve conter literal 'order.events.'")
void noRoutingKeyLiteralsOutsideRabbitMQConfig() throws IOException {
    Path srcRoot = Paths.get("src"); // relativo ao diretório Maven
    List<String> violations = Files.walk(srcRoot)
            .filter(p -> p.toString().endsWith(".java"))
            .filter(p -> EXCLUDED_FILES.stream()
                    .noneMatch(e -> p.getFileName().toString().equals(e)))
            .filter(p -> Files.readString(p).contains("order.events."))
            .map(Path::toString)
            .collect(toList());

    assertThat(violations).isEmpty();
}
```

### TDD: Ciclo RED → GREEN

| Fase | Ação | Resultado |
|---|---|---|
| **RED** | Criar `RoutingKeyLiteralTest` com 5 arquivos em violação | Teste falha, listando os arquivos |
| **GREEN** | Substituir todas as strings por constantes `RabbitMQConfig.*` | Teste passa, `BUILD SUCCESS` |

### Arquivos Corrigidos (AT-02.2)

| Arquivo | Violações | Substituição |
|---|---|---|
| `OrderQueryControllerTest` | 10 | `RK_ORDER_RECEIVED`, `RK_MATCH_EXECUTED`, `RK_ORDER_CANCELLED` |
| `OrderIdempotencyIntegrationTest` | 7 | `QUEUE_FUNDS_RESERVED`, `QUEUE_FUNDS_FAILED` |
| `OrderEventProjectionConsumer` | 1 (Javadoc) | `{@link RabbitMQConfig#QUEUE_FUNDS_RESERVED}` |
| `MatchEngineRedisIntegrationTest` | 1 (comentário) | `RabbitMQConfig.QUEUE_FUNDS_RESERVED` |
| `OrderOutboxResilienceIntegrationTest` | 1 (comentário) | `RabbitMQConfig.RK_ORDER_RECEIVED` |

### Como Executar

```bash
# Executa apenas o guard (rápido — sem infraestrutura)
mvn test -pl apps/order-service -Dtest=RoutingKeyLiteralTest

# Valida manualmente (deve retornar zero resultados)
grep -r "order.events." apps/order-service/src \\
  --include="*.java" \\
  | grep -v "RabbitMQConfig.java" \\
  | grep -v "RoutingKeyLiteralTest.java"
```

---

## Referências

- [JUnit 5 Documentation](https://junit.org/junit5/docs/current/user-guide/)
- [Mockito Documentation](https://javadoc.io/doc/org.mockito/mockito-core/latest/org/mockito/Mockito.html)
- [AssertJ Documentation](https://assertj.github.io/assertj-core-features-highlight.html)
- [REST Assured Documentation](https://rest-assured.io/)
- [TestContainers Documentation](https://www.testcontainers.org/)
- [Spring Boot Testing Guide](https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.testing)

---

**Status**: ✅ Consolidado e Completo  
**Última atualização**: 1 de março de 2026

> **Mudanças recentes:**
> - **AT-02.2**: Routing Key Literal Guard — eliminadas todas as strings literais de routing key fora de `RabbitMQConfig`. Adicionado `RoutingKeyLiteralTest` (guarda arquitetural estático). 5 arquivos refatorados. Adicionada seção 18 neste guia.
> - **AT-01.1**: Refatoração de Transacionalidade — eliminação do Dual Write (`Thread.ofVirtual` + `RabbitTemplate`) em `OrderCommandService.placeOrder()`. `OrderReceivedEvent` agora persiste via Outbox na mesma transação. Adicionado `OrderCommandServiceTest` (6 testes unitários TDD) e atualizado `OrderOutboxIntegrationTest` (2 entradas por `placeOrder`).
> - **US-005**: Adicionada seção 16 — Invariantes de Domínio Wallet (encapsulamento de agregado, remoção de setters, `applyBuySettlement`, `applySellSettlement`, `@Version`)
> - **US-005**: Documentados 4 padrões de teste unitário de domínio puro (`WalletDomainTest`) e padrão de setup de integração sem setters
> - Adicionada hierarquia `AbstractMongoIntegrationTest` (Query Side) separada de `AbstractIntegrationTest` (Command Side) — US-003
> - Documentado problema de SLA com 4 containers e solução via isolamento de hierarquia
> - Adicionada seção sobre `@ConditionalOnProperty` para beans MongoDB condicionais
> - Adicionado padrão de pre-criação de índices e connection pool MongoDB
> - Adicionados padrões de testes de CDC Debezium (seção [Testes de CDC — Debezium Outbox](#testes-de-cdc--debezium-outbox))
> - Adicionados padrões de Partial Fill e Idempotência por eventId (seção 15 — US-002)
