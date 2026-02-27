# ⚠️ Arquivo Descontinuado

Este documento foi **consolidado** em [**COMPREHENSIVE_TESTING.md**](COMPREHENSIVE_TESTING.md).

## 🔗 Acesse o novo documento consolidado

📖 **[Guia Completo de Testes Consolidado](COMPREHENSIVE_TESTING.md)** - Contém:
- Guia completo de testes
- Todos os padrões e exemplos
- Quick start (Windows/Linux/Mac)
- Estrutura de testes
- Ferramentas e bibliotecas
- Troubleshooting
- CI/CD
- E muito mais (500+ linhas)

---

**Por que consolidado?**
Para facilitar a manutenção e o acesso aos padrões de teste, agora você tem um único documento com toda a informação necessária, sem duplicação. As seções anteriores foram incorporadas ao novo documento.

### Versão Anterior (Arquivo Descontinuado)

Este era o conteúdo anterior:

---

# Guia de Testes - Ambiente TDD Configurado

## 📋 Visão Geral

Este projeto está configurado com um ambiente completo de **Test-Driven Development (TDD)** utilizando as **melhores bibliotecas de testes do mercado**:

- **JUnit 5 (Jupiter)** - Framework de testes moderno
- **Mockito** - Framework de mocking
- **AssertJ** - Biblioteca de assertions fluentes
- **REST Assured** - Testes de APIs REST
- **TestContainers** - Testes com containers Docker
- **WireMock** - Mocking de serviços HTTP

## 🚀 Quick Start

### Windows (PowerShell)

```powershell
# Executar testes unitários
.\build.ps1 test-unit

# Executar testes de integração
.\build.ps1 test-integration

# Executar todos os testes
.\build.ps1 test

# Iniciar ambiente de desenvolvimento com hotreload
.\build.ps1 docker-dev-up

# Executar testes em containers
.\build.ps1 docker-test
```

### Linux / macOS

```bash
# Executar testes unitários
make test-unit

# Executar testes de integração
make test-integration

# Executar todos os testes
make test

# Iniciar ambiente de desenvolvimento
make docker-dev-up

# Executar testes em containers
make docker-test
```

## 📊 Estrutura de Testes

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

### Tipos de Testes

#### 1. **Testes Unitários** (`*Test.java`)
Testam componentes isolados (Services, Utilities).

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

#### 2. **Testes de Integração** (`*IT.java`)
Testam APIs REST e fluxos completos.

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

## 🛠️ Executando Testes

### Via Docker (Recomendado)

```bash
# Iniciar ambiente de testes completo
docker compose -f docker/docker-compose.test.yml up

# Executar testes e parar automaticamente
docker compose -f docker/docker-compose.test.yml up --abort-on-container-exit

# Ver logs dos testes
docker compose -f docker/docker-compose.test.yml logs

# Parar e limpar ambiente
docker compose -f docker/docker-compose.test.yml down
```

### Via Scripts do Projeto

```bash
# Windows
.\build.ps1 docker-test

# Linux/Mac
make docker-test
```

## 🔍 Cobertura de Código

Relatório de cobertura de código com **Jacoco** (automático em containers):

```bash
# Executar testes com cobertura
docker compose -f docker/docker-compose.test.yml up

# Relatório gerado em
# target/site/jacoco/index.html (após testes finalizarem)
```

### Configurar mínimo de cobertura

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

## 🐛 Debug Remoto

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

## 📚 Biblioteca de Assertions (AssertJ)

### Exemplos Comuns

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

## 📡 REST Assured - Testing APIs

### Estrutura BDD (Given-When-Then)

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

### Exemplos Comuns

```java
// GET request
given()
    .when().get("/users")
    .then().statusCode(200);

// POST request com corpo
given()
    .contentType(ContentType.JSON)
    .body(user)
    .when().post("/users")
    .then().statusCode(201)
        .body("id", notNullValue());

// PUT request
given()
    .contentType(ContentType.JSON)
    .body(updatedUser)
    .when().put("/users/{id}", userId)
    .then().statusCode(200);

// DELETE request
given()
    .when().delete("/users/{id}", userId)
    .then().statusCode(204);

// Verificar múltiplas condições
given()
    .when().get("/orders")
    .then()
        .statusCode(200)
        .assertThat()
        .body("size()", greaterThan(0))
        .body("find { it.status == 'PENDING' }", notNullValue())
        .body("findAll { it.total > 100 }.size()", greaterThan(0));
```

## 🧪 Mockito - Mocking e Verificação

### Anotações

```java
@ExtendWith(MockitoExtension.class)
class ServiceTest {
    
    @Mock                           // Cria um mock
    private Repository repository;
    
    @Spy                            // Monitora método real
    private Helper helper;
    
    @InjectMocks                    // Injeta os mocks
    private Service service;
    
    @ParameterizedTest              // Teste parametrizado
    @ValueSource(ints = {1, 2, 3})
    void testWithMultipleValues(int value) {
        // ...
    }
}
```

### Comportamentos (Stubs)

```java
// Configurar retorno simples
when(repository.findById(1L))
    .thenReturn(Optional.of(user));

// Configurar exceção
when(repository.save(any()))
    .thenThrow(new DatabaseException("Error"));

// Configurar comportamento com argumentos
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

### Verificação (Verify)

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

## 📝 Padrão AAA (Arrange-Act-Assert)

Estrutura recomendada para testes:

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

## 🐳 Docker Compose - Ambientes

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

# Ver relatório após execução
cat test-results/reports/index.html
```

## 📋 Checklist para Novos Testes

- [ ] Nome descritivo em `@DisplayName`
- [ ] Padrão AAA (Arrange-Act-Assert)
- [ ] Apenas um conceito testado por método
- [ ] Assertions significativas (não apenas `notNull()`)
- [ ] Mocks configurados corretamente
- [ ] Verificações (`verify()`) quando apropriado
- [ ] Testes independentes (sem dependência entre testes)
- [ ] Tempo de execução < 100ms para testes unitários
- [ ] Documentação de casos complexos

## 🔗 Integração CI/CD Recomendada

Adicionar ao GitHub Actions (`.github/workflows/test.yml`):

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

## 🆘 Troubleshooting

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
- Verificar se a classe é final (Mockito não consegue mockar classes finais)
- Usar `mock(Class.class)` manualmente se anotação não funcionar

## 📚 Referências

- [JUnit 5 Documentation](https://junit.org/junit5/docs/current/user-guide/)
- [Mockito Documentation](https://javadoc.io/doc/org.mockito/mockito-core/latest/org/mockito/Mockito.html)
- [AssertJ Documentation](https://assertj.github.io/assertj-core-features-highlight.html)
- [REST Assured Documentation](https://rest-assured.io/)
- [TestContainers Documentation](https://www.testcontainers.org/)
- [Spring Boot Testing Guide](https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.testing)

---

**Última atualização**: $(date)
