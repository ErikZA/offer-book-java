package com.vibranium.orderservice.integration;

import com.vibranium.orderservice.config.RabbitMQConfig;
import com.vibranium.orderservice.domain.model.UserRegistry;
import com.vibranium.orderservice.domain.repository.OrderOutboxRepository;
import com.vibranium.orderservice.domain.repository.OrderRepository;
import com.vibranium.orderservice.domain.repository.UserRegistryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AT-01.2 — Valida resiliência determinística do Transactional Outbox Pattern quando
 * o broker RabbitMQ está indisponível.
 *
 * <h3>Garantias verificadas</h3>
 * <ul>
 *   <li><strong>Atomicidade</strong>: {@code Order} e as 2 mensagens de outbox são persistidas
 *       na mesma transação de banco independentemente do estado do broker.</li>
 *   <li><strong>Resiliência</strong>: o scheduler não marca mensagens como publicadas
 *       ({@code publishedAt} permanece {@code null}) enquanto o broker está offline.</li>
 *   <li><strong>Recuperação</strong>: após {@code unpause} do container, todas as mensagens
 *       pendentes são publicadas automaticamente no próximo ciclo do scheduler.</li>
 *   <li><strong>Não duplicidade</strong>: cada mensagem é publicada exatamente uma vez,
 *       verificado tanto na tabela {@code tb_order_outbox} quanto nas filas do broker.</li>
 * </ul>
 *
 * <h3>Mecanismo de falha determinístico</h3>
 * <p>Combina duas ações para garantir que a tentativa de publicação falhe com erro
 * em vez de "silenciosamente" ir para o buffer TCP:</p>
 * <ol>
 *   <li>{@code docker pause} — suspende todos os processos do container via cgroups freezer.
 *       O AMQP handshake ({@code connection.start/tune/open}) não conclui porque o
 *       processo RabbitMQ está frozen.</li>
 *   <li>{@link CachingConnectionFactory#resetConnection()} — invalida todos os channels
 *       em cache. A próxima tentativa de publicação cria uma <em>nova</em> conexão AMQP
 *       que expira em {@code connection-timeout=1000ms} com {@code AmqpConnectException}.
 *       O {@code publishSingle()} captura a exceção → {@code markAsPublished()} não é chamado
 *       → {@code publishedAt} permanece {@code null}.</li>
 * </ol>
 *
 * <h3>Isolamento total</h3>
 * <p>Este teste NÃO herda {@link AbstractIntegrationTest}. Usa containers
 * <strong>dedicados e sem {@code withReuse()}</strong>, garantindo que pause/unpause
 * não afete outros testes que rodem em paralelo ou sequência.</p>
 *
 * @see com.vibranium.orderservice.application.service.OrderOutboxPublisherService
 * @see com.vibranium.orderservice.domain.model.OrderOutboxMessage
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // Exclui auto-configuração do MongoDB: não é necessário neste teste de Command Side
                "spring.autoconfigure.exclude=" +
                "org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.mongo.MongoRepositoriesAutoConfiguration",

                // Desabilita beans do Query Side (OrderEventProjectionConsumer,
                // OrderQueryController, MongoIndexConfig). Sem MongoDB, esses beans
                // falhariam na injeção de dependência.
                "app.mongodb.enabled=false",

                // Remove o contributor 'mongo' do readiness group quando Mongo está desabilitado.
                // Evita NoSuchHealthContributorException no startup do contexto.
                "management.endpoint.health.group.readiness.include=db,redis,rabbit",

                // Reduz o intervalo do Outbox Publisher de 5000ms para 1000ms.
                // Cada ciclo do scheduler demora ≤ 1s para falhar (connection-timeout abaixo).
                // 3 ciclos confirmados = ~3-4s → Awaitility.during(4s) cobre com folga.
                "app.outbox.delay-ms=1000",

                // Timeout curto para o AMQP handshake (connection.start/tune/open).
                // Quando o broker está pausado, o processo RabbitMQ não responde ao
                // handshake → a tentativa de reconexão expira em ≤ 1s com
                // AmqpConnectException (determinístico, sem depender do timeout TCP padrão
                // de ~120s do kernel Linux).
                "spring.rabbitmq.connection-timeout=1000"
        }
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DisplayName("AT-01.2 — Outbox Resiliência: broker indisponível → recuperação automática garantida")
class OrderOutboxResilienceIntegrationTest {

