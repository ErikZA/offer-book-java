# ⚠️ Test Patterns & Templates (Arquivo Descontinuado)

**Este conteúdo foi consolidado em [COMPREHENSIVE_TESTING.md](COMPREHENSIVE_TESTING.md)**

Todos os padrões, templates e exemplos agora estão disponíveis em um único documento: **[Guia Completo de Testes Consolidado](COMPREHENSIVE_TESTING.md)**

---

# Test Patterns & Templates

Este documento contém **padrões de testes prontos para copiar e colar** para cenários comuns em aplicações Spring Boot.

## 📋 Índice

1. [Testes Unitários](#testes-unitários)
2. [Testes de Integração](#testes-de-integração)
3. [Testes com Mock de APIs Externas](#testes-com-mock-de-apis-externas)
4. [Testes de Transações](#testes-de-transações)
5. [Testes de Eventos](#testes-de-eventos)
6. [Testes de Validação](#testes-de-validação)
7. [Testes de Performance](#testes-de-performance)
8. [Testes de Concorrência](#testes-de-concorrência)

---

## Testes Unitários

### Padrão Básico: Service com Repository

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
        
        // Verificar que email foi enviado
        verify(emailService, times(1))
            .sendWelcomeEmail("john@example.com");
    }
}
```

### Padrão: Tratamento de Exceções

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

### Padrão: ArgumentCaptor para Verificar Argumentos Passados

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

### Padrão: Múltiplas Chamadas com Diferentes Retornos

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

---

## Testes de Integração

### Padrão Básico: Criar Recurso

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

### Padrão: Atualizar Recurso

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

### Padrão: Deletar Recurso

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

### Padrão: Validações de Entrada

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

### Padrão: Paginação

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

### Padrão: Filtros

```java
@Test
@DisplayName("deve filtrar usuários por idade")
void shouldFilterUsersByAge() {
    // ACT & ASSERT
    given()
        .queryParam("minAge", 18)
        .queryParam("maxAge", 65)
        .when()
            .get("/users")
        .then()
            .statusCode(200)
            .body("findAll { it.age >= 18 && it.age <= 65 }",
                notNullValue());
}
```

---

## Testes com Mock de APIs Externas

### Usando WireMock

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
        
        // Verificar que a chamada foi feita
        verify(getRequestedFor(urlEqualTo("/api/external/data")));
    }
}
```

### Mock Falhando

```java
@Test
@DisplayName("deve tratar erro da API externa graciosamente")
void shouldHandleExternalApiError() {
    // ARRANGE
    stubFor(get(urlEqualTo("/api/external/data"))
        .willReturn(aResponse()
            .withStatus(503)
            .withBody("Service Unavailable")));
    
    // ACT & ASSERT
    assertThatThrownBy(() -> externalApiService.getData())
        .isInstanceOf(ExternalApiException.class);
}
```

---

## Testes de Transações

### Padrão: Rollback Automático

```java
@SpringBootTest
@Transactional  // Cada teste é uma transação que sofre rollback
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
        
        // Linha seguinte: transação sofre rollback automaticamente
    }
}
```

### Padrão: Transação que Falha

```java
@Test
@DisplayName("deve reverter transação ao encontrar erro")
void shouldRollbackOnError() {
    // ARRANGE
    User user1 = new User("john@example.com");
    User user2 = new User("invalid"); // Sem email válido
    
    // ACT & ASSERT
    assertThatThrownBy(() -> 
        userService.createMultipleUsers(Arrays.asList(user1, user2)))
        .isInstanceOf(ValidationException.class);
    
    // Ambos usuários não foram salvos (rollback)
    assertThat(userRepository.findAll()).isEmpty();
}
```

### Padrão: Flush Explícito

```java
@Test
@DisplayName("deve forçar flush antes de query")
void shouldFlushBeforeQuery() {
    // ARRANGE
    User user = new User("john@example.com");
    userRepository.save(user);
    
    // ACT - Flush explícito
    userRepository.flush();
    
    // A query agora verá o usuário
    List<User> users = userRepository.findAll();
    
    // ASSERT
    assertThat(users).hasSize(1);
}
```

---

## Testes de Eventos

### Padrão: Publicação de Evento

```java
@SpringBootTest
class UserEventIT {
    
    @SpyBean
    private UserCreatedEventListener eventListener;
    
    @Test
    @DisplayName("deve publicar evento ao criar usuário")
    void shouldPublishEventWhenCreatingUser() {
        // ARRANGE
        User user = new User("john@example.com");
        
        // ACT
        userService.createUser(user);
        
        // ASSERT - Verificar que o listener foi chamado
        verify(eventListener, timeout(5000))
            .onUserCreated(any(UserCreatedEvent.class));
    }
}
```

### Padrão: Event Listener com RestAssured

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderEventIT {
    
    @LocalServerPort
    private int port;
    
    @Test
    @DisplayName("deve processar evento de ordem criada")
    void shouldProcessOrderCreatedEvent() throws InterruptedException {
        // ARRANGE
        String createOrderRequest = """
            {
                "userId": "user-123",
                "amount": 100.00
            }
            """;
        
        // ACT
        given()
            .contentType(ContentType.JSON)
            .body(createOrderRequest)
            .when().post("http://localhost:" + port + "/api/orders")
            .then().statusCode(201);
        
        // ASSERT - Aguardar processamento de evento
        Thread.sleep(1000); // Aguardar processamento assíncrono
        
        given()
            .when()
                .get("http://localhost:" + port + "/api/wallet-balance/user-123")
            .then()
                .statusCode(200)
                .body("balance", equalTo(100.00f)); // Carteira foi creditada
    }
}
```

---

## Testes de Validação

### Padrão: Validações JSR-380

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
            .contains("must not be blank", "must be a well-formed email address", "must be greater than or equal to 0");
    }
}
```

### Padrão: Validação em Controller

```java
@Test
@DisplayName("deve retornar 400 com detalhes de validação")
void shouldReturnValidationErrorDetails() {
    // ARRANGE
    String invalidRequest = """
        {
            "name": "",
            "email": "invalid-email"
        }
        """;
    
    // ACT & ASSERT
    given()
        .contentType(ContentType.JSON)
        .body(invalidRequest)
        .when()
            .post("/users")
        .then()
            .statusCode(400)
            .body("errors.name", notNullValue())
            .body("errors.email", notNullValue());
}
```

---

## Testes de Performance

### Padrão: Tempo de Resposta

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

### Padrão: Múltiplas Requisições

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

---

## Testes de Concorrência

### Padrão: Teste com AtomicInteger

```java
@Test
@DisplayName("deve incrementar contador de forma thread-safe")
void shouldIncrementCounterThreadSafely() throws InterruptedException {
    // ARRANGE
    AtomicInteger counter = new AtomicInteger(0);
    ExecutorService executor = Executors.newFixedThreadPool(5);
    
    // ACT
    for (int i = 0; i < 100; i++) {
        executor.submit(() -> {
            // Simular operação
            int value = counter.get();
            // Aqui poderia haver race condition
            counter.set(value + 1);
        });
    }
    
    // ASSERT
    executor.shutdown();
    executor.awaitTermination(10, TimeUnit.SECONDS);
    
    assertThat(counter.get()).isEqualTo(100);
}
```

### Padrão: Teste com Latch

```java
@Test
@DisplayName("deve coordenar múltiplas threads com CountDownLatch")
void shouldCoordinateThreadsWithCountDownLatch() throws InterruptedException {
    // ARRANGE
    int numberOfThreads = 5;
    CountDownLatch latch = new CountDownLatch(numberOfThreads);
    
    // ACT
    for (int i = 0; i < numberOfThreads; i++) {
        new Thread(() -> {
            try {
                // Simular trabalho
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                latch.countDown();
            }
        }).start();
    }
    
    // ASSERT
    boolean finished = latch.await(10, TimeUnit.SECONDS);
    assertThat(finished).isTrue();
}
```

---

## 🎯 Quick Reference

| Padrão | Anotações | Quando Usar |
|--------|-----------|------------|
| **Unit Test** | `@ExtendWith(MockitoExtension.class)` | Testar lógica isolada sem Spring |
| **Integration Test** | `@SpringBootTest`, `@ActiveProfiles("test")` | Testar endpoints e integração com BD |
| **Slice Test** | `@WebMvcTest`, `@DataJpaTest` | Testar apenas controller ou repository |
| **Transactional** | `@Transactional` | Auto-rollback após teste |
| **Parametrized** | `@ParameterizedTest` | Mesmo teste com múltiplos valores |

---

## 📚 Mais Recursos

- [Spring Boot Testing](https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.testing)
- [REST Assured](https://rest-assured.io/)
- [Mockito](https://site.mockito.org/)
- [AssertJ](https://assertj.github.io/assertj-core/)
- [WireMock](https://wiremock.org/)
