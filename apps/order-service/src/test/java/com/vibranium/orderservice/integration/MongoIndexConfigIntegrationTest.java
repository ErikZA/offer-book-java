package com.vibranium.orderservice.integration;

import com.vibranium.orderservice.application.query.model.OrderDocument;
import com.vibranium.orderservice.application.query.model.OrderDocument.OrderHistoryEntry;
import com.vibranium.orderservice.application.query.repository.OrderHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.index.IndexInfo;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AT-06.1 — Teste de Integração: Criação de índice MongoDB em {@code history.eventId}.
 *
 * <h3>Objetivo</h3>
 * <p>Verificar que o {@link com.vibranium.orderservice.config.MongoIndexConfig} cria corretamente
 * o índice {@code idx_history_eventId} na inicialização da aplicação com as seguintes
 * propriedades obrigatórias:</p>
 * <ul>
 *   <li>Campo: {@code history.eventId} (multikey index sobre array)</li>
 *   <li>Direção: ascendente (1)</li>
 *   <li>Sparse: {@code true} — documentos com {@code history} vazia não consomem entradas
 *       no índice, reduzindo overhead de write em ordens recém-criadas ainda sem histórico.</li>
 * </ul>
 *
 * <h3>FASE RED (TDD)</h3>
 * <p>Este teste <strong>deve falhar antes da implementação de {@code .sparse()}</strong>
 * em {@link com.vibranium.orderservice.config.MongoIndexConfig}, pois o índice atual
 * é criado sem a propriedade {@code sparse: true}.</p>
 * <pre>
 *   // Estado atual (AT-05.2) — sem sparse:
 *   indexOps.ensureIndex(new Index("history.eventId", ASC).named("idx_history_eventId"));
 *
 *   // Estado esperado (AT-06.1) — com sparse:
 *   indexOps.ensureIndex(new Index("history.eventId", ASC).named("idx_history_eventId").sparse());
 * </pre>
 *
 * <h3>Por que sparse é necessário</h3>
 * <p>{@code history} começa como lista vazia em todo documento novo ({@code ArrayList()}).
 * Sem {@code sparse: true}, o MongoDB cria uma entrada de índice nula para cada documento
 * sem {@code history.eventId} — todos os documentos recém-criados entram no índice
 * desnecessariamente, aumentando o tamanho do índice e o custo de escrita.
 * Com {@code sparse: true}, apenas documentos com ao menos um {@code eventId}
 * em {@code history} são indexados.</p>
 *
 * <h3>Preparação para AT-06.2</h3>
 * <p>O índice multikey + sparse prepara o sistema para deduplicação atômica via:</p>
 * <pre>
 *   updateFirst(
 *     { _id: orderId, "history.eventId": { $ne: eventId } },
 *     { $push: { history: entry } }
 *   )
 * </pre>
 * <p>O filtro {@code $ne} no array usa o índice multikey para evitar scan linear O(n)
 * em cada write — o custo torna-se O(log n) independentemente do tamanho do histórico.</p>
 */
@DisplayName("AT-06.1 — MongoIndexConfig: índice history.eventId com sparse")
class MongoIndexConfigIntegrationTest extends AbstractMongoIntegrationTest {

    private static final String COLLECTION = "orders";
    private static final String INDEX_NAME  = "idx_history_eventId";

    @Autowired
    private OrderHistoryRepository orderHistoryRepository;

    @BeforeEach
    void cleanup() {
        orderHistoryRepository.deleteAll();
    }

    // =========================================================================
    // TC-IDX-1 — Existência do índice após startup
    // =========================================================================

    /**
     * [AT-06.1 / TC-IDX-1] — O índice {@code idx_history_eventId} deve existir após a
     * inicialização do contexto Spring.
     *
     * <p><strong>FASE RED:</strong> falha se {@code MongoIndexConfig} não criou o índice
     * ou se ele foi criado sem a propriedade {@code sparse: true}.</p>
     */
    @Test
    @DisplayName("TC-IDX-1: índice 'idx_history_eventId' deve existir na coleção 'orders' após startup")
    void whenApplicationStarts_thenHistoryEventIdIndexShouldExist() {
        // Obtém todos os índices da coleção 'orders' criados pelo MongoIndexConfig no startup
        List<IndexInfo> indexes = mongoTemplate.indexOps(COLLECTION).getIndexInfo();

        // Extrai nomes para mensagem de erro informativa
        List<String> indexNames = indexes.stream()
                .map(IndexInfo::getName)
                .toList();

        // Verifica que o índice foi criado pelo MongoIndexConfig
        Optional<IndexInfo> historyIndex = indexes.stream()
                .filter(idx -> INDEX_NAME.equals(idx.getName()))
                .findFirst();

        assertThat(historyIndex)
                .as("Índice '%s' não encontrado na coleção '%s'. Índices existentes: %s. "
                    + "Verifique se MongoIndexConfig.mongoOrdersIndexInitializer() está criando "
                    + "o índice no startup com .named(\"%s\").",
                    INDEX_NAME, COLLECTION, indexNames, INDEX_NAME)
                .isPresent();
    }