    private static final Logger logger =
            LoggerFactory.getLogger(OrderOutboxResilienceIntegrationTest.class);

    // =========================================================================
    // Containers ISOLADOS — sem withReuse() para garantir lifecycle exclusivo.
    // @Container + static = iniciados antes do primeiro teste, parados após
    // o último. O Testcontainers gerencia start/stop; pause/unpause é feito
    // manualmente durante a execução do teste.
    // =========================================================================

    /**
     * PostgreSQL 16 Alpine dedicado para este teste de resiliência.
     * Flyway aplica todas as migrations (tb_user_registry, tb_orders, tb_order_outbox)
     * durante o startup do Spring context.
     */
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("vibranium_resilience_test")
                    .withUsername("test")
                    .withPassword("test")
                    // Aguarda explicitamente a porta TCP antes de declarar readiness
                    .waitingFor(Wait.forListeningPort());

    /**
     * RabbitMQ 3.13 management dedicado.
     *
     * <p>NÃO usa {@code withReuse(true)} — deve ser exclusivo para permitir
     * {@code docker pause/unpause} sem impactar outros testes da suíte.
     * Se o container fosse compartilhado via reuse, pausá-lo derrubaria
     * todos os testes que dependem do mesmo broker.</p>
     *
     * <p>Não sobrescreve a wait strategy: {@link RabbitMQContainer} aguarda
     * internamente a mensagem de log {@code "Server startup complete"}, garantindo
     * que o AMQP handshake esteja pronto antes do Spring context inicializar.
     * Usar {@code Wait.forListeningPort()} é insuficiente — a porta TCP pode estar
     * aberta antes que o RabbitMQ termine o startup, causando {@code AmqpConnectException}.</p>
     */
    @Container
    static final RabbitMQContainer RABBITMQ =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management-alpine"));

