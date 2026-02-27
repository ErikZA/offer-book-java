# 📚 Guia Completo de Testes - Consolidado

## 📋 Conteúdo

1. [Visão Geral](#visão-geral)
2. [Quick Start](#quick-start)
3. [Estrutura de Testes](#estrutura-de-testes)
4. [Tipos de Testes](#tipos-de-testes)
5. [Padrões de Teste](#padrões-de-teste)
6. [Executando Testes](#executando-testes)
7. [Ferramentas e Bibliotecas](#ferramentas-e-bibliotecas)
8. [Ambientes Docker](#ambientes-docker)
9. [Cobertura de Código](#cobertura-de-código)
10. [Debug Remoto](#debug-remoto)
11. [Checklist de Qualidade](#checklist-de-qualidade)
12. [Troubleshooting](#troubleshooting)
13. [Referências](#referências)

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
docker compose -f docker/docker-compose.test.yml up

# Desenvolvimento (detached, sem ver logs)
docker compose -f docker/docker-compose.dev.yml up -d

# Ver logs depois de iniciar
docker compose -f docker/docker-compose.dev.yml logs -f

# Parar tudo
docker compose -f docker/docker-compose.dev.yml down
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
docker compose -f docker/docker-compose.test.yml up

# Executar e parar automaticamente após finalizar
docker compose -f docker/docker-compose.test.yml up --exit-code-from test-runner

# Ver logs detalhados
docker compose -f docker/docker-compose.test.yml logs -f test-runner

# Limpar ambiente de testes
docker compose -f docker/docker-compose.test.yml down -v
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
docker compose -f docker/docker-compose.test.yml up

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
          docker compose -f docker/docker-compose.test.yml up --abort-on-container-exit
          
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
**Última atualização**: 27 de fevereiro de 2026
