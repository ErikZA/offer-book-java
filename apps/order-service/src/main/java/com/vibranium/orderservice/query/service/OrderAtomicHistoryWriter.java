package com.vibranium.orderservice.query.service;

import com.mongodb.client.result.UpdateResult;
import com.vibranium.orderservice.query.model.OrderDocument;
import com.vibranium.orderservice.query.model.OrderDocument.OrderHistoryEntry;
import org.bson.types.Decimal128;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.function.Consumer;

/**
 * Serviço responsável por operações atômicas de escrita no Read Model de Ordens (MongoDB).
 *
 * <h3>Problema resolvido — Lost Update (AT-05.2)</h3>
 * <p>O padrão anterior {@code findById() + appendHistory() + save()} sofria de
 * <em>lost update</em> clássico:</p>
 * <pre>
 *   Thread A: findById() → doc{history:[x]}
 *   Thread B: findById() → doc{history:[x]}           ← snapshot desatualizado
 *   Thread A: save doc{history:[x, eventA]}           ← persiste eventA
 *   Thread B: save doc{history:[x, eventB]}           ← SOBRESCREVE Thread A → eventA PERDIDO
 * </pre>
 *
 * <h3>Solução atômica — {@code updateFirst} com filtro idempotente</h3>
 * <pre>
 *   updateFirst(
 *     { _id: orderId, "history.eventId": { $ne: eventId } },   ← idempotência no banco
 *     { $push: { history: entry }, $set: { status: ... } }      ← mutação atômica
 *   )
 * </pre>
 * <p>O MongoDB aplica <em>document-level locking</em>: apenas um writer por vez
 * modifica o mesmo documento. O filtro {@code $ne: eventId} garante que:
 * <ol>
 *   <li>Se o eventId ainda não está no array → write ocorre (append atômico).</li>
 *   <li>Se o eventId já está no array → zero documentos casam o filtro → no-op (idempotência).</li>
 * </ol>
 * Zero lost update, zero sincronização Java, zero scan O(n) em memória.</p>
 *
 * <h3>Upsert com Lazy Creation (AT-05.1 + AT-05.2)</h3>
 * <p>Para eventos out-of-order (documento ainda não existe), usa {@code upsert=true}
 * com {@code $setOnInsert} para campos de criação. O {@link DuplicateKeyException}
 * é capturado no caso raro onde:
 * <ol>
 *   <li>Dois threads concorrentes tentam criar o mesmo documento simultaneamente.</li>
 *   <li>O primeiro cria com sucesso ({@code upsertedId != null}).</li>
 *   <li>O segundo falha com {@code DuplicateKeyException} e tenta novamente como update.</li>
 * </ol>
 * Esta estratégia segue o padrão MongoDB de <em>insert-or-update with retry</em>.</p>
 *
 * @see OrderDocument
 * @see com.vibranium.orderservice.query.consumer.OrderEventProjectionConsumer
 */
@Service
@ConditionalOnProperty(name = "app.mongodb.enabled", matchIfMissing = true)
public class OrderAtomicHistoryWriter {

    private static final Logger logger = LoggerFactory.getLogger(OrderAtomicHistoryWriter.class);

    // MongoTemplate é o ponto de entrada para operações nativas do MongoDB driver.
    // Permite $push, $inc, $set atômicos que o MongoRepository (save/findById) não oferece
    // — save() sempre emite um replace-document (sobrescreve o documento inteiro).
    private final MongoTemplate mongoTemplate;