    /**
     * Redis 7 Alpine dedicado.
     *
     * <p>Necessário para que o {@code SpringBootTest} context inicialize sem erros:
     * {@code RedisMatchEngineAdapter} e {@code StringRedisTemplate} são auto-configurados.
     * O Motor de Match não é exercitado neste teste, mas o bean precisa estar disponível.</p>
     */
    @SuppressWarnings("resource") // lifecycle gerenciado pelo runtime Testcontainers
    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379)
                    // requirepass habilitado para simular produção com autenticação
                    .withCommand("redis-server", "--appendonly", "no", "--requirepass", "testpass")
                    .waitingFor(Wait.forListeningPort());

    // =========================================================================
    // Dynamic Properties — sobrescreve as URLs padrão do application-test.yml
    // com as portas aleatórias alocadas pelos containers Testcontainers.
    // Executado ANTES da criação do ApplicationContext.
    // =========================================================================

    @DynamicPropertySource
    static void registerContainerProperties(DynamicPropertyRegistry registry) {
        // PostgreSQL — Flyway precisa das credenciais para aplicar migrations
        registry.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        // RabbitMQ — Spring AMQP usa estas propriedades para criar o CachingConnectionFactory
        registry.add("spring.rabbitmq.host",     RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port",     RABBITMQ::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);

        // Redis — GenericContainer expõe porta mapeada aleatoriamente
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "testpass");
    }

    // =========================================================================
    // Beans injetados pelo Spring Context
    // =========================================================================

    /** MockMvc: simula requisições HTTP à API REST sem servidor Tomcat real. */
    @Autowired
    private MockMvc mockMvc;

    /** RabbitTemplate: usado para verificar mensagens nas filas após recuperação. */
    @Autowired
    private RabbitTemplate rabbitTemplate;

    /** RabbitAdmin: declara filas do wallet-service no broker de teste antes de cada execução. */
    @Autowired
    private RabbitAdmin rabbitAdmin;

    /**
     * ConnectionFactory: injetado como {@link CachingConnectionFactory} pelo Spring Boot AMQP.
     * Usado para chamar {@link CachingConnectionFactory#resetConnection()}, que invalida
     * todos os channels em cache e força reconexão na próxima operação.
     */
    @Autowired
    private ConnectionFactory connectionFactory;

    @Autowired
    private OrderOutboxRepository  outboxRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private UserRegistryRepository userRegistryRepository;

    // =========================================================================
    // Setup — garante estado limpo a cada execução
    // =========================================================================

    /**
     * Limpa todas as tabelas e recoloca o broker em estado operacional antes de cada teste.
     *
     * <p>Isso garante que o teste possa ser executado múltiplas vezes (idempotente) e
     * que um teste anterior com falha não contamine o estado do broker ou do banco.</p>
     */
    @BeforeEach
    void setUp() {
        // Limpa tabelas em ordem que respeita FKs (outbox → orders → users)
        outboxRepository.deleteAll();
        orderRepository.deleteAll();
        userRegistryRepository.deleteAll();

        // Garante que o broker não está pausado (proteção contra falha em execução anterior)
        ensureBrokerIsRunning();

        // Declara a fila wallet.commands.reserve-funds no broker de teste.
        // Em produção esta fila é declarada pelo wallet-service (consumidor);
        // aqui precisamos que exista para que o outbox possa entregar e o teste verificar.
        // Parâmetros idênticos aos usados pelo wallet-service (durable + DLX) para evitar
        // PRECONDITION_FAILED no broker compartilhado (Testcontainers withReuse=true).
        rabbitAdmin.declareQueue(QueueBuilder.durable(RabbitMQConfig.QUEUE_RESERVE_FUNDS)
                .withArgument("x-dead-letter-exchange", RabbitMQConfig.DLQ_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", RabbitMQConfig.QUEUE_RESERVE_FUNDS + ".dlq")
                .build());
        rabbitAdmin.declareBinding(new Binding(
                RabbitMQConfig.QUEUE_RESERVE_FUNDS, Binding.DestinationType.QUEUE,
                RabbitMQConfig.COMMANDS_EXCHANGE, RabbitMQConfig.QUEUE_RESERVE_FUNDS, null));

        // Restaura o cache de conexões AMQP para estado limpo
        resetConnectionCache();
    }

    // =========================================================================
    // AT-01.2 — Teste Principal de Resiliência (6 Fases em 1 Cenário)
    // =========================================================================

    @Test
    @DisplayName("""
            Dado broker pausado,
            Quando placeOrder() é chamado,
            Então Order e outbox são persistidos atomicamente,
            E publishedAt permanece null enquanto broker está offline,
            E após unpause todas as mensagens são publicadas sem duplicatas
            """)
    void outbox_deveGarantirAtomicidadeResilienciaERecuperacao() throws Exception {

        // ─── Arrange — cria usuário registrado no banco ───────────────────────
        final String keycloakId = UUID.randomUUID().toString();
        final UUID   walletId   = UUID.randomUUID();

        userRegistryRepository.save(new UserRegistry(keycloakId));

        // ═════════════════════════════════════════════════════════════════════
        // FASE 2 — Simulação de Falha
        //
        // Ordem das operações é deliberada:
        // 1. docker pause: suspende o processo RabbitMQ (AMQP handshake para de responder)
        // 2. resetConnection: invalida channels em cache → nova conexão será necessária
        //
        // Efeito: next rabbitTemplate.convertAndSend() tenta novo AMQP handshake
        // que expira em connection-timeout=1000ms → AmqpConnectException capturada em
        // publishSingle() → publishedAt permanece null. Determinístico e sem sleeps.
        // ═════════════════════════════════════════════════════════════════════

        logger.info("[FASE 2] Pausando container RabbitMQ e invalidando connection cache...");
        pauseBroker();
        resetConnectionCache();
        logger.info("[FASE 2] Broker pausado. publishSingle() falhará com AmqpConnectException por até {}ms",
                "1000 (connection-timeout)");

        // ═════════════════════════════════════════════════════════════════════
        // FASE 3 — Execução de placeOrder() com broker offline
        //
        // O PostgreSQL está plenamente disponível.
        // OrderCommandService.placeOrder() executa em @Transactional:
        //   1. Persiste Order (status=PENDING)
        //   2. Persiste OrderOutboxMessage(ReserveFundsCommand) — publishedAt=null
        //   3. Persiste OrderOutboxMessage(OrderReceivedEvent)  — publishedAt=null
        //   4. Commit da transação — TUDO ou NADA
        //
        // Nenhuma chamada ao RabbitMQ é feita nesta fase. O broker offline não
        // afeta a transação de banco de dados. Esta é a garantia de ATOMICIDADE.
        // ═════════════════════════════════════════════════════════════════════

        logger.info("[FASE 3] Chamando placeOrder() com broker offline...");

        mockMvc.perform(post("/api/v1/orders")
                        .contentType("application/json")
                        .content(buildPlaceOrderJson(walletId, "BUY", "100.00", "1.00"))
                        .with(jwt().jwt(j -> j.subject(keycloakId))))
                // HTTP 202 Accepted: ordem aceita pelo sistema mesmo com broker offline.
                // A garantia de entrega é responsabilidade do Outbox Pattern, não do HTTP layer.
                .andExpect(status().isAccepted());

        // ── 3a. Verifica persistência atômica da Order ─────────────────────
        var orders = orderRepository.findAll();
        assertThat(orders)
                .as("[AT-01.2] Order deve ser persistida no banco mesmo com broker offline (atomicidade)")
                .hasSize(1);
        assertThat(orders.get(0).getStatus())
                .as("[AT-01.2] Status inicial da Order deve ser PENDING")
                .isEqualTo(com.vibranium.contracts.enums.OrderStatus.PENDING);

        // ── 3b. Verifica 2 registros na tb_order_outbox ────────────────────
        var outboxMessages = outboxRepository.findAll();
        assertThat(outboxMessages)
                .as("[AT-01.2] Exatamente 2 mensagens de outbox devem ser criadas: " +
                    "ReserveFundsCommand + OrderReceivedEvent")
                .hasSize(2);

        var eventTypes = outboxMessages.stream().map(m -> m.getEventType()).toList();
        assertThat(eventTypes)
                .as("[AT-01.2] Tipos de evento devem ser ReserveFundsCommand e OrderReceivedEvent")
                .containsExactlyInAnyOrder("ReserveFundsCommand", "OrderReceivedEvent");

        // ── 3c. Verifica que ambas estão com publishedAt=null ──────────────
        assertThat(outboxMessages)
                .as("[AT-01.2] publishedAt deve ser null: mensagens pendentes com broker offline")
                .allMatch(msg -> msg.getPublishedAt() == null);

        assertThat(outboxMessages)
                .as("[AT-01.2] aggregateType deve ser 'Order' para ambas as mensagens")
                .allMatch(msg -> "Order".equals(msg.getAggregateType()));

        logger.info("[FASE 3] Verificações de atomicidade OK: 2 outbox records com publishedAt=null");

        // ═════════════════════════════════════════════════════════════════════
        // FASE 4 — Garantia de Não Processamento Indevido
        //
        // O Outbox Publisher roda a cada 1s (app.outbox.delay-ms=1000).
        // Com o broker pausado e connection-timeout=1000ms, cada ciclo:
        //   1. Busca messages pending (findByPublishedAtIsNull) — retorna 2 registros
        //   2. Para cada msg: tenta rabbitTemplate.convertAndSend()
        //   3. Tenta criar nova conexão AMQP → handshake não conclui → AmqpConnectException em ≤ 1s
        //   4. publishSingle() captura AmqpException, loga WARN → NÃO chama markAsPublished()
        //   5. publishedAt permanece null
        //
        // Awaitility.during(4s): verifica que a INVARIANTE (publishedAt==null) se mantém
        // continuamente durante 4 segundos ≈ 3-4 ciclos do scheduler. Se em qualquer
        // polling interval a condição falhar (publishedAt != null), o timer reseta e
        // o atMost(10s) expira com AssertionError — evidenciando que a resiliência
        // NÃO foi implementada corretamente.
        // ═════════════════════════════════════════════════════════════════════

        logger.info("[FASE 4] Verificando invariante: publishedAt deve permanecer null por 4s " +
                    "(3+ ciclos do scheduler com broker pausado)...");

        await("[AT-01.2] publishedAt deve permanecer null enquanto broker está pausado")
                .during(4, SECONDS)        // invariante deve se manter por 4s contínuos
                .atMost(10, SECONDS)       // timeout total da verificação
                .pollInterval(500, MILLISECONDS)
                .untilAsserted(() ->
                        assertThat(outboxRepository.findByPublishedAtIsNull())
                                .as("[AT-01.2] Todas mensagens devem estar pendentes com broker offline " +
                                    "(sem publicação indevida durante pausa)")
                                .hasSize(2)
                );

        logger.info("[FASE 4] Invariante confirmada: publishedAt=null em todos os ciclos. Resiliência OK.");

        // ═════════════════════════════════════════════════════════════════════
        // FASE 5 — Recuperação: unpause do broker + aguarda readiness
        //
        // docker unpause retoma todos os processos do container. O RabbitMQ
        // process volta a aceitar conexões AMQP. As conexões TCP que estavam
        // em ESTABLISHED (buffered) são drenadas e processadas.
        //
        // Awaitility(15s): sem sleep fixo. Tenta abrir um channel AMQP real
        // (isBrokerAmqpReachable). Só retorna true quando o handshake completo
        // (connection.start → tune → open → channel.open) é concluído com sucesso.
        // ═════════════════════════════════════════════════════════════════════

        logger.info("[FASE 5] Executando docker unpause e aguardando readiness do broker...");
        unpauseBroker();

        await("[AT-01.2] Broker deve estar acessível via AMQP após unpause")
                .atMost(15, SECONDS)
                .pollInterval(500, MILLISECONDS)
                .until(this::isBrokerAmqpReachable);

        logger.info("[FASE 5] Broker operacional. Spring AMQP reconectará no próximo ciclo do scheduler.");

        // ═════════════════════════════════════════════════════════════════════
        // FASE 6a — Verificação de Publicação Total
        //
        // Após o broker se recuperar, o próximo ciclo do scheduler (≤ 1s) irá:
        //   1. findByPublishedAtIsNull() → retorna 2 registros
        //   2. publishSingle() → convertAndSend() → broker aceita → sucesso
        //   3. markAsPublished() → publishedAt = Instant.now()
        //   4. outboxRepository.save(msg)
        //
        // Awaitility(15s): aguarda até que AMBOS os registros tenham publishedAt != null.
        // Também verifica que o count total permanece 2 (sem inserção de registros extras).
        // ═════════════════════════════════════════════════════════════════════

        logger.info("[FASE 6a] Aguardando publicação de todas as mensagens pendentes...");

        await("[AT-01.2] Todas as mensagens de outbox devem ser publicadas após recuperação")
                .atMost(15, SECONDS)
                .pollInterval(500, MILLISECONDS)
                .untilAsserted(() -> {
                    var published = outboxRepository.findAll();

                    assertThat(published)
                            .as("[AT-01.2] Número de registros na tabela não deve mudar " +
                                "(nenhum registro novo = sem duplicatas no banco)")
                            .hasSize(2);

                    assertThat(published)
                            .as("[AT-01.2] Todos os registros devem ter publishedAt != null " +
                                "após recuperação do broker")
                            .allMatch(msg -> msg.getPublishedAt() != null);
                });

        logger.info("[FASE 6a] publishedAt != null para todos os registros. Publicação confirmada.");

        // ═════════════════════════════════════════════════════════════════════
        // FASE 6b — Verificação de Entrega nas Filas Corretas
        //
        // ReserveFundsCommand:
        //   - Exchange: vibranium.commands (direct)
        //   - Routing key: wallet.commands.reserve-funds
        //   - Destino: fila wallet.commands.reserve-funds
        //   - Nenhum @RabbitListener do order-service consome esta fila →
        //     mensagem acumula e pode ser recebida por receive()
        //
        // OrderReceivedEvent:
        //   - Exchange: vibranium.events (topic)
        //   - Routing key: RabbitMQConfig.RK_ORDER_RECEIVED
        //   - Destino: fila order.projection.received
        //   - @ConditionalOnProperty(app.mongodb.enabled) → listener DESABILITADO
        //     neste teste → mensagem acumula e pode ser recebida por receive()
        // ═════════════════════════════════════════════════════════════════════

        logger.info("[FASE 6b] Verificando mensagens nas filas do broker...");

        // receive() com 5s de timeout: aguarda a mensagem estar disponível na fila.
        // Se a Fase 6a concluiu, a mensagem JÁ foi enviada ao broker, então
        // receive() deve retornar em < 100ms. O timeout é generoso para CI lento.
        var reserveFundsMsg = rabbitTemplate.receive(RabbitMQConfig.QUEUE_RESERVE_FUNDS, 5_000);
        assertThat(reserveFundsMsg)
                .as("[AT-01.2] ReserveFundsCommand deve estar disponível na fila '%s'"
                        .formatted(RabbitMQConfig.QUEUE_RESERVE_FUNDS))
                .isNotNull();

        var orderReceivedMsg = rabbitTemplate.receive(
                RabbitMQConfig.QUEUE_ORDER_PROJECTION_RECEIVED, 5_000);
        assertThat(orderReceivedMsg)
                .as("[AT-01.2] OrderReceivedEvent deve estar disponível na fila '%s'"
                        .formatted(RabbitMQConfig.QUEUE_ORDER_PROJECTION_RECEIVED))
                .isNotNull();

        logger.info("[FASE 6b] Mensagens encontradas nas filas corretas. Entrega confirmada.");

        // ═════════════════════════════════════════════════════════════════════
        // FASE 6c — Garantia de Não Duplicidade (banco + broker)
        //
        // O scheduler verifica findByPublishedAtIsNull() antes de tentar publicar.
        // Após a Fase 6a, todos os registros têm publishedAt != null → a query
        // retorna lista vazia → nenhuma nova tentativa de publicação.
        //
        // Awaitility.during(3s): invariante — nenhum registro com publishedAt=null
        // deve surgir. Se o scheduler tentar re-publicar, ele precisaria ter
        // resetado publishedAt, o que não é comportamento esperado.
        // ═════════════════════════════════════════════════════════════════════

        logger.info("[FASE 6c] Aguardando 3s adicionais para garantir ausência de duplicatas...");

        await("[AT-01.2] Invariante de não duplicidade: nenhum registro deve ser reenfileirado")
                .during(3, SECONDS)
                .atMost(5, SECONDS)
                .pollInterval(500, MILLISECONDS)
                .untilAsserted(() -> {
                    assertThat(outboxRepository.findByPublishedAtIsNull())
                            .as("[AT-01.2] findByPublishedAtIsNull() deve retornar lista vazia " +
                                "(sem mensagens reenfileiradas para duplicação)")
                            .isEmpty();

                    assertThat(outboxRepository.findAll())
                            .as("[AT-01.2] Total de registros deve permanecer 2 " +
                                "(sem inserção de registros extras)")
                            .hasSize(2);
                });

        // Verifica ausência de mensagens duplicadas nas filas do broker.
        // As mensagens recebidas na Fase 6b já foram consumidas do broker via receive().
        // Nenhuma nova mensagem deve existir nas filas (duplicata = falha do Outbox Pattern).
        var duplicateReserveFunds = rabbitTemplate.receive(RabbitMQConfig.QUEUE_RESERVE_FUNDS, 500);
        assertThat(duplicateReserveFunds)
                .as("[AT-01.2] Não deve haver mensagem duplicada na fila '%s'"
                        .formatted(RabbitMQConfig.QUEUE_RESERVE_FUNDS))
                .isNull();

        var duplicateOrderReceived = rabbitTemplate.receive(
                RabbitMQConfig.QUEUE_ORDER_PROJECTION_RECEIVED, 500);
        assertThat(duplicateOrderReceived)
                .as("[AT-01.2] Não deve haver mensagem duplicada na fila '%s'"
                        .formatted(RabbitMQConfig.QUEUE_ORDER_PROJECTION_RECEIVED))
                .isNull();

        logger.info("[FASE 6c] Não duplicidade confirmada no banco e nas filas. AT-01.2 PASSOU.");
    }

    // =========================================================================
    // Helpers — Docker Container Lifecycle
    // =========================================================================

    /**
     * Pausa o container RabbitMQ usando a Docker API.
     *
     * <p>Suspende todos os processos do container via cgroups freezer.
     * Conexões TCP existentes permanecem em estado {@code ESTABLISHED} no kernel do host,
     * mas o processo RabbitMQ não processa novos AMQP frames. Novas tentativas de
     * AMQP handshake expiram após {@code connection-timeout} ms.</p>
     */
    private void pauseBroker() {
        RABBITMQ.getDockerClient()
                .pauseContainerCmd(RABBITMQ.getContainerId())
                .exec();
        logger.debug("docker pause executado: containerId={}", RABBITMQ.getContainerId());
    }

    /**
     * Retoma o container RabbitMQ após pausa.
     *
     * <p>O cgroups freezer é removido e todos os processos do container voltam
     * a executar normalmente. Mensagens TCP bufferizadas são processadas imediatamente.</p>
     */
    private void unpauseBroker() {
        RABBITMQ.getDockerClient()
                .unpauseContainerCmd(RABBITMQ.getContainerId())
                .exec();
        logger.debug("docker unpause executado: containerId={}", RABBITMQ.getContainerId());
    }

    /**
     * Invalida todos os channels e a conexão física em cache no {@link CachingConnectionFactory}.
     *
     * <p>Garante que a próxima operação AMQP abra uma <em>nova</em> conexão — que falhará
     * rapidamente com {@code AmqpConnectException} quando o broker estiver pausado (pois o
     * AMQP handshake não conclui). Sem este passo, um channel em cache poderia aceitar
     * o write no buffer TCP local, fazendo {@code convertAndSend()} retornar sem erro
     * e marcando {@code publishedAt} prematuramente.</p>
     */
    private void resetConnectionCache() {
        if (connectionFactory instanceof CachingConnectionFactory ccf) {
            ccf.resetConnection();
            logger.debug("CachingConnectionFactory.resetConnection() invocado — cache invalidado");
        }
    }

    /**
     * Garante que o broker está ativo (não pausado) no início de cada teste.
     *
     * <p>Proteção contra estado residual de uma execução anterior que tenha falhado
     * antes de executar o {@code unpauseBroker()} da Fase 5.</p>
     */
    private void ensureBrokerIsRunning() {
        try {
            var inspect = RABBITMQ.getDockerClient()
                    .inspectContainerCmd(RABBITMQ.getContainerId())
                    .exec();
            if (Boolean.TRUE.equals(inspect.getState().getPaused())) {
                logger.warn("Broker encontrado pausado (remanescente de execução anterior). Executando unpause...");
                unpauseBroker();
            }
        } catch (Exception e) {
            // Container pode não estar disponível durante setup inicial — ignora
            logger.debug("ensureBrokerIsRunning: inspecção ignorada — {}", e.getMessage());
        }
    }

    /**
     * Verifica se o broker AMQP está acessível tentando abrir um channel real.
     *
     * <p>Usado pelo Awaitility na Fase 5 para aguardar readiness após {@code unpause}
     * sem recorrer a {@code Thread.sleep()}. Retorna {@code true} apenas quando o
     * AMQP handshake completo ({@code connection.start} → {@code tune} → {@code open}
     * → {@code channel.open}) é concluído com sucesso.</p>
     *
     * @return {@code true} se o broker respondeu ao handshake AMQP com sucesso.
     */
    private boolean isBrokerAmqpReachable() {
        try {
            // execute() cria um Channel (que exige conexão ativa) e executa a lambda.
            // Se o broker não estiver acessível, AmqpException é lançada → retorna false.
            Boolean channelOpen = rabbitTemplate.execute(channel -> channel.isOpen());
            return Boolean.TRUE.equals(channelOpen);
        } catch (Exception e) {
            logger.trace("Broker ainda inacessível: {}", e.getMessage());
            return false;
        }
    }

    // =========================================================================
    // Helpers — DSL de Teste
    // =========================================================================

    /**
     * Constrói o JSON do body de uma requisição de criação de ordem.
     *
     * @param wId    UUID da wallet do usuário
     * @param type   "BUY" ou "SELL"
     * @param price  preço unitário (ex: "100.00")
     * @param amount quantidade (ex: "1.00")
     * @return JSON válido para {@code POST /api/v1/orders}
     */
    private String buildPlaceOrderJson(UUID wId, String type, String price, String amount) {
        return """
                {
                  "walletId": "%s",
                  "orderType": "%s",
                  "price": %s,
                  "amount": %s
                }
                """.formatted(wId, type, price, amount);
    }
}