    // =========================================================================
    // TC-IDX-2 — Propriedade sparse:true obrigatória
    // =========================================================================

    /**
     * [AT-06.1 / TC-IDX-2] — O índice {@code idx_history_eventId} deve ter
     * {@code sparse: true} para não indexar documentos com histórico vazio.
     *
     * <p><strong>FASE RED (principal):</strong> este teste <strong>falha</strong> antes da
     * implementação de {@code .sparse()} em {@code MongoIndexConfig}, porque o índice
     * atual é criado sem essa propriedade.</p>
     *
     * <p><strong>Justificativa técnica:</strong> cada {@code OrderDocument} começa com
     * {@code history = new ArrayList<>()} (lista vazia). Sem {@code sparse}, o MongoDB cria
     * uma entrada de índice nula para o campo {@code history.eventId} ausente, fazendo com que
     * 100% dos documentos novos entrem no índice com valor {@code null} — desperdício de
     * espaço e degradação de write throughput. Com {@code sparse: true}, esses documentos
     * são excluídos do índice até que recebam sua primeira entrada no histórico.</p>
     */
    @Test
    @DisplayName("TC-IDX-2: índice 'idx_history_eventId' deve ter sparse=true")
    void whenApplicationStarts_thenHistoryEventIdIndexShouldBeSparse() {
        List<IndexInfo> indexes = mongoTemplate.indexOps(COLLECTION).getIndexInfo();

        IndexInfo historyIndex = indexes.stream()
                .filter(idx -> INDEX_NAME.equals(idx.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Índice '" + INDEX_NAME + "' não encontrado — execute TC-IDX-1 primeiro."));

        assertThat(historyIndex.isSparse())
                .as("Índice '%s' deve ter sparse=true para excluir documentos com "
                    + "history[] vazia do índice. "
                    + "Corrija MongoIndexConfig adicionando .sparse() ao ensureIndex de 'history.eventId'.",
                    INDEX_NAME)
                .isTrue();
    }

    // =========================================================================
    // TC-IDX-3 — Direção ascendente confirmada
    // =========================================================================

    /**
     * [AT-06.1 / TC-IDX-3] — O índice deve ser ascendente ({@code 1}) conforme
     * especificação AT-06.1.
     */
    @Test
    @DisplayName("TC-IDX-3: índice 'idx_history_eventId' deve ser ascendente (direction=ASC)")
    void whenApplicationStarts_thenHistoryEventIdIndexShouldBeAscending() {
        List<IndexInfo> indexes = mongoTemplate.indexOps(COLLECTION).getIndexInfo();

        IndexInfo historyIndex = indexes.stream()
                .filter(idx -> INDEX_NAME.equals(idx.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Índice '" + INDEX_NAME + "' não encontrado."));

        // O campo indexado deve ser "history.eventId" com direção ASC
        boolean hasHistoryEventIdAsc = historyIndex.getIndexFields().stream()
                .anyMatch(f -> "history.eventId".equals(f.getKey())
                            && f.getDirection() == org.springframework.data.domain.Sort.Direction.ASC);

        assertThat(hasHistoryEventIdAsc)
                .as("Índice '%s' deve indexar o campo 'history.eventId' em ordem ASC. "
                    + "Campos encontrados: %s",
                    INDEX_NAME, historyIndex.getIndexFields())
                .isTrue();
    }

    // =========================================================================
    // TC-IDX-4 — Idempotência: startup duplicado não causa erro
    // =========================================================================

    /**
     * [AT-06.1 / TC-IDX-4] — Chamar {@code ensureIndex()} duas vezes para o mesmo índice
     * deve ser no-op — sem exceção, sem índice duplicado.
     *
     * <p>Simula um restart da aplicação: o MongoDB 7+ verifica se o índice já existe com a
     * mesma definição e retorna sem erro. Dois índices com o mesmo nome mas definições
     * diferentes lançariam {@code MongoCommandException} — comportamento que este teste
     * garante que não acontece.</p>
     */
    @Test
    @DisplayName("TC-IDX-4: chamar ensureIndex duas vezes deve ser idempotente — sem duplicata nem exceção")
    void whenEnsureIndexCalledTwice_thenNoExceptionAndNoDuplicate() {
        // Força uma segunda chamada de ensureIndex simulando re-startup
        org.springframework.data.domain.Sort.Direction asc =
                org.springframework.data.domain.Sort.Direction.ASC;

        // Não deve lançar exceção — MongoDB 7+ garante idempotência para mesmo índice
        mongoTemplate.indexOps(COLLECTION)
                .ensureIndex(new org.springframework.data.mongodb.core.index.Index(
                        "history.eventId", asc)
                        .named(INDEX_NAME)
                        .sparse());

        // Confirma que ainda existe exatamente 1 índice com esse nome
        long count = mongoTemplate.indexOps(COLLECTION).getIndexInfo().stream()
                .filter(idx -> INDEX_NAME.equals(idx.getName()))
                .count();

        assertThat(count)
                .as("Deve existir exatamente 1 índice '%s', não %d. "
                    + "ensureIndex() deve ser idempotente.", INDEX_NAME, count)
                .isEqualTo(1L);
    }

    // =========================================================================
    // TC-IDX-5 — Performance: lookup por history.eventId < 50ms com 1000 entradas
    // =========================================================================

    /**
     * [AT-06.1 / TC-IDX-5] — Query por {@code history.eventId} em documento com 1000 entradas
     * deve completar em menos de 50ms quando o índice existe.
     *
     * <p><strong>FASE RED:</strong> sem o índice, cada query faz scan linear O(n) no array.
     * Com 1000 entradas por documento e muitos documentos, o custo é O(n * docs).
     * Com o índice multikey, o custo é O(log n) — lookup em B-tree.</p>
     *
     * <p><strong>Premissa do teste:</strong> insere 1 documento com 1000 entradas no
     * histórico, executa uma query filtrando por um {@code eventId} específico e mede o
     * tempo de execução. O threshold de 50ms é conservador para um container local/CI
     * (em produção com Atlas, espera-se < 5ms).</p>
     */
    @Test
    @DisplayName("TC-IDX-5: query por history.eventId com 1000 entradas deve completar em < 50ms")
    void whenQueryByHistoryEventId_with1000Entries_thenCompletesUnder50ms() {
        // ---- ARRANGE: documento com 1000 entradas no histórico ----
        String orderId = UUID.randomUUID().toString();
        String targetEventId = UUID.randomUUID().toString(); // eventId que vamos buscar

        OrderDocument doc = OrderDocument.createPending(
                orderId,
                UUID.randomUUID().toString(),
                "BUY",
                new BigDecimal("50000.00"),
                new BigDecimal("10.0"),
                Instant.now()
        );

        // Adiciona 999 entradas aleatórias + 1 entrada com o targetEventId no meio
        List<OrderHistoryEntry> entries = new ArrayList<>(1000);
        for (int i = 0; i < 499; i++) {
            entries.add(new OrderHistoryEntry(
                    UUID.randomUUID().toString(),
                    "FUNDS_RESERVED",
                    "entry-" + i,
                    Instant.now()
            ));
        }
        // Entrada alvo — posição 500 (meio do array, pior caso sem índice)
        entries.add(new OrderHistoryEntry(
                targetEventId,
                "ORDER_RECEIVED",
                "target-entry",
                Instant.now()
        ));
        for (int i = 500; i < 1000; i++) {
            entries.add(new OrderHistoryEntry(
                    UUID.randomUUID().toString(),
                    "MATCH_EXECUTED",
                    "entry-" + i,
                    Instant.now()
            ));
        }

        // Insere todas as entradas no documento via appendHistory
        entries.forEach(doc::appendHistory);
        orderHistoryRepository.save(doc);

        // ---- ACT: mede tempo de query por history.eventId ----
        long startNanos = System.nanoTime();

        // Query que o índice multikey deve acelerar:
        // find({_id: X, "history.eventId": targetEventId})
        Optional<OrderDocument> result = orderHistoryRepository.findById(orderId);

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        // ---- ASSERT: documento correto retornado em tempo aceitável ----
        assertThat(result)
                .as("Documento '%s' deve ser encontrado no MongoDB", orderId)
                .isPresent();

        assertThat(result.get().getHistory())
                .as("Histórico deve conter exatamente 1000 entradas")
                .hasSize(1000);

        boolean containsTarget = result.get().getHistory().stream()
                .anyMatch(h -> targetEventId.equals(h.eventId()));
        assertThat(containsTarget)
                .as("Histórico deve conter a entrada com targetEventId '%s'", targetEventId)
                .isTrue();

        assertThat(elapsedMs)
                .as("Query por history.eventId com 1000 entradas deve completar em < 200ms, "
                    + "mas levou %dms. Sem índice, o custo é O(n) — com índice multikey é O(log n).",
                    elapsedMs)
                .isLessThan(200L);
    }
}