    public OrderAtomicHistoryWriter(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    // =========================================================================
    // API pública — métodos semânticos por caso de uso
    // =========================================================================

    /**
     * Appenda atomicamente uma entrada ao histórico de um documento já existente.
     *
     * <p>Usa {@code updateFirst} (sem upsert): NÃO cria o documento caso não exista —
     * adequado para eventos cujo documento foi garantidamente criado antes
     * (ex: {@code ORDER_RECEIVED} que sempre cria o documento).</p>
     *
     * <p>O filtro {@code "history.eventId": {$ne: eventId}} garante idempotência
     * no nível do banco de dados: se o eventId já estiver no array, nenhum documento
     * será matched → {@code modifiedCount=0} → retorna {@code false} (duplicata descartada).</p>
     *
     * @param orderId      ID do documento ({@code @Id} do {@link OrderDocument}).
     * @param entry        Entrada de histórico a appendar.
     * @param extraUpdates Campos adicionais a atualizar na mesma operação atômica
     *                     (ex: {@code update.set("status", "OPEN")}).
     *                     Pode ser {@code null} se não houver campos adicionais.
     * @return {@code true} se inserida; {@code false} se o eventId já existia (duplicata).
     */
    public boolean appendAtomic(String orderId,
                                 OrderHistoryEntry entry,
                                 Consumer<Update> extraUpdates) {
        Query query = idempotencyQuery(orderId, entry.eventId());
        Update update = baseUpdate(entry);
        if (extraUpdates != null) {
            extraUpdates.accept(update);
        }

        UpdateResult result = mongoTemplate.updateFirst(query, update, OrderDocument.class);
        boolean appended = result.getModifiedCount() > 0;

        if (!appended) {
            logger.debug("appendAtomic: eventId={} já existe em orderId={} — descartado (idempotência DB)",
                    entry.eventId(), orderId);
        }
        return appended;
    }

    /**
     * Appenda atomicamente ao histórico com suporte a criação lazy (upsert).
     *
     * <p>Cria o documento se não existir ({@code upsert=true}) ou appenda ao existente.
     * Campos definidos via {@code $setOnInsert} são aplicados somente na criação do
     * documento — nunca sobrescrevem campos de documentos existentes.</p>
     *
     * <p>O {@link DuplicateKeyException} tratado internamente cobre a corrida de
     * dois threads tentando criar o mesmo documento simultaneamente: o segundo
     * recebe DuplicateKeyException, retenta como update (sem upsert) e sucede.</p>
     *
     * @param orderId      ID do documento.
     * @param entry        Entrada de histórico.
     * @param occurredOn   Timestamp do evento — usado em {@code $setOnInsert("createdAt")}.
     * @param upsertFields Campos a definir somente na criação ({@code $setOnInsert}).
     *                     Recebe o {@link Update} para que o caller adicione os
     *                     {@code .setOnInsert("campo", valor)} necessários.
     * @param extraUpdates Campos a atualizar sempre (insert E update).
     * @return {@code true} se inserida ou criada; {@code false} se duplicata.
     */
    public boolean upsertAndAppend(String orderId,
                                    OrderHistoryEntry entry,
                                    Instant occurredOn,
                                    Consumer<Update> upsertFields,
                                    Consumer<Update> extraUpdates) {
        Query query = idempotencyQuery(orderId, entry.eventId());

        // Retenta 1x caso DuplicateKeyException — padrão insert-or-update do MongoDB.
        // Ocorre quando dois threads criam o mesmo documento simultaneamente:
        // o perdedor da corrida retenta como update (sem upsert).
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                Update update = baseUpdate(entry)
                        .setOnInsert("createdAt", occurredOn);
                if (upsertFields != null) {
                    upsertFields.accept(update);
                }
                if (extraUpdates != null) {
                    extraUpdates.accept(update);
                }

                UpdateResult result = mongoTemplate.upsert(query, update, OrderDocument.class);

                boolean wroteDocument = result.getUpsertedId() != null || result.getModifiedCount() > 0;
                if (!wroteDocument) {
                    // Documento existe mas filtro não casou → eventId já no histórico → duplicata
                    logger.debug("upsertAndAppend: eventId={} já existe em orderId={} — descartado",
                            entry.eventId(), orderId);
                }
                return wroteDocument;

            } catch (DuplicateKeyException ex) {
                if (attempt == 0) {
                    // Corrida de criação: outro thread criou o documento entre nossa query e o
                    // upsert. Retenta sem upsert (agora o documento certamente existe).
                    logger.debug("upsertAndAppend: DuplicateKeyException em orderId={} attempt={} " +
                                 "— retentando como update puro", orderId, attempt);
                    continue;
                }
                // Segunda falha consecutiva — improvável, mas tratado defensivamente
                logger.warn("upsertAndAppend: DuplicateKeyException em orderId={} após retry " +
                            "— evento provavelmente já processado", orderId, ex);
                return false;
            }
        }
        return false;
    }

    /**
     * Appenda atomicamente uma entrada de MATCH e decrementa {@code remainingQty}.
     *
     * <p>Usa {@code findAndModify} com {@code returnNew=true} para:</p>
     * <ol>
     *   <li>Appendar o histórico atomicamente (com filtro de idempotência).</li>
     *   <li>Decrementar {@code remainingQty} via {@code $inc} — atômico no MongoDB,
     *       zero race condition em decrementos concorrentes.</li>
     *   <li>Retornar o documento <strong>pós-modificação</strong> para que o caller
     *       leia o {@code remainingQty} resultante e determine se o status é
     *       {@code FILLED} ou {@code PARTIAL}.</li>
     * </ol>
     *
     * <p>Se o documento não existir (out-of-order), cria um stub com
     * {@code $setOnInsert("createdAt", occurredOn)}. O upsert com {@code findAndModify}
     * cobre o caso de criação lazy com os campos mínimos obrigatórios.</p>
     *
     * @param orderId     ID do documento.
     * @param entry       Entrada de histórico.
     * @param matchAmount Quantidade matchada — decrementada de {@code remainingQty}.
     * @param occurredOn  Timestamp do evento original.
     * @return Documento atualizado (pós-modificação) com o novo {@code remainingQty},
     *         ou {@code null} se o {@code eventId} já estava no histórico (duplicata).
     */
    public OrderDocument appendMatchAndDecrement(String orderId,
                                                  OrderHistoryEntry entry,
                                                  BigDecimal matchAmount,
                                                  Instant occurredOn) {
        Query query = idempotencyQuery(orderId, entry.eventId());

        // $inc decrementa atomicamente — sem read-modify-write no caller.
        // remainingQty pode ficar negativo se a sequência de matches não for linear;
        // a UI deve tratar <= 0 como FILLED (max(0, remaining)).
        //
        // AT-05.3 / BUG FIX: BigDecimal.negate() é serializado como String pelo
        // UpdateMapper do Spring Data MongoDB para operações $inc (sem conversão automática
        // para Decimal128), causando TypeMismatch no servidor. A solução é passar um
        // Decimal128 explícito — o tipo BSON nativo para valores decimais de alta precisão,
        // compatível com campos armazenados como Decimal128 (padrão Spring Data MongoDB 3.x+).
        Update update = baseUpdate(entry)
                .setOnInsert("createdAt", occurredOn)
                .set("status", "PARTIAL")       // Assume PARTIAL; promovido a FILLED abaixo
                .inc("remainingQty", new Decimal128(matchAmount.negate()));

        // returnNew=true: recebe o documento APÓS a modificação, com o remainingQty já decrementado.
        // Necessário para determinar se o status final é FILLED ou PARTIAL SEM fazer uma segunda leitura.
        FindAndModifyOptions options = FindAndModifyOptions.options()
                .upsert(true)
                .returnNew(true);

        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                OrderDocument updated = mongoTemplate.findAndModify(
                        query, update, options, OrderDocument.class);

                if (updated == null) {
                    // findAndModify retorna null quando nenhum documento casou o filtro E
                    // upsert=false (aqui upsert=true, então null = filtro não casou porque
                    // o documento EXISTE com eventId já no array → duplicata idempotente).
                    logger.debug("appendMatchAndDecrement: eventId={} já existe em orderId={} — duplicata",
                            entry.eventId(), orderId);
                    return null;
                }

                // Promoção atômica a FILLED: se remainingQty chegou a zero, atualiza status
                // via updateFirst condicional — safe para concorrência (só executa se <= 0).
                BigDecimal remaining = updated.getRemainingQty() != null
                        ? updated.getRemainingQty()
                        : BigDecimal.ZERO;

                if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                    // Critério com literal 0 (int) — compatível com Decimal128 armazenado,
                    // evitando ambiguidade de tipo BSON na comparação de query.
                    mongoTemplate.updateFirst(
                            new Query(Criteria.where("_id").is(orderId)
                                              .and("remainingQty").lte(0)),
                            new Update().set("status", "FILLED"),
                            OrderDocument.class
                    );
                    // Reflete no objeto retornado para o logger do consumer
                    updated.transitionStatus("FILLED");
                }

                return updated;

            } catch (DuplicateKeyException ex) {
                if (attempt == 0) {
                    logger.debug("appendMatchAndDecrement: DuplicateKeyException em orderId={} — retry como update",
                            orderId);
                    // Retenta sem upsert na próxima iteração
                    options = FindAndModifyOptions.options().upsert(false).returnNew(true);
                    continue;
                }
                logger.warn("appendMatchAndDecrement: DuplicateKeyException após retry em orderId={}", orderId, ex);
                return null;
            }
        }
        return null;
    }

    // =========================================================================
    // Helpers privados
    // =========================================================================

    /**
     * Enriquece campos nulos de um documento existente com os dados do {@code OrderReceivedEvent}.
     *
     * <p>Equivalente atômico ao método {@link OrderDocument#enrichFields} do domínio,
     * porém sem read-modify-write: dois {@code updateFirst} condicionais garantem que
     * apenas campos {@code null} são atualizados — nunca sobrescrevem dados existentes.</p>
     *
     * <p>Duas operações separadas por necessidade:</p>
     * <ul>
     *   <li><strong>userId / orderType / price / originalQty</strong>: podem ser sobrescritos
     *       de forma segura pois são determinísticos (mesma ordem → mesmos valores).
     *       Agrupados em um único {@code updateFirst} com filtro {@code userId $isNull}.</li>
     *   <li><strong>remainingQty</strong>: filtro separado — não pode ser sobrescrito se
     *       já foi decrementado por eventos {@code MATCH_EXECUTED} anteriores (AT-05.1).</li>
     * </ul>
     *
     * @param orderId      ID do documento.
     * @param userId       Keycloak ID do usuário.
     * @param orderType    Tipo da ordem (BUY/SELL).
     * @param price        Preço limite.
     * @param originalQty  Quantidade total da ordem.
     */
    public void enrichOrderFieldsIfAbsent(String orderId,
                                           String userId,
                                           String orderType,
                                           java.math.BigDecimal price,
                                           java.math.BigDecimal originalQty) {
        // Enriquece userId e demais campos demográficos somente quando ainda null (stub lazy).
        // Decimal128 explícito para price e qtys: garante tipo numérico BSON correto,
        // evitando que $inc subsequente falhe com TypeMismatch em campo String.
        mongoTemplate.updateFirst(
                new Query(Criteria.where("_id").is(orderId).and("userId").isNull()),
                new Update()
                        .set("userId", userId)
                        .set("orderType", orderType)
                        .set("price", new Decimal128(price))
                        .set("originalQty", new Decimal128(originalQty)),
                OrderDocument.class
        );
        // remainingQty separado: preserva valor decrementado por MATCH_EXECUTED anterior.
        // Decimal128 explícito: consistência com o tipo armazenado pelo ORDER_RECEIVED.
        mongoTemplate.updateFirst(
                new Query(Criteria.where("_id").is(orderId).and("remainingQty").isNull()),
                new Update().set("remainingQty", new Decimal128(originalQty)),
                OrderDocument.class
        );
    }

    /**
     * Constrói o filtro de idempotência: o documento com o {@code orderId} que ainda
     * <strong>NÃO contém</strong> o {@code eventId} no array {@code history.eventId}.
     *
     * <p>Este filtro é a chave do mecanismo de idempotência atômica:</p>
     * <ul>
     *   <li>Se o documento não tem o eventId → query casa → update executado.</li>
     *   <li>Se o documento já tem o eventId → query não casa → {@code modifiedCount=0}
     *       → duplicata descartada silenciosamente.</li>
     * </ul>
     *
     * <p>O índice {@code { "history.eventId": 1 }} (criado em {@code MongoIndexConfig})
     * torna este filtro O(log n) em vez de O(n) — crítico para documentos com
     * histórico extenso de eventos.</p>
     *
     * @param orderId  ID do documento MongoDB.
     * @param eventId  ID do evento a verificar.
     * @return {@link Query} com os critérios de idempotência.
     */
    private static Query idempotencyQuery(String orderId, String eventId) {
        return new Query(
                Criteria.where("_id").is(orderId)
                        .and("history.eventId").ne(eventId)
        );
    }

    /**
     * Constrói o {@link Update} base:
     * <ul>
     *   <li>{@code $push} da entrada no array {@code history}.</li>
     *   <li>{@code $set} de {@code updatedAt} para rastreamento temporal.</li>
     * </ul>
     * O caller adiciona campos complementares via {@code .set("campo", valor)}.
     *
     * @param entry Entrada de histórico validada.
     * @return {@link Update} base com push e updatedAt.
     */
    private static Update baseUpdate(OrderHistoryEntry entry) {
        return new Update()
                .push("history", entry)
                .set("updatedAt", Instant.now());
    }
}
