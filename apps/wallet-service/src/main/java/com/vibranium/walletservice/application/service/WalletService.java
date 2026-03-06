package com.vibranium.walletservice.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibranium.contracts.commands.wallet.ReleaseFundsCommand;
import com.vibranium.contracts.commands.wallet.ReserveFundsCommand;
import com.vibranium.contracts.commands.wallet.SettleFundsCommand;
import com.vibranium.contracts.enums.FailureReason;
import com.vibranium.contracts.events.wallet.*;
import com.vibranium.walletservice.application.dto.WalletResponse;
import com.vibranium.walletservice.domain.model.IdempotencyKey;
import com.vibranium.walletservice.domain.model.OutboxMessage;
import com.vibranium.walletservice.domain.model.Wallet;
import com.vibranium.walletservice.domain.repository.IdempotencyKeyRepository;
import com.vibranium.walletservice.domain.repository.OutboxMessageRepository;
import com.vibranium.walletservice.domain.repository.WalletRepository;
import com.vibranium.walletservice.web.exception.InsufficientFundsException;
import com.vibranium.walletservice.web.exception.InsufficientLockedFundsException;
import com.vibranium.walletservice.web.exception.WalletNotFoundException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Serviço central do wallet-service — implementa toda a lógica de negócio
 * de carteiras em transações ACID no PostgreSQL.
 *
 * <p>Responsabilidades principais:</p>
 * <ul>
 *   <li><b>Criação de carteiras</b> — disparada por evento REGISTER do Keycloak.</li>
 *   <li><b>Reserva de fundos</b> — bloqueia saldo antes de uma ordem entrar no livro.</li>
 *   <li><b>Liberação de fundos</b> — devolve saldo bloqueado no caminho compensatório da Saga.</li>
 *   <li><b>Liquidação de fundos</b> — executa as transferências BRL/VIB após um match.</li>
 *   <li><b>Ajuste de saldo</b> — operação REST para crédito/débito administrativo.</li>
 * </ul>
 *
 * <p>Todos os métodos que alteram estado utilizam:</p>
 * <ul>
 *   <li>Lock pessimista ({@code SELECT ... FOR UPDATE}) via {@code WalletRepository}.</li>
 *   <li>Transactional Outbox gravado na mesma transação da alteração do saldo.</li>
 *   <li>Chave de idempotência gravada atomicamente, prevenindo double-processing.</li>
 * </ul>
 *
 * <h3>Prevenção de Deadlock ABBA (AT-03.1)</h3>
 * <p>O método {@link #settleFunds} adquire locks pessimistas em <em>duas</em> carteiras
 * por transação. Sem ordenação, dois settlements concorrentes sobre as mesmas carteiras
 * poderiam adquirir locks em ordem inversa, criando espera circular (deadlock ABBA).</p>
 *
 * <p>Solução aplicada: os locks são sempre adquiridos em <b>ordem crescente de UUID</b>
 * ({@link UUID#compareTo}), garantindo uma sequência global determinística. A semântica
 * de buyer/seller é restaurada por mapeamento após a aquisição dos locks —
 * sem alterar nenhum contrato público nem introduzir lock Java/synchronized.</p>
 */
@Service
public class WalletService {

    private static final Logger logger = LoggerFactory.getLogger(WalletService.class);

    private final WalletRepository walletRepository;
    private final OutboxMessageRepository outboxMessageRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public WalletService(WalletRepository walletRepository,
                         OutboxMessageRepository outboxMessageRepository,
                         IdempotencyKeyRepository idempotencyKeyRepository,
                         ObjectMapper objectMapper,
                         MeterRegistry meterRegistry) {
        this.walletRepository = walletRepository;
        this.outboxMessageRepository = outboxMessageRepository;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    // -------------------------------------------------------------------------
    // Criação de carteira (triggered por Keycloak REGISTER)
    // -------------------------------------------------------------------------

    /**
     * Cria uma nova carteira zerada para o usuário recém-registrado.
     *
     * <p>Salva {@code WalletCreatedEvent} no outbox na mesma transação.
     * A chave de idempotência é gravada atomicamente para evitar duplicatas
     * em caso de re-entrega da mensagem do Keycloak.</p>
     *
     * @param userId        UUID do usuário no Keycloak.
     * @param correlationId ID de correlação da Saga de onboarding.
     * @param messageId     ID da mensagem AMQP (chave de idempotência).
     */
    @Transactional
    public void createWallet(UUID userId, UUID correlationId, String messageId) {
        logger.info("Creating wallet for userId={}", userId);

        Wallet wallet = Wallet.create(userId, BigDecimal.ZERO, BigDecimal.ZERO);
        wallet = walletRepository.save(wallet);

        WalletCreatedEvent event = WalletCreatedEvent.of(correlationId, wallet.getId(), userId);
        outboxMessageRepository.save(OutboxMessage.create(
                "WalletCreatedEvent",
                wallet.getId().toString(),
                toJson(event)
        ));

        // Grava chave de idempotência na mesma transação — à prova de retries
        idempotencyKeyRepository.save(new IdempotencyKey(messageId));

        logger.info("Wallet created: walletId={}, userId={}", wallet.getId(), userId);
    }

    // -------------------------------------------------------------------------
    // Reserva de fundos (Cenário de ordem no livro)
    // -------------------------------------------------------------------------

    /**
     * Tenta reservar (bloquear) o saldo especificado no comando.
     *
     * <p>Fluxo:</p>
     * <ol>
     *   <li>Adquire lock pessimista na carteira.</li>
     *   <li>Verifica saldo disponível.</li>
     *   <li>Sucesso: move available → locked, grava {@code FundsReservedEvent} no outbox.</li>
     *   <li>Falha: não altera saldo, grava {@code FundsReservationFailedEvent} no outbox.</li>
     * </ol>
     *
     * <p>A {@link InsufficientFundsException} é capturada internamente —
     * a falha de negócio é representada como evento no outbox, não como exceção HTTP.</p>
     *
     * @param cmd       Comando com walletId, asset e amount.
     * @param messageId ID da mensagem AMQP para idempotência.
     */
    @Transactional
    public void reserveFunds(ReserveFundsCommand cmd, String messageId) {
        logger.debug("Processing ReserveFundsCommand for walletId={}", cmd.walletId());

        // Grava chave de idempotência antes de qualquer alteração de estado
        idempotencyKeyRepository.save(new IdempotencyKey(messageId));

        Wallet wallet = walletRepository.findByIdForUpdate(cmd.walletId())
                .orElseThrow(() -> WalletNotFoundException.forWalletId(cmd.walletId()));

        try {
            wallet.reserveFunds(cmd.asset(), cmd.amount());
            walletRepository.save(wallet);

            FundsReservedEvent event = FundsReservedEvent.of(
                    cmd.correlationId(), cmd.orderId(), cmd.walletId(), cmd.asset(), cmd.amount()
            );
            outboxMessageRepository.save(OutboxMessage.create(
                    "FundsReservedEvent",
                    wallet.getId().toString(),
                    toJson(event)
            ));
            logger.info("Funds reserved: walletId={}, asset={}, amount={}",
                    cmd.walletId(), cmd.asset(), cmd.amount());

            // AT-15.2: incrementa contador de reservas com tag asset (BRL|VIBRANIUM)
            Counter.builder("vibranium.funds.reserved")
                    .tag("asset", cmd.asset().name())
                    .register(meterRegistry)
                    .increment();

        } catch (InsufficientFundsException e) {
            // Falha de negócio: saldo insuficiente. Não lançar exceção — registra evento de falha.
            logger.warn("Insufficient funds for walletId={}: {}", cmd.walletId(), e.getMessage());

            FundsReservationFailedEvent failedEvent = FundsReservationFailedEvent.of(
                    cmd.correlationId(), cmd.orderId(),
                    wallet.getId().toString(),
                    FailureReason.INSUFFICIENT_FUNDS,
                    e.getMessage()
            );
            outboxMessageRepository.save(OutboxMessage.create(
                    "FundsReservationFailedEvent",
                    wallet.getId().toString(),
                    toJson(failedEvent)
            ));
        }
    }

    // -------------------------------------------------------------------------
    // Liberação de fundos (compensação Saga)
    // -------------------------------------------------------------------------

    /**
     * Libera (desbloqueia) o saldo reservado no contexto compensatório da Saga.
     *
     * <p>Move {@code amount} de "locked" de volta para "available", revertendo
     * uma reserva prévia. Opera dentro de uma única {@code @Transactional}, garantindo
     * que a mutação do saldo, a gravação do evento no outbox e a chave de idempotência
     * sejam atômicas — ou tudo comita, ou nada muda.</p>
     *
     * <p>Fluxo happy path:</p>
     * <ol>
     *   <li>Grava chave de idempotência (previne double-processing em re-entregas).</li>
     *   <li>Adquire lock pessimista na carteira ({@code SELECT FOR UPDATE}).</li>
     *   <li>Executa {@code wallet.releaseFunds()} — valida locked antes de modificar.</li>
     *   <li>Grava {@code FundsReleasedEvent} no outbox na mesma TX.</li>
     * </ol>
     *
     * <p>Em caso de falha (carteira não encontrada ou saldo locked insuficiente),
     * grava {@code FundsReleaseFailedEvent} no outbox sem alterar saldos.</p>
     *
     * @param cmd       Comando com walletId, asset e amount a liberar.
     * @param messageId ID da mensagem AMQP para idempotência.
     */
    @Transactional
    public void releaseFunds(ReleaseFundsCommand cmd, String messageId) {
        logger.debug("Processing ReleaseFundsCommand for walletId={}", cmd.walletId());

        // Grava chave de idempotência atomicamente com a operação
        idempotencyKeyRepository.save(new IdempotencyKey(messageId));

        try {
            Wallet wallet = walletRepository.findByIdForUpdate(cmd.walletId())
                    .orElseThrow(() -> WalletNotFoundException.forWalletId(cmd.walletId()));

            // Valida locked >= amount e move locked → available
            wallet.releaseFunds(cmd.asset(), cmd.amount());
            walletRepository.save(wallet);

            FundsReleasedEvent event = FundsReleasedEvent.of(
                    cmd.correlationId(), cmd.orderId(),
                    cmd.walletId(), cmd.asset(), cmd.amount()
            );
            outboxMessageRepository.save(OutboxMessage.create(
                    "FundsReleasedEvent",
                    wallet.getId().toString(),
                    toJson(event)
            ));
            logger.info("Funds released: walletId={}, asset={}, amount={}",
                    cmd.walletId(), cmd.asset(), cmd.amount());

            // AT-15.2: incrementa contador de liberações com tag reason (SAGA_COMPENSATION)
            Counter.builder("vibranium.funds.released")
                    .tag("reason", "SAGA_COMPENSATION")
                    .register(meterRegistry)
                    .increment();

        } catch (WalletNotFoundException | InsufficientLockedFundsException e) {
            /*
             * Falha crítica: fundos permanecem bloqueados indevidamente.
             * Emitir FundsReleaseFailedEvent para que o order-service acione
             * processo de reconciliação manual ou alerta operacional.
             */
            logger.error("ReleaseFunds failed for walletId={}: {}", cmd.walletId(), e.getMessage());

            FailureReason reason = (e instanceof WalletNotFoundException)
                    ? FailureReason.WALLET_NOT_FOUND
                    : FailureReason.RELEASE_DB_ERROR;

            outboxMessageRepository.save(OutboxMessage.create(
                    "FundsReleaseFailedEvent",
                    cmd.walletId().toString(),
                    toJson(FundsReleaseFailedEvent.of(
                            cmd.correlationId(), cmd.orderId(),
                            cmd.walletId().toString(),
                            reason, e.getMessage()
                    ))
            ));
        }
    }

    // -------------------------------------------------------------------------
    // Liquidação de fundos (pós-match)
    // -------------------------------------------------------------------------

    /**
     * Executa a liquidação de um trade ACID: transfere BRL e VIB entre comprador e vendedor.
     *
     * <p>Fluxo happy path:</p>
     * <ol>
     *   <li>Adquire lock pessimista nas duas carteiras <b>em ordem crescente de UUID</b>
     *       (prevenção de deadlock ABBA — AT-03.1).</li>
     *   <li>Comprador: brlLocked -= totalBrl, vibAvailable += matchAmount.</li>
     *   <li>Vendedor: vibLocked -= matchAmount, brlAvailable += totalBrl.</li>
     *   <li>Grava {@code FundsSettledEvent} no outbox.</li>
     * </ol>
     *
     * <p>Em caso de falha (carteira não encontrada ou saldo insuficiente),
     * grava {@code FundsSettlementFailedEvent} no outbox sem alterar saldos.</p>
     *
     * <p><b>Lock Ordering (AT-03.1):</b> para evitar deadlock entre settlements
     * concorrentes, os locks são sempre adquiridos na ordem {@code min(buyerWalletId,
     * sellerWalletId)} primeiro, {@code max(...)} segundo — via {@link UUID#compareTo}.
     * A semântica buyer/seller é restaurada por mapeamento após os locks.</p>
     *
     * @param cmd       Comando com IDs das carteiras, preço e quantidade do match.
     * @param messageId ID da mensagem AMQP para idempotência.
     */
    @Transactional
    public void settleFunds(SettleFundsCommand cmd, String messageId) {
        logger.debug("Processing SettleFundsCommand for matchId={}", cmd.matchId());

        // Grava chave de idempotência atomicamente com a operação
        idempotencyKeyRepository.save(new IdempotencyKey(messageId));

        try {
            /*
             * Prevenção de deadlock ABBA (AT-03.1) — Lock Ordering determinístico.
             *
             * Problema: sem ordenação, dois settlements concorrentes sobre as mesmas
             * carteiras podem adquirir locks em sentido oposto e criar espera circular:
             *
             *   Thread 1: lock(buyerA) → espera lock(sellerB)
             *   Thread 2: lock(buyerB) → espera lock(sellerA)  ← deadlock
             *
             * Solução: adquirir SEMPRE o lock do menor UUID primeiro.
             * UUID implementa Comparable<UUID> com comparação de long sinalizado nos
             * 128 bits — a ordenação é determinística e global.
             * Quaisquer dois threads processando as mesmas carteiras bloqueiam na mesma
             * sequência, eliminando a possibilidade de espera circular.
             *
             * A semântica buyer/seller é restaurada pelo mapeamento após os locks.
             */
            boolean buyerIsFirst = cmd.buyerWalletId().compareTo(cmd.sellerWalletId()) < 0;
            UUID firstId  = buyerIsFirst ? cmd.buyerWalletId() : cmd.sellerWalletId();
            UUID secondId = buyerIsFirst ? cmd.sellerWalletId() : cmd.buyerWalletId();

            // Adquire o lock do menor UUID primeiro — garante ordem global consistente
            Wallet firstWallet = walletRepository.findByIdForUpdate(firstId)
                    .orElseThrow(() -> WalletNotFoundException.forWalletId(firstId));

            // Adquire o lock do maior UUID segundo — sem risco de espera circular
            Wallet secondWallet = walletRepository.findByIdForUpdate(secondId)
                    .orElseThrow(() -> WalletNotFoundException.forWalletId(secondId));

            // Restaura a semântica original: buyer e seller apontam para as carteiras corretas
            // independentemente da ordem em que os locks foram adquiridos.
            Wallet buyer  = buyerIsFirst ? firstWallet  : secondWallet;
            Wallet seller = buyerIsFirst ? secondWallet : firstWallet;

            BigDecimal totalBrl = cmd.matchPrice().multiply(cmd.matchAmount());

            // Liquidação encapsulada nos métodos de domínio — invariantes validadas internamente.
            // applyBuySettlement lança InsufficientFundsException se brlLocked < totalBrl.
            buyer.applyBuySettlement(totalBrl, cmd.matchAmount());
            seller.applySellSettlement(cmd.matchAmount(), totalBrl);

            walletRepository.save(buyer);
            walletRepository.save(seller);

            FundsSettledEvent event = FundsSettledEvent.of(
                    cmd.correlationId(), cmd.matchId(),
                    cmd.buyOrderId(), cmd.sellOrderId(),
                    cmd.buyerWalletId(), cmd.sellerWalletId(),
                    cmd.matchPrice(), cmd.matchAmount()
            );
            outboxMessageRepository.save(OutboxMessage.create(
                    "FundsSettledEvent",
                    cmd.matchId().toString(),
                    toJson(event)
            ));
            logger.info("Funds settled: matchId={}, totalBrl={}, vibAmount={}",
                    cmd.matchId(), totalBrl, cmd.matchAmount());

            // AT-15.2: incrementa contador de settlements
            Counter.builder("vibranium.funds.settled")
                    .register(meterRegistry)
                    .increment();

        } catch (WalletNotFoundException | InsufficientFundsException e) {
            // Falha de negócio: grava evento compensatório sem alterar saldos
            logger.warn("Settlement failed for matchId={}: {}", cmd.matchId(), e.getMessage());

            FailureReason reason = (e instanceof WalletNotFoundException)
                    ? FailureReason.WALLET_NOT_FOUND
                    : FailureReason.INSUFFICIENT_FUNDS;

            outboxMessageRepository.save(OutboxMessage.create(
                    "FundsSettlementFailedEvent",
                    cmd.matchId().toString(),
                    toJson(FundsSettlementFailedEvent.of(
                            cmd.correlationId(), cmd.matchId(), reason, e.getMessage()
                    ))
            ));
        }
    }

    // -------------------------------------------------------------------------
    // Ajuste de saldo via REST (PATCH /balance)
    // -------------------------------------------------------------------------

    /**
     * Aplica um delta de saldo na carteira (crédito ou débito administrativo).
     *
     * <p>Utiliza lock pessimista para garantir consistência em atualizações concorrentes.
     * Valida que nenhum saldo ficará negativo antes de persisitir.</p>
     *
     * @param walletId  UUID da carteira a ser atualizada.
     * @param brlDelta  Delta de BRL (pode ser nulo para não alterar BRL).
     * @param vibDelta  Delta de VIB (pode ser nulo para não alterar VIB).
     * @return {@link WalletResponse} com os saldos atualizados.
     * @throws WalletNotFoundException    se a carteira não existir.
     * @throws InsufficientFundsException se qualquer saldo resultante for negativo.
     */
    @Transactional
    public WalletResponse adjustBalance(UUID walletId, BigDecimal brlDelta, BigDecimal vibDelta) {
        Wallet wallet = walletRepository.findByIdForUpdate(walletId)
                .orElseThrow(() -> WalletNotFoundException.forWalletId(walletId));

        // adjustBalance valida ANTES de modificar — transação atômica
        wallet.adjustBalance(brlDelta, vibDelta);
        wallet = walletRepository.save(wallet);

        logger.debug("Balance adjusted: walletId={}, brlDelta={}, vibDelta={}", walletId, brlDelta, vibDelta);
        return WalletResponse.from(wallet);
    }

    // -------------------------------------------------------------------------
    // Consultas (read-only)
    // -------------------------------------------------------------------------

    /**
     * Busca a carteira pelo walletId (chave primária).
     *
     * <p>Utilizado pelo {@code WalletController} para verificar ownership antes
     * de operações de escrita — permite obter o {@code userId} do dono sem
     * adquirir lock pessimista (operação somente-leitura).</p>
     *
     * @param walletId UUID da carteira.
     * @return DTO com os saldos e o userId do dono da carteira.
     * @throws WalletNotFoundException se a carteira não existir.
     */
    @Transactional(readOnly = true)
    public WalletResponse findById(UUID walletId) {
        Wallet wallet = walletRepository.findById(walletId)
                .orElseThrow(() -> WalletNotFoundException.forWalletId(walletId));
        return WalletResponse.from(wallet);
    }

    /**
     * Busca a carteira de um usuário pelo userId do Keycloak.
     *
     * @param userId UUID do usuário.
     * @return DTO com os saldos da carteira.
     * @throws WalletNotFoundException se não existir carteira para este userId.
     */
    @Transactional(readOnly = true)
    public WalletResponse findByUserId(UUID userId) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> WalletNotFoundException.forUserId(userId));
        return WalletResponse.from(wallet);
    }

    /**
     * Retorna todas as carteiras cadastradas com paginação.
     *
     * <p>Utilizado pelo endpoint admin {@code GET /api/v1/wallets} — restrito a ROLE_ADMIN
     * (AT-4.2.1). O {@link Pageable} é passado diretamente ao repository, que delega
     * ao {@code LIMIT}/{@code OFFSET} do SQL. O retorno é {@link Page} com
     * {@code content}, {@code totalElements} e {@code totalPages}.</p>
     *
     * @param pageable parâmetros de paginação (page, size, sort).
     * @return página de {@link WalletResponse}.
     */
    @Transactional(readOnly = true)
    public Page<WalletResponse> findAll(Pageable pageable) {
        // JpaRepository herda PagingAndSortingRepository.findAll(Pageable)
        // que executa SELECT com LIMIT/OFFSET + COUNT(*) automático para totalElements.
        return walletRepository.findAll(pageable)
                .map(WalletResponse::from);
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    /**
     * Serializa um objeto para JSON. Encapsula {@link JsonProcessingException}
     * em RuntimeException para não poluir as assinaturas dos métodos.
     */
    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Falha ao serializar evento para JSON: " + obj.getClass().getSimpleName(), e);
        }
    }
}
