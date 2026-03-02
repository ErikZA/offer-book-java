package com.vibranium.orderservice.query.consumer;

import com.vibranium.contracts.events.order.MatchExecutedEvent;
import com.vibranium.contracts.events.order.OrderCancelledEvent;
import com.vibranium.contracts.events.order.OrderReceivedEvent;
import com.vibranium.contracts.events.wallet.FundsReservedEvent;
import com.vibranium.orderservice.config.RabbitMQConfig;
import com.vibranium.orderservice.query.model.OrderDocument;
import com.vibranium.orderservice.query.model.OrderDocument.OrderHistoryEntry;
import com.vibranium.orderservice.query.service.OrderAtomicHistoryWriter;
import org.bson.types.Decimal128;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Consumer de projeção que constrói e mantém o Read Model de Ordens no MongoDB.
 *
 * <p>Cada listener consome uma fila dedicada de projeção — cópia independente do evento
 * via fanout no {@code vibranium.events} TopicExchange (filas declaradas em
 * {@link RabbitMQConfig}). Isso desacopla o Read Model do Command Side sem
 * nenhum acoplamento temporal.</p>
 *
 * <h3>Idempotência Atômica — AT-05.2</h3>
 * <p>O padrão anterior ({@code findById + appendHistory + save}) sofria de
 * <em>lost update</em> sob concorrência: dois consumers liam o mesmo documento,
 * ambos appendavam em memória, e o último {@code save} sobrescrvia o primeiro.</p>
 *
 * <p>Substituído por {@link OrderAtomicHistoryWriter}, que usa
 * {@code mongoTemplate.updateFirst()} com filtro idempotente por {@code eventId}:</p>
 * <pre>
 *   updateFirst(
 *     { _id: orderId, "history.eventId": { $ne: eventId } },
 *     { $push: { history: entry }, $set: { ... } }
 *   )
 * </pre>
 * <p>O MongoDB aplica <em>document-level locking</em>: a operação é 100% atômica —
 * zero lost update, zero scan O(n) em memória, idempotência garantida no banco.</p>
 *
 * <h3>Resiliência a Ordem de Eventos — AT-05.1 Criação Lazy Determinística</h3>
 * <p>Eventos fora de ordem (ex: {@code FUNDS_RESERVED} antes de {@code ORDER_RECEIVED})
 * são tratados via {@code upsert=true}: se o documento não existe, é criado com os campos
 * mínimos disponíveis ({@code $setOnInsert}). Quando {@code ORDER_RECEIVED} chegar,
 * {@link OrderAtomicHistoryWriter#enrichOrderFieldsIfAbsent} preencherá os campos nulos
 * de forma idempotente sem sobrescrever dados já existentes.</p>
 */
@Component
// Criado apenas quando app.mongodb.enabled=true (ou quando a propriedade está ausente,
// comportamento de produção via matchIfMissing=true).
// Em testes do Command Side, AbstractIntegrationTest define app.mongodb.enabled=false
// para desabilitar este bean e evitar falha de injecão dos repositórios MongoDB.
@ConditionalOnProperty(name = "app.mongodb.enabled", matchIfMissing = true)
public class OrderEventProjectionConsumer {

    private static final Logger logger = LoggerFactory.getLogger(OrderEventProjectionConsumer.class);

    // OrderAtomicHistoryWriter encapsula todas as escritas atômicas via MongoTemplate.
    // OrderHistoryRepository (MongoRepository.save) foi removido do caminho de escrita
    // para evitar o padrão replace-document não-atômico.
    private final OrderAtomicHistoryWriter atomicWriter;

    public OrderEventProjectionConsumer(OrderAtomicHistoryWriter atomicWriter) {
        this.atomicWriter = atomicWriter;
    }

    // =========================================================================
    // 1. OrderReceivedEvent → cria documento PENDING ou enriquece stub lazy
    // =========================================================================

    /**
     * Cria um novo {@link OrderDocument} no estado PENDING ou enriquece um stub
     * criado lazily por evento anterior (AT-05.1).
     *
     * <p><strong>Operação atômica (AT-05.2):</strong> {@code upsertAndAppend} executa um
     * único {@code updateFirst}/{@code upsert} no MongoDB — sem read-modify-write.</p>
     *
     * <p><strong>Enriquecimento de stub (AT-05.1):</strong> após o append atômico,
     * {@link OrderAtomicHistoryWriter#enrichOrderFieldsIfAbsent} preenche campos nulos
     * (userId, orderType, price, originalQty, remainingQty) com dois {@code updateFirst}
     * condicionais — nunca sobrescreve valores já preenchidos.</p>
     *
     * @param event Evento publicado por {@code OrderCommandService.placeOrder()}.
     */
    @RabbitListener(queues = RabbitMQConfig.QUEUE_ORDER_PROJECTION_RECEIVED)
    public void onOrderReceived(OrderReceivedEvent event) {
        String orderId = event.orderId().toString();
        String userId  = event.userId().toString();

        logger.debug("Projeção ORDER_RECEIVED: orderId={} userId={}", orderId, userId);

        OrderHistoryEntry entry = new OrderHistoryEntry(
                event.eventId().toString(),
                "ORDER_RECEIVED",
                "type=%s price=%s amount=%s".formatted(
                        event.orderType(), event.price(), event.amount()),
                event.occurredOn()
        );

        // Upsert atômico: cria o documento com todos os campos de negócio via $setOnInsert
        // se o documento não existir. Se existir (stub lazy do AT-05.1), apenas appenda
        // ao histórico (os $setOnInsert não são aplicados a documentos existentes).
        boolean appended = atomicWriter.upsertAndAppend(
                orderId, entry, event.occurredOn(),
                // $setOnInsert — somente na criação do documento (nunca sobrescreve).
                // Decimal128 explícito: garante tipo BSON numérico (não String) para
                // campos BigDecimal — necessário para $inc posterior sem TypeMismatch.
                upsert -> upsert
                        .setOnInsert("userId",       userId)
                        .setOnInsert("orderType",    event.orderType().name())
                        .setOnInsert("price",        new Decimal128(event.price()))
                        .setOnInsert("originalQty",  new Decimal128(event.amount()))
                        .setOnInsert("remainingQty", new Decimal128(event.amount()))
                        .setOnInsert("status",       "PENDING"),
                null  // sem extraUpdates: ORDER_RECEIVED não transiciona status em documentos existentes
        );

        if (!appended) {
            logger.debug("Evento ORDER_RECEIVED duplicado ignorado: eventId={} orderId={}",
                    event.eventId(), orderId);
            return;
        }

        // AT-05.1: enriquece campos nulos de stub lazy (criado por evento out-of-order anterior).
        // Dois updateFirst condicionais — não afetam documentos já completos (campos não null).
        atomicWriter.enrichOrderFieldsIfAbsent(
                orderId, userId, event.orderType().name(), event.price(), event.amount());

        logger.info("Read Model criado/enriquecido atomicamente: orderId={} userId={}", orderId, userId);
    }

    // =========================================================================
    // 2. FundsReservedEvent → appenda FUNDS_RESERVED; status → OPEN
    // =========================================================================

    /**
     * Atualiza o documento para OPEN quando os fundos são confirmados pelo wallet-service.
     *
     * <p><strong>Operação atômica (AT-05.2):</strong> {@code upsertAndAppend} com
     * {@code $set("status", "OPEN")} — aplica status OPEN tanto na criação do stub
     * (out-of-order) quanto na atualização do documento existente.</p>
     *
     * <p>Fanout: esta fila recebe a mesma mensagem que a fila do Command Side
     * ({@link RabbitMQConfig#QUEUE_FUNDS_RESERVED}), mas de forma independente.</p>
     *
     * @param event Evento publicado pelo wallet-service.
     */
    @RabbitListener(queues = RabbitMQConfig.QUEUE_ORDER_PROJECTION_FUNDS)
    public void onFundsReserved(FundsReservedEvent event) {
        String orderId = event.orderId().toString();

        logger.debug("Projeção FUNDS_RESERVED: orderId={} correlationId={}",
                orderId, event.correlationId());

        OrderHistoryEntry entry = new OrderHistoryEntry(
                event.eventId().toString(),
                "FUNDS_RESERVED",
                "asset=%s amount=%s walletId=%s".formatted(
                        event.asset(), event.reservedAmount(), event.walletId()),
                event.occurredOn()
        );

        // AT-05.1 + AT-05.2: upsert atômico com idempotência por eventId.
        // Se documento não existe (out-of-order), cria stub com status OPEN.
        // FUNDS_RESERVED significa que os fundos foram reservados → status = OPEN é correto
        // mesmo para stubs criados lazily.
        boolean appended = atomicWriter.upsertAndAppend(
                orderId, entry, event.occurredOn(),
                null,   // sem $setOnInsert além de createdAt (já no upsertAndAppend base)
                extra -> extra.set("status", "OPEN")  // sempre transiciona para OPEN
        );

        if (!appended) {
            logger.debug("Evento FUNDS_RESERVED duplicado ignorado: eventId={}", event.eventId());
            return;
        }

        logger.info("Read Model atualizado atomicamente: orderId={} status=OPEN", orderId);
    }

    // =========================================================================
    // 3. MatchExecutedEvent → appenda MATCH_EXECUTED; status → FILLED ou PARTIAL
    // =========================================================================

    /**
     * Atualiza o documento ao cruzar uma ordem com uma contraparte.
     *
     * <p>O evento contém os dois lados do trade ({@code buyOrderId} e {@code sellOrderId}).
     * Este listener atualiza ambos os documentos.</p>
     *
     * <p><strong>Determinação de FILLED vs PARTIAL (AT-05.2):</strong>
     * {@link OrderAtomicHistoryWriter#appendMatchAndDecrement} usa {@code findAndModify}
     * com {@code $inc("remainingQty", -matchAmount)} e retorna o documento pós-modificação
     * com o {@code remainingQty} resultante. Se {@code remainingQty <= 0} → FILLED;
     * senão → PARTIAL. Tanto o decremento quanto o {@code $push} no histórico ocorrem
     * na mesma operação atômica — zero race condition em decrementos concorrentes.</p>
     *
     * @param event Evento publicado por {@code FundsReservedEventConsumer.handleMatch()}.
     */
    @RabbitListener(queues = RabbitMQConfig.QUEUE_ORDER_PROJECTION_MATCH)
    // AT-05.3 — Atomicidade cross-document: os dois findAndModify (buyer + seller) são
    // envolvidos em uma única sessão MongoDB com startTransaction. Se qualquer um falha,
    // session.abortTransaction() reverte ambos — zero inconsistência parcial.
    // Qualificador "mongoTransactionManager" é obrigatório para coexistir com o
    // JpaTransactionManager do Command Side sem ambiguidade.
    @Transactional("mongoTransactionManager")
    public void onMatchExecuted(MatchExecutedEvent event) {
        logger.debug("Projeção MATCH_EXECUTED: matchId={} buyOrderId={} sellOrderId={}",
                event.matchId(), event.buyOrderId(), event.sellOrderId());

        // Atualiza ambos os lados do trade dentro da mesma transação MongoDB.
        // Se updateDocumentWithMatch(seller) lançar exceção, o @Transactional
        // instrui o MongoTransactionManager a fazer abortTransaction(),
        // revertendo automaticamente a modificação do buyer — sem estado parcial.
        updateDocumentWithMatch(event.buyOrderId().toString(), event);
        updateDocumentWithMatch(event.sellOrderId().toString(), event);
    }

    /**
     * Atualiza o {@link OrderDocument} de um dos lados do match (buyer ou seller).
     *
     * <p><strong>Atomicidade AT-05.2:</strong> {@code appendMatchAndDecrement} realiza
     * {@code $push} + {@code $inc} em uma única operação {@code findAndModify} —
     * impossível para dois threads decrementarem o mesmo valor base.</p>
     *
     * @param orderId ID da ordem a atualizar.
     * @param event   Evento de match com os dados do cruzamento.
     */
    private void updateDocumentWithMatch(String orderId, MatchExecutedEvent event) {
        OrderHistoryEntry entry = new OrderHistoryEntry(
                // Prefixo do orderId garante eventId único por lado: mesmo matchId
                // gera 2 writes (buyer + seller) com os mesmos event.eventId().
                // O sufixo "-orderId" diferencia buyer de seller na chave de idempotência.
                event.eventId().toString() + "-" + orderId,
                "MATCH_EXECUTED",
                "matchId=%s price=%s qty=%s".formatted(
                        event.matchId(), event.matchPrice(), event.matchAmount()),
                event.occurredOn()
        );

        // AT-05.1 + AT-05.2: appendMatchAndDecrement cria stub se necessário (out-of-order),
        // appenda ao histórico com filtro idempotente E decrementa remainingQty com $inc —
        // tudo em uma única operação findAndModify atômica.
        // Retorna o documento pós-modificação para determinar o status final.
        OrderDocument updated = atomicWriter.appendMatchAndDecrement(
                orderId, entry, event.matchAmount(), event.occurredOn());

        if (updated == null) {
            // null = eventId já no histórico (duplicata) → idempotência garantida
            logger.debug("MATCH_EXECUTED duplicado ignorado: eventId={} orderId={}",
                    event.eventId(), orderId);
            return;
        }

        logger.info("Read Model atualizado atomicamente: orderId={} status={} remaining={}",
                orderId, updated.getStatus(), updated.getRemainingQty());
    }

    // =========================================================================
    // 4. OrderCancelledEvent → appenda ORDER_CANCELLED; status → CANCELLED
    // =========================================================================

    /**
     * Marca o documento como CANCELLED ao receber o evento de cancelamento.
     *
     * <p>Se o documento não existir (ordem cancelada antes de ser projetada),
     * cria um stub mínimo para que o cancelamento seja registrado no histórico.
     * Cancelamentos são eventos terminais — registrar o fato é preferível a perder a informação.</p>
     *
     * <p><strong>Operação atômica (AT-05.2):</strong> {@code upsertAndAppend} com
     * {@code $set("status", "CANCELLED")} garante que nem o append nem a transição
     * de status podem ser perdidos por lost update.</p>
     *
     * @param event Evento publicado por {@code FundsReservedEventConsumer.cancelOrder()}.
     */
    @RabbitListener(queues = RabbitMQConfig.QUEUE_ORDER_PROJECTION_CANCELLED)
    public void onOrderCancelled(OrderCancelledEvent event) {
        String orderId = event.orderId().toString();

        logger.debug("Projeção ORDER_CANCELLED: orderId={} reason={}", orderId, event.reason());

        OrderHistoryEntry entry = new OrderHistoryEntry(
                event.eventId().toString(),
                "ORDER_CANCELLED",
                "reason=%s detail=%s".formatted(event.reason(), event.detail()),
                event.occurredOn()
        );

        // AT-05.1 + AT-05.2: upsert atômico. Cancelamentos são terminais —
        // status CANCELLED mesmo em stubs criados lazily.
        boolean appended = atomicWriter.upsertAndAppend(
                orderId, entry, event.occurredOn(),
                null,   // sem $setOnInsert adicional
                extra -> extra.set("status", "CANCELLED")
        );

        if (!appended) {
            logger.debug("Evento ORDER_CANCELLED duplicado ignorado: eventId={}", event.eventId());
            return;
        }

        logger.info("Read Model atualizado atomicamente: orderId={} status=CANCELLED reason={}",
                orderId, event.reason());
    }
}

