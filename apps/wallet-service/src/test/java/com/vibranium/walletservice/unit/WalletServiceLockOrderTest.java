package com.vibranium.walletservice.unit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibranium.contracts.commands.wallet.SettleFundsCommand;
import com.vibranium.contracts.enums.AssetType;
import com.vibranium.walletservice.application.service.WalletService;
import com.vibranium.walletservice.domain.model.Wallet;
import com.vibranium.walletservice.domain.repository.IdempotencyKeyRepository;
import com.vibranium.walletservice.domain.repository.OutboxMessageRepository;
import com.vibranium.walletservice.domain.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Testes unitários para prevenção do deadlock ABBA em {@link WalletService#settleFunds}.
 *
 * <h3>Problema (AT-03.1) — Deadlock ABBA:</h3>
 * <p>Sem ordenação determinística, dois threads concorrentes podem adquirir locks
 * em ordem inversa e criar uma espera circular (deadlock):</p>
 * <pre>
 *   Thread 1: settleFunds(buyerA, sellerB) → bloqueia A, espera B
 *   Thread 2: settleFunds(buyerB, sellerA) → bloqueia B, espera A  ← DEADLOCK
 * </pre>
 *
 * <h3>Solução — Lock Ordering:</h3>
 * <p>Adquirir locks <em>sempre</em> em ordem crescente de UUID. Como
 * {@link UUID} implementa {@link Comparable}, a ordenação é natural e global.
 * Quaisquer dois threads processando as mesmas carteiras bloqueiam na mesma
 * sequência, eliminando a possibilidade de espera circular.</p>
 *
 * <h3>Estratégia TDD:</h3>
 * <ul>
 *   <li><b>FASE RED</b>: {@code RED-01} falha com o código atual (sem ordenação).</li>
 *   <li><b>FASE GREEN</b>: após refatoração de {@code settleFunds}, todos os testes passam.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WalletService — Prevenção de Deadlock ABBA em settleFunds (AT-03.1)")
class WalletServiceLockOrderTest {

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private OutboxMessageRepository outboxMessageRepository;

    @Mock
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Mock
    private ObjectMapper objectMapper;

    private WalletService service;

    // -------------------------------------------------------------------------
    // UUIDs escolhidos para expor o problema: BUYER > SELLER (condição ABBA)
    //
    // IMPORTANTE — Java UUID.compareTo() usa comparação de long SINALIZADO:
    //   UUID.getMostSignificantBits() retorna um long com sinal.
    //   Bits altos ≥ 0x8000... são negativos → UUIDs com 'f' MSB são MENORES.
    //
    // Para BUYER > SELLER, ambos os UUIDs devem ter MSB positivo (0x0..7xxx).
    //
    //   BUYER:  mostSig = 0x5000000000000000 = +5764607523034234880L  (maior)
    //   SELLER: mostSig = 0x1000000000000000 = +1152921504606846976L  (menor)
    //   → BUYER.compareTo(SELLER) > 0  ✓
    // -------------------------------------------------------------------------

    /**
     * UUID com MSB = 0x5000... (signed long positivo, maior).
     * Representa o buyerWalletId no cenário de risco ABBA.
     * Código atual bloqueia este PRIMEIRO — a correção deve bloquear o menor antes.
     */
    private static final UUID BUYER_WALLET_ID  = UUID.fromString("50000000-0000-0000-0000-000000000002");

    /**
     * UUID com MSB = 0x1000... (signed long positivo, menor).
     * Representa o sellerWalletId no cenário de risco ABBA.
     * Após a correção, este DEVE ser o primeiro lock adquirido (menor UUID).
     */
    private static final UUID SELLER_WALLET_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");

    private static final BigDecimal MATCH_PRICE  = new BigDecimal("100.00");
    private static final BigDecimal MATCH_AMOUNT = new BigDecimal("5.00");
    private static final BigDecimal TOTAL_BRL    = MATCH_PRICE.multiply(MATCH_AMOUNT); // 500.00

    @BeforeEach
    void setUp() throws JsonProcessingException {
        // ObjectMapper é mockado para isolar o foco do teste (ordem dos locks).
        // A serialização de eventos é comportamento auxiliar irrelevante aqui.
        lenient().when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        service = new WalletService(
                walletRepository, outboxMessageRepository, idempotencyKeyRepository, objectMapper
        );
    }

    // -------------------------------------------------------------------------
    // Helpers de factory — criam carteiras em estado pronto para liquidação
    // -------------------------------------------------------------------------

    /**
     * Cria carteira de comprador com {@code TOTAL_BRL} em BRL bloqueados.
     * Estado final esperado: brlAvailable=0, brlLocked=500, vibAvailable=0, vibLocked=0.
     */
    private Wallet createBuyerWallet() {
        // adjustBalance credita BRL sem precisar de setter público
        Wallet w = Wallet.create(UUID.randomUUID(), BigDecimal.ZERO, BigDecimal.ZERO);
        w.adjustBalance(TOTAL_BRL, null);         // brlAvailable = 0 + 500 = 500
        w.reserveFunds(AssetType.BRL, TOTAL_BRL); // brlAvailable = 0, brlLocked = 500
        return w;
    }

    /**
     * Cria carteira de vendedor com {@code MATCH_AMOUNT} em VIB bloqueados.
     * Estado final esperado: brlAvailable=0, brlLocked=0, vibAvailable=0, vibLocked=5.
     */
    private Wallet createSellerWallet() {
        Wallet w = Wallet.create(UUID.randomUUID(), BigDecimal.ZERO, MATCH_AMOUNT);
        w.reserveFunds(AssetType.VIBRANIUM, MATCH_AMOUNT); // vibAvailable = 0, vibLocked = 5
        return w;
    }

    /** Constrói {@link SettleFundsCommand} com os IDs fornecidos e parâmetros padrão. */
    private SettleFundsCommand buildCmd(UUID buyerWalletId, UUID sellerWalletId) {
        return new SettleFundsCommand(
                UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(),
                buyerWalletId, sellerWalletId,
                MATCH_PRICE, MATCH_AMOUNT, 1
        );
    }

    // =========================================================================
    // FASE RED — Ordenação determinística de locks
    // =========================================================================

    @Nested
    @DisplayName("Ordenação determinística de locks (FASE RED)")
    class LockOrderingTests {

        /**
         * <b>FASE RED — AT-03.1</b>
         *
         * <p>Dado que {@code BUYER_WALLET_ID > SELLER_WALLET_ID}, a correção exige que
         * {@code sellerWalletId} (menor UUID) seja o PRIMEIRO lock adquirido.</p>
         *
         * <p>Com o código <em>atual</em> (sem ordenação), {@code findByIdForUpdate} é chamado
         * na ordem dos campos do comando: buyer primeiro, seller depois.
         * Este teste <b>falha</b> com o código atual — essa é a FASE RED intencional.</p>
         *
         * <p>Após a refatoração que garante {@code min(buyer, seller)} como primeiro lock,
         * o teste passa (FASE GREEN).</p>
         */
        @Test
        @DisplayName("RED-01 — [DEVE FALHAR ANTES DA REFATORAÇÃO] quando buyerWalletId > sellerWalletId, " +
                     "o menor UUID deve ser bloqueado primeiro")
        void lockOrdering_whenBuyerIsLargerThanSeller_shouldLockSmallerUuidFirst() {
            // Precondição: garante o cenário ABBA (buyer > seller)
            assertThat(BUYER_WALLET_ID.compareTo(SELLER_WALLET_ID))
                    .as("Precondição: BUYER_WALLET_ID deve ser MAIOR que SELLER_WALLET_ID")
                    .isGreaterThan(0);

            Wallet buyerWallet  = createBuyerWallet();
            Wallet sellerWallet = createSellerWallet();

            // Captura a ordem real de aquisição de locks via InOrder tracking
            List<UUID> lockOrder = new ArrayList<>();
            when(walletRepository.findByIdForUpdate(any())).thenAnswer(inv -> {
                UUID id = inv.getArgument(0);
                lockOrder.add(id);
                if (id.equals(BUYER_WALLET_ID))  return Optional.of(buyerWallet);
                if (id.equals(SELLER_WALLET_ID)) return Optional.of(sellerWallet);
                return Optional.empty();
            });
            when(walletRepository.save(any(Wallet.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            service.settleFunds(buildCmd(BUYER_WALLET_ID, SELLER_WALLET_ID), "msg-red-01");

            // --------------- ASSERÇÃO RED ---------------
            // Código ATUAL bloqueia buyerWalletId (ffffffff) primeiro  → lockOrder.get(0) = BUYER
            // Código CORRETO deve bloquear sellerWalletId (000...01) primeiro → lockOrder.get(0) = SELLER
            //
            // Este assertThat FALHA com o código atual (FASE RED intencional).
            assertThat(lockOrder).hasSize(2);
            assertThat(lockOrder.get(0))
                    .as("""
                        [AT-03.1 RED-01] O PRIMEIRO lock deve ser o MENOR UUID.
                        Esperado (menor): %s (sellerWalletId)
                        Atual (código sem fix): %s (buyerWalletId)
                        
                        Problema: código atual bloqueia buyer primeiro, mas quando outro thread
                        processa o trade inverso (buyerB=seller, sellerB=buyer), ambos travam em
                        ordem oposta → deadlock ABBA.
                        """,
                        SELLER_WALLET_ID, BUYER_WALLET_ID
                    )
                    .isEqualTo(SELLER_WALLET_ID); // ← FALHA com código atual (FASE RED)
        }

        /**
         * Valida que, quando {@code buyerWalletId < sellerWalletId}, a ordem correta
         * (buyer primeiro) já é o comportamento do código atual E do código corrigido.
         *
         * <p>Garante que a refatoração não quebre o caso onde a ordem natural já era correta.</p>
         */
        @Test
        @DisplayName("RED-02 — quando buyerWalletId < sellerWalletId, " +
                     "o menor UUID (buyer) deve continuar sendo bloqueado primeiro")
        void lockOrdering_whenBuyerIsSmallerThanSeller_shouldLockBuyerFirst() {
            // Inverte: agora buyer é o MENOR (00000...01 < ffffffff)
            UUID buyerWalletId  = SELLER_WALLET_ID; // 00000000-...-0001 (menor)
            UUID sellerWalletId = BUYER_WALLET_ID;  // ffffffff          (maior)

            assertThat(sellerWalletId.compareTo(buyerWalletId))
                    .as("Precondição: sellerWalletId deve ser MAIOR que buyerWalletId")
                    .isGreaterThan(0);

            Wallet buyerWallet  = createBuyerWallet();
            Wallet sellerWallet = createSellerWallet();

            List<UUID> lockOrder = new ArrayList<>();
            when(walletRepository.findByIdForUpdate(any())).thenAnswer(inv -> {
                UUID id = inv.getArgument(0);
                lockOrder.add(id);
                if (id.equals(buyerWalletId))  return Optional.of(buyerWallet);
                if (id.equals(sellerWalletId)) return Optional.of(sellerWallet);
                return Optional.empty();
            });
            when(walletRepository.save(any(Wallet.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            service.settleFunds(buildCmd(buyerWalletId, sellerWalletId), "msg-red-02");

            // Aqui buyer < seller, portanto o primeiro lock deve ser buyerWalletId
            assertThat(lockOrder).hasSize(2);
            assertThat(lockOrder.get(0))
                    .as("[AT-03.1 RED-02] O PRIMEIRO lock deve ser o MENOR UUID (buyerWalletId=%s)",
                            buyerWalletId)
                    .isEqualTo(buyerWalletId);
        }
    }

    // =========================================================================
    // Preservação de semântica buyer/seller após reordenação
    // =========================================================================

    @Nested
    @DisplayName("Preservação de semântica buyer/seller após reordenação dos locks")
    class SemanticPreservationTests {

        /**
         * Garante que, mesmo com locks adquiridos em ordem inversa ao comando
         * (sellerWalletId antes de buyerWalletId), as operações de domínio são
         * aplicadas nas carteiras corretas:
         * <ul>
         *   <li>Buyer: {@code brlLocked -= totalBrl}, {@code vibAvailable += matchAmount}</li>
         *   <li>Seller: {@code vibLocked -= matchAmount}, {@code brlAvailable += totalBrl}</li>
         * </ul>
         *
         * <p>Este teste serve como proteção de regressão — deve PASSAR tanto antes
         * quanto após a refatoração do lock ordering.</p>
         */
        @Test
        @DisplayName("SEM-01 — buyer recebe VIB e seller recebe BRL, " +
                     "independente da ordem de lock adquirida")
        void semantics_buyerReceivesVibAndSellerReceivesBrl_afterLockReordering() {
            // Cenário de risco: buyer > seller (locks serão reordenados pela correção)
            assertThat(BUYER_WALLET_ID.compareTo(SELLER_WALLET_ID)).isGreaterThan(0);

            Wallet buyerWallet  = createBuyerWallet();
            Wallet sellerWallet = createSellerWallet();

            // Mock retorna a carteira correta para cada UUID, independente da ordem da chamada
            when(walletRepository.findByIdForUpdate(BUYER_WALLET_ID))
                    .thenReturn(Optional.of(buyerWallet));
            when(walletRepository.findByIdForUpdate(SELLER_WALLET_ID))
                    .thenReturn(Optional.of(sellerWallet));
            when(walletRepository.save(any(Wallet.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            service.settleFunds(buildCmd(BUYER_WALLET_ID, SELLER_WALLET_ID), "msg-sem-01");

            // Buyer: brlLocked deve zerar; vibAvailable deve receber o MATCH_AMOUNT
            assertThat(buyerWallet.getBrlLocked())
                    .as("[SEM-01] BRL locked do buyer deve ser ZERO após liquidação")
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(buyerWallet.getVibAvailable())
                    .as("[SEM-01] VIB available do buyer deve ser igual ao matchAmount após liquidação")
                    .isEqualByComparingTo(MATCH_AMOUNT);

            // Seller: vibLocked deve zerar; brlAvailable deve receber o TOTAL_BRL
            assertThat(sellerWallet.getVibLocked())
                    .as("[SEM-01] VIB locked do seller deve ser ZERO após liquidação")
                    .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(sellerWallet.getBrlAvailable())
                    .as("[SEM-01] BRL available do seller deve ser igual a totalBrl após liquidação")
                    .isEqualByComparingTo(TOTAL_BRL);
        }

        /**
         * Valida que a reordenação não altera o contrato do domínio quando
         * o buyerWalletId já é o menor UUID (nenhuma reordenação necessária).
         * Garante simetria comportamental nos dois sentidos possíveis.
         */
        @Test
        @DisplayName("SEM-02 — semântica correta também quando buyer < seller (sem reordenação)")
        void semantics_correctWhenBuyerIsSmallerThanSeller() {
            UUID buyerWalletId  = SELLER_WALLET_ID; // menor
            UUID sellerWalletId = BUYER_WALLET_ID;  // maior

            Wallet buyerWallet  = createBuyerWallet();
            Wallet sellerWallet = createSellerWallet();

            when(walletRepository.findByIdForUpdate(buyerWalletId))
                    .thenReturn(Optional.of(buyerWallet));
            when(walletRepository.findByIdForUpdate(sellerWalletId))
                    .thenReturn(Optional.of(sellerWallet));
            when(walletRepository.save(any(Wallet.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            service.settleFunds(buildCmd(buyerWalletId, sellerWalletId), "msg-sem-02");

            assertThat(buyerWallet.getBrlLocked()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(buyerWallet.getVibAvailable()).isEqualByComparingTo(MATCH_AMOUNT);
            assertThat(sellerWallet.getVibLocked()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(sellerWallet.getBrlAvailable()).isEqualByComparingTo(TOTAL_BRL);
        }
    }
}
