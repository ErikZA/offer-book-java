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
14. [Referências](#referências)

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

## Referências

- [JUnit 5 Documentation](https://junit.org/junit5/docs/current/user-guide/)
- [Mockito Documentation](https://javadoc.io/doc/org.mockito/mockito-core/latest/org/mockito/Mockito.html)
- [AssertJ Documentation](https://assertj.github.io/assertj-core-features-highlight.html)
- [REST Assured Documentation](https://rest-assured.io/)
- [TestContainers Documentation](https://www.testcontainers.org/)
- [Spring Boot Testing Guide](https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.testing)

---

**Status**: ✅ Consolidado e Completo  
**Última atualização**: 28 de fevereiro de 2026

> **Mudanças recentes (US-003):**
> - Adicionada hierarquia `AbstractMongoIntegrationTest` (Query Side) separada de `AbstractIntegrationTest` (Command Side)
> - Documentado problema de SLA com 4 containers e solução via isolamento de hierarquia
> - Adicionada seção sobre `@ConditionalOnProperty` para beans MongoDB condicionais
> - Adicionado padrão de pre-criação de índices e connection pool MongoDB
