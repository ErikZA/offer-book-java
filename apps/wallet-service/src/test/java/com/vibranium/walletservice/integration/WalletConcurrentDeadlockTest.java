package com.vibranium.walletservice.integration;

import com.vibranium.contracts.commands.wallet.SettleFundsCommand;
import com.vibranium.contracts.enums.AssetType;
import com.vibranium.walletservice.AbstractIntegrationTest;
import com.vibranium.walletservice.application.service.WalletService;
import com.vibranium.walletservice.domain.model.Wallet;
import com.vibranium.walletservice.domain.repository.IdempotencyKeyRepository;
import com.vibranium.walletservice.domain.repository.OutboxMessageRepository;
import com.vibranium.walletservice.domain.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AT-03.2 — Prova de ausência de deadlock ABBA em {@link WalletService#settleFunds}
 * usando PostgreSQL real via Testcontainers e threads concorrentes determinísticas.
 *
 * <h3>Problema — Deadlock ABBA</h3>
 * <p>Sem ordenação global de locks, dois settlements concorrentes sobre as mesmas
 * carteiras em ordens inversas criam uma espera circular:</p>
 * <pre>
 *   Thread 1: lock(A) → espera lock(B)  ← mantido por Thread 2
 *   Thread 2: lock(B) → espera lock(A)  ← mantido por Thread 1
 *             ↑ DEADLOCK — ambas esperam indefinidamente
 * </pre>
 *
 * <h3>Solução — Lock Ordering Determinístico (AT-03.1)</h3>
 * <p>O {@link WalletService#settleFunds} sempre adquire locks em <b>ordem crescente
 * de UUID</b> ({@link UUID#compareTo}), garantindo uma sequência global única.
 * Quaisquer dois threads processando as mesmas carteiras bloqueiam na mesma
 * sequência — eliminando a possibilidade de espera circular.</p>
 *
 * <h3>Estrutura do teste</h3>
 * <ul>
 *   <li><b>FASE RED</b>: Usa {@link TransactionTemplate} diretamente (sem lock ordering)
 *       para provocar um deadlock real no PostgreSQL. Dois {@link CountDownLatch}
 *       garantem o cenário ABBA de forma 100% determinística — sem {@code Thread.sleep()}.
 *       O PostgreSQL detecta a espera circular em ~1s e cancela uma transação
 *       (SQLState 40P01). O teste PASSA assertando que o erro ocorreu.</li>
 *   <li><b>FASE GREEN</b>: Usa {@link WalletService#settleFunds} (com lock ordering)
 *       para provar que o problema não ocorre. Executa 20 iterações consecutivas
 *       para robustez estatística.</li>
 *   <li><b>Alta Contenção</b>: 10 carteiras, 50 settlements concorrentes com pares
 *       aleatórios (semente fixa). Valida zero deadlocks, zero falhas e conservação
 *       global de valor.</li>
 * </ul>
 *
 * <h3>Ausência de deadlock prova o invariante ABBA</h3>
 * <p>Se o lock ordering garante que todos os threads sempre adquirem
 * {@code min(buyerWalletId, sellerWalletId)} primeiro, então não existe mais
 * uma espera circular possível: qualquer thread que espera pelo segundo lock
 * está esperando por um lock de UUID maior, que só pode ser mantido por um thread
 * que já tem o menor UUID — e esse thread terminará sem precisar do mesmo maior UUID
 * que o primeiro thread espera. Acyclicidade do grafo de espera garantida.</p>
 */
@DisplayName("AT-03.2 — Ausência de deadlock ABBA em settlements concorrentes (PostgreSQL real)")
class WalletConcurrentDeadlockTest extends AbstractIntegrationTest {

    // -------------------------------------------------------------------------
    // Constantes de fixture
    // -------------------------------------------------------------------------

    /** Preço fictício do match — valores pequenos para facilitar a verificação. */
    private static final BigDecimal MATCH_PRICE = new BigDecimal("10.00");

    /** Quantidade de VIB negociada por settlement. */
    private static final BigDecimal MATCH_AMOUNT = new BigDecimal("1.00000000");

    /**
     * Total BRL por settlement = price × amount.
     * Usado para validar a conservação de valor e para dimensionar os saldos iniciais.
     */
    private static final BigDecimal TOTAL_BRL = MATCH_PRICE.multiply(MATCH_AMOUNT); // 10.00

    // -------------------------------------------------------------------------
    // Dependências injetadas pelo contexto Spring Boot de teste
    // -------------------------------------------------------------------------

    /**
     * Serviço com lock ordering determinístico — objeto real que implementa a correção
     * AT-03.1. Usado nos testes da FASE GREEN e Alta Contenção.
     */
    @Autowired
    private WalletService walletService;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private OutboxMessageRepository outboxMessageRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    /**
     * Permite executar lambdas dentro de transações Spring gerenciadas a partir de
     * threads do pool — necessário na FASE RED para simular aquisição de locks sem
     * passar pelo WalletService (que tem a correção aplicada).
     */
    @Autowired
    private TransactionTemplate transactionTemplate;

    // -------------------------------------------------------------------------
    // Setup — isolamento entre testes
    // -------------------------------------------------------------------------

    /**
     * Limpa as tabelas de outbox e idempotência antes de cada teste para garantir
     * que contagens de eventos reflitam apenas o cenário atual, sem contaminação
     * de execuções anteriores.
     */
    @BeforeEach
    void cleanupDatabase() {
        outboxMessageRepository.deleteAll();
        idempotencyKeyRepository.deleteAll();
    }

    // -------------------------------------------------------------------------
    // Helpers de fixture
    // -------------------------------------------------------------------------

    /**
     * Cria e persiste uma carteira pronta para participar de settlements:
     * os saldos disponíveis são imediatamente reservados (available → locked),
     * replicando o estado após uma ordem entrar no livro de ordens.
     *
     * @param brlToLock Valor BRL a bloquear.
     * @param vibToLock Quantidade VIB a bloquear.
     * @return Wallet persistida com saldos locked, brlAvailable=0, vibAvailable=0.
     */
    private Wallet createWalletWithLockedFunds(BigDecimal brlToLock, BigDecimal vibToLock) {
        Wallet w = Wallet.create(UUID.randomUUID(), brlToLock, vibToLock);
        w.reserveFunds(AssetType.BRL, brlToLock);
        w.reserveFunds(AssetType.VIBRANIUM, vibToLock);
        return walletRepository.save(w);
    }

    /**
     * Constrói um {@link SettleFundsCommand} com preço e quantidade padrão.
     * IDs de correlação, match e ordens são aleatórios — idempotência garantida
     * pelo {@code messageId} passado individualmente em cada chamada ao serviço.
     */
    private SettleFundsCommand buildCmd(UUID buyerWalletId, UUID sellerWalletId) {
        return new SettleFundsCommand(
                UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(),
                buyerWalletId, sellerWalletId,
                MATCH_PRICE, MATCH_AMOUNT
        );
    }

    /**
     * Saldo total BRL de uma carteira (available + locked).
     * Usado na verificação de conservação de valor — a soma deve ser invariante
     * antes e depois de qualquer conjunto de settlements.
     */
    private BigDecimal totalBrl(Wallet w) {
        return w.getBrlAvailable().add(w.getBrlLocked());
    }

    /**
     * Saldo total VIB de uma carteira (available + locked).
     * Usado na verificação de conservação de valor.
     */
    private BigDecimal totalVib(Wallet w) {
        return w.getVibAvailable().add(w.getVibLocked());
    }

    /**
     * Percorre toda a cadeia de causas de uma exceção e concatena as mensagens.
     * Necessário para encontrar a mensagem do {@code PSQLException} no fundo
     * da cadeia Spring/Hibernate/JDBC (ex: "ERROR: deadlock detected").
     */
    private String buildFullExceptionMessage(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable current = t;
        while (current != null) {
            if (current.getMessage() != null) {
                sb.append(current.getMessage()).append(" | ");
            }
            current = current.getCause();
        }
        return sb.toString();
    }

    // =========================================================================
    // FASE RED — Demonstração controlada do deadlock ABBA sem lock ordering
    // =========================================================================

    /**
     * FASE RED — Prova que o problema EXISTE sem lock ordering.
     *
     * <p>Esta classe usa {@link TransactionTemplate} diretamente nos threads
     * para adquirir locks na ordem ABBA, sem passar pelo {@link WalletService}
     * que já tem a correção. O cenário força um deadlock real no PostgreSQL,
     * que é detectado e encerrado pelo mecanismo de deadlock detection
     * built-in do banco (parâmetro {@code deadlock_timeout}, padrão 1s).</p>
     */
    @Nested
    @DisplayName("FASE RED — Deadlock ABBA deliberado via TransactionTemplate (sem lock ordering)")
    class FaseRed {

        /**
         * <b>AT-03.2.RED-01</b>
         *
         * <p>Demonstra que, sem ordenação de locks, dois threads sobre as
         * <em>mesmas</em> carteiras em <em>ordens opostas</em> provocam um
         * deadlock real detectado pelo PostgreSQL (SQLState 40P01).</p>
         *
         * <h3>Mecanismo — CountDownLatch de 2 estágios (sem Thread.sleep)</h3>
         * <pre>
         *   Thread 1: findForUpdate(A) → countdown(t1HasA) → await(t2HasB) → findForUpdate(B) ← BLOCKED
         *   Thread 2: findForUpdate(B) → countdown(t2HasB) → await(t1HasA) → findForUpdate(A) ← BLOCKED
         *
         *   Resultado: ambos bloqueados → espera circular → PostgreSQL detecta ~1s
         *              → cancela UMA transação com PSQLException / SQLState 40P01
         *              → CannotAcquireLockException propagada para o thread vítima
         * </pre>
         *
         * <p>{@code assertThat(errors).isNotEmpty()} — o teste PASSA ao confirmar
         * que pelo menos uma transação foi cancelada por deadlock, provando que
         * o problema EXISTE sem a correção de lock ordering.</p>
         */
        @Test
        @Timeout(30) // Failsafe absoluto — o PostgreSQL irá detectar e cancelar em < 5s
        @DisplayName("RED-01 — Sem lock ordering, PostgreSQL detecta deadlock ABBA e cancela uma transação (SQLState 40P01)")
        void withoutLockOrdering_postgresDeadlockDetectionCancelsOneTransaction() throws InterruptedException {

            // Duas carteiras independentes — o problema surge quando dois threads
            // as adquirem em ordens OPOSTAS dentro de transações separadas.
            Wallet walletA = createWalletWithLockedFunds(TOTAL_BRL, MATCH_AMOUNT);
            Wallet walletB = createWalletWithLockedFunds(TOTAL_BRL, MATCH_AMOUNT);

            final UUID idA = walletA.getId();
            final UUID idB = walletB.getId();

            /*
             * Dois CountDownLatches forçam o cenário ABBA de forma determinística:
             *
             *   thread1HasLockA: Thread 1 libera APÓS adquirir lock em A.
             *                    Thread 2 aguarda este sinal antes de tentar lock em A.
             *
             *   thread2HasLockB: Thread 2 libera APÓS adquirir lock em B.
             *                    Thread 1 aguarda este sinal antes de tentar lock em B.
             *
             * Efeito: cada thread mantém seu primeiro lock enquanto espera que o
             * outro adquira o seu — os dois então tentam o segundo lock simultaneamente,
             * cada um bloqueado pelo lock do outro → espera circular = DEADLOCK.
             *
             * Nenhum Thread.sleep() necessário: a sincronização é 100% baseada em eventos.
             */
            CountDownLatch thread1HasLockA = new CountDownLatch(1);
            CountDownLatch thread2HasLockB = new CountDownLatch(1);
            List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

            ExecutorService executor = Executors.newFixedThreadPool(2);

            // Thread 1: Lock(A) → sinaliza → aguarda Thread 2 ter Lock(B) → tenta Lock(B)
            executor.submit(() -> {
                try {
                    transactionTemplate.execute(status -> {
                        // Passo 1: adquire lock em A dentro da transação Spring
                        walletRepository.findByIdForUpdate(idA);
                        // Passo 2: sinaliza que o lock em A foi adquirido
                        thread1HasLockA.countDown();
                        // Passo 3: aguarda Thread 2 garantir lock em B antes de tentar B
                        // (isso garante que a espera circular é formada antes de prosseguir)
                        try {
                            boolean acquired = thread2HasLockB.await(10, TimeUnit.SECONDS);
                            if (!acquired) {
                                status.setRollbackOnly();
                                return null;
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            status.setRollbackOnly();
                            return null;
                        }
                        // Passo 4: tenta lock em B (já mantido por Thread 2) → DEADLOCK
                        walletRepository.findByIdForUpdate(idB);
                        return null;
                    });
                } catch (Throwable t) {
                    // Captura CannotAcquireLockException ou DataAccessException
                    // propagada pelo Spring quando o PostgreSQL cancela a transação.
                    errors.add(t);
                }
            });

            // Thread 2: Lock(B) → sinaliza → aguarda Thread 1 ter Lock(A) → tenta Lock(A)
            executor.submit(() -> {
                try {
                    transactionTemplate.execute(status -> {
                        // Passo 1: adquire lock em B dentro da transação Spring
                        walletRepository.findByIdForUpdate(idB);
                        // Passo 2: sinaliza que o lock em B foi adquirido
                        thread2HasLockB.countDown();
                        // Passo 3: aguarda Thread 1 garantir lock em A
                        try {
                            boolean acquired = thread1HasLockA.await(10, TimeUnit.SECONDS);
                            if (!acquired) {
                                status.setRollbackOnly();
                                return null;
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            status.setRollbackOnly();
                            return null;
                        }
                        // Passo 4: tenta lock em A (já mantido por Thread 1) → DEADLOCK
                        walletRepository.findByIdForUpdate(idA);
                        return null;
                    });
                } catch (Throwable t) {
                    errors.add(t);
                }
            });

            executor.shutdown();

            // O PostgreSQL detecta o deadlock em ~deadlock_timeout (padrão 1s no PG 15):
            // uma transação vítima é cancelada e o thread recebe PSQLException(40P01),
            // que o Spring envolve em CannotAcquireLockException / PessimisticLockingFailureException.
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS))
                    .as("[RED-01] Executor não finalizou em 20s — PostgreSQL deveria ter detectado o deadlock")
                    .isTrue();

            // Prova que o deadlock ocorreu: pelo menos uma transação foi cancelada.
            assertThat(errors)
                    .as("""
                        [RED-01] Esperado pelo menos 1 error de deadlock.
                        Se vazio: o deadlock NÃO ocorreu como esperado, o que indicaria
                        um problema no mecanismo de latch ou na configuração do pool de conexões.
                        """)
                    .isNotEmpty();

            // Valida que a causa raiz menciona "deadlock" — confirma SQLState 40P01 do PostgreSQL.
            // A cadeia de exceção é: CannotAcquireLockException → LockAcquisitionException → PSQLException("deadlock detected").
            assertThat(errors)
                    .anySatisfy(e ->
                            assertThat(buildFullExceptionMessage(e))
                                    .as("[RED-01] A mensagem do erro deve mencionar 'deadlock' (PSQLException SQLState 40P01)")
                                    .containsIgnoringCase("deadlock")
                    );
        }
    }

    // =========================================================================
    // FASE GREEN — Dois settlements concorrentes invertidos sem deadlock (20 iterações)
    // =========================================================================

    /**
     * FASE GREEN — Prova que a correção funciona.
     *
     * <p>Usa {@link WalletService#settleFunds} que adquire locks em ordem crescente
     * de UUID. Dois settlements com buyer/seller invertidos são submetidos
     * concorrentemente via {@code CountDownLatch} — o mesmo cenário que causa
     * deadlock na FASE RED, mas agora sem deadlock porque a ordem dos locks é global.</p>
     */
    @Nested
    @DisplayName("FASE GREEN — Settlements concorrentes invertidos com lock ordering determinístico (20 iterações)")
    class FaseGreen {

        /**
         * <b>AT-03.2.GREEN-01</b>
         *
         * <p>Prova que dois settlements concorrentes com buyer/seller <em>invertidos</em>
         * NUNCA entram em deadlock quando {@link WalletService#settleFunds} garante
         * lock ordering por UUID crescente.</p>
         *
         * <h3>Cenário por iteração</h3>
         * <pre>
         *   walletA: brlLocked = TOTAL_BRL(10)  → buyer no Thread 1
         *            vibLocked = MATCH_AMOUNT(1) → seller no Thread 2
         *
         *   walletB: vibLocked = MATCH_AMOUNT(1) → seller no Thread 1
         *            brlLocked = TOTAL_BRL(10)   → buyer no Thread 2
         *
         *   Thread 1: settleFunds(buyer=A, seller=B) — Lock ordering: min(A,B) antes de max(A,B)
         *   Thread 2: settleFunds(buyer=B, seller=A) — Lock ordering: min(A,B) antes de max(A,B)
         *
         *   Resultado: ambos adquirem o mesmo primeiro lock → um espera, o outro executa.
         *              Zero espera circular → zero deadlock.
         * </pre>
         *
         * <h3>Conservação de valor por iteração</h3>
         * <pre>
         *   BRL antes: A.brlLocked(10) + B.brlLocked(10) = 20
         *   BRL após:  A.brlAvailable(10) + B.brlAvailable(10) = 20 ✓
         *
         *   VIB antes: A.vibLocked(1) + B.vibLocked(1) = 2
         *   VIB após:  A.vibAvailable(1) + B.vibAvailable(1) = 2 ✓
         * </pre>
         *
         * <p>Executa <b>20 iterações</b> consecutivas com carteiras frescas para robustez.</p>
         */
        @Test
        @Timeout(300) // 20 iterações × timeout máximo de 15s por iteração
        @DisplayName("GREEN-01 — Settlements invertidos concorrentes completam sem deadlock em 20 iterações consecutivas")
        void invertedConcurrentSettlements_neverDeadlock_20Iterations() throws Exception {
            for (int iteration = 0; iteration < 20; iteration++) {
                final int iter = iteration; // effectively final para uso no lambda

                /*
                 * Cria carteiras independentes por iteração para isolar completamente o estado.
                 * Cada wallet recebe:
                 *   brlLocked = 10.00 — para ser buyer em UM settlement
                 *   vibLocked =  1.00 — para ser seller em UM settlement
                 * Ambas as funções são exercidas por carteiras diferentes em cada thread.
                 */
                Wallet walletA = createWalletWithLockedFunds(TOTAL_BRL, MATCH_AMOUNT);
                Wallet walletB = createWalletWithLockedFunds(TOTAL_BRL, MATCH_AMOUNT);

                // Snapshot do saldo total antes — deve ser preservado após os dois settlements.
                // totalBrl(walletA) = brlAvailable(0) + brlLocked(10) = 10
                // totalBrl(walletB) = brlAvailable(0) + brlLocked(10) = 10 → TOTAL = 20
                BigDecimal totalBrlBefore = totalBrl(walletA).add(totalBrl(walletB));
                BigDecimal totalVibBefore = totalVib(walletA).add(totalVib(walletB));

                // Limpa o outbox para contar apenas eventos gerados nesta iteração.
                outboxMessageRepository.deleteAll();

                /*
                 * Latch de 2 estágios para início simultâneo DETERMINÍSTICO:
                 *   readyLatch(2): cada thread decrementa ao ficar pronta para executar.
                 *   startLatch(1): o main thread libera SOMENTE quando ambas estão prontas.
                 *
                 * Isso maximiza a contenção de lock — as duas transações começam
                 * praticamente ao mesmo instante, reproduzindo o cenário de produção.
                 */
                CountDownLatch readyLatch = new CountDownLatch(2);
                CountDownLatch startLatch = new CountDownLatch(1);
                List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
                ExecutorService executor = Executors.newFixedThreadPool(2);

                // Thread 1: settleFunds(buyer=A, seller=B)
                // Com lock ordering: adquire min(idA, idB) primeiro → sem espera circular.
                executor.submit(() -> {
                    readyLatch.countDown();
                    try {
                        startLatch.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    try {
                        walletService.settleFunds(
                                buildCmd(walletA.getId(), walletB.getId()),
                                UUID.randomUUID().toString()
                        );
                    } catch (Throwable t) {
                        errors.add(t);
                    }
                });

                // Thread 2: settleFunds(buyer=B, seller=A) — BUYER/SELLER INVERTIDOS
                // Sem lock ordering, isso causaria o deadlock ABBA da FASE RED.
                // Com lock ordering, ambos adquirem min(idA, idB) → serialização correta.
                executor.submit(() -> {
                    readyLatch.countDown();
                    try {
                        startLatch.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    try {
                        walletService.settleFunds(
                                buildCmd(walletB.getId(), walletA.getId()),
                                UUID.randomUUID().toString()
                        );
                    } catch (Throwable t) {
                        errors.add(t);
                    }
                });

                // Aguarda ambas as threads estarem prontas e dispara o start simultâneo.
                assertThat(readyLatch.await(10, TimeUnit.SECONDS))
                        .as("[GREEN-01 iter=%d] Threads não ficaram prontas em 10s", iter)
                        .isTrue();
                startLatch.countDown();

                executor.shutdown();
                assertThat(executor.awaitTermination(30, TimeUnit.SECONDS))
                        .as("""
                            [GREEN-01 iter=%d] Executor não completou em 30s.
                            Possível deadlock não detectado — verificar lock ordering em WalletService.settleFunds().
                            """, iter)
                        .isTrue();

                // ------- Asserção 1: Zero exceções / zero rollbacks -------
                // Com lock ordering, nenhuma transação deve ser cancelada por deadlock.
                assertThat(errors)
                        .as("[GREEN-01 iter=%d] Nenhuma exceção/rollback esperado com lock ordering ativo", iter)
                        .isEmpty();

                // ------- Asserção 2: Zero FundsSettlementFailedEvent -------
                // Confirma que NENHUM settlement falhou por saldo insuficiente ou lock error.
                assertThat(outboxMessageRepository.findAll())
                        .filteredOn(m -> "FundsSettlementFailedEvent".equals(m.getEventType()))
                        .as("[GREEN-01 iter=%d] Zero FundsSettlementFailedEvent esperado", iter)
                        .isEmpty();

                // ------- Asserção 3: Exatamente 2 FundsSettledEvent -------
                // Ambos os settlements devem ter completado e gravado o evento no outbox.
                assertThat(outboxMessageRepository.findAll())
                        .filteredOn(m -> "FundsSettledEvent".equals(m.getEventType()))
                        .as("[GREEN-01 iter=%d] Exatamente 2 FundsSettledEvent esperados (um por thread)", iter)
                        .hasSize(2);

                // ------- Asserção 4: Conservação de valor -------
                // Lei de conservação: BRL e VIB totais devem ser iguais antes e depois.
                // Cada settlement move saldo entre campos (locked ↔ available) MAS não
                // cria nem destrói valor — o total permanece invariante.
                Wallet updatedA = walletRepository.findById(walletA.getId()).orElseThrow();
                Wallet updatedB = walletRepository.findById(walletB.getId()).orElseThrow();

                BigDecimal totalBrlAfter = totalBrl(updatedA).add(totalBrl(updatedB));
                BigDecimal totalVibAfter = totalVib(updatedA).add(totalVib(updatedB));

                assertThat(totalBrlAfter)
                        .as("[GREEN-01 iter=%d] Conservação de BRL violada: antes=%s, depois=%s",
                                iter, totalBrlBefore, totalBrlAfter)
                        .isEqualByComparingTo(totalBrlBefore);

                assertThat(totalVibAfter)
                        .as("[GREEN-01 iter=%d] Conservação de VIB violada: antes=%s, depois=%s",
                                iter, totalVibBefore, totalVibAfter)
                        .isEqualByComparingTo(totalVibBefore);
            }
        }
    }

    // =========================================================================
    // Alta Contenção — 10 carteiras, 50 settlements concorrentes
    // =========================================================================

    /**
     * Teste de alta contenção com múltiplas carteiras e settlements simultâneos.
     *
     * <p>Stress-testa o lock ordering num cenário próximo ao de produção:
     * múltiplas carteiras com pares aleatórios de buyer/seller, submetidos
     * em paralelo máximo para maximizar a probabilidade de contenção.</p>
     */
    @Nested
    @DisplayName("Alta Contenção — 10 carteiras × 50 settlements concorrentes (pares aleatórios)")
    class AltaContencao {

        /** Número de carteiras envolvidas. */
        private static final int WALLET_COUNT = 10;

        /** Número total de settlements concorrentes. */
        private static final int SETTLEMENT_COUNT = 50;

        /**
         * <b>AT-03.2.HC-01</b>
         *
         * <p>Submete 50 settlements concorrentes sobre 10 carteiras com pares
         * aleatórios (semente fixa = 42 para reprodutibilidade). O lock ordering
         * determinístico deve serializar corretamente todos os acessos, sem
         * nenhum deadlock ou falha de saldo.</p>
         *
         * <h3>Pré-condição de saldo inicial</h3>
         * <p>Cada carteira recebe {@code brlLocked = 50 × TOTAL_BRL = 500.00} e
         * {@code vibLocked = 50 × MATCH_AMOUNT = 50.00} — saldo suficiente mesmo no
         * caso extremo de uma carteira ser buyer em todos os 50 settlements.
         * Isso garante que nenhum {@code FundsSettlementFailedEvent} seja causado
         * por saldo insuficiente, isolando o invariante testado (deadlock vs. negócio).</p>
         *
         * <h3>Conservação global de valor</h3>
         * <pre>
         *   BRL antes: 10 carteiras × 50 × 10.00 = 5000.00
         *   BRL após:  redistribuído entre available/locked mas TOTAL = 5000.00 ✓
         *
         *   VIB antes: 10 carteiras × 50 × 1.00 = 500.00
         *   VIB após:  redistribuído entre available/locked mas TOTAL = 500.00 ✓
         * </pre>
         */
        @Test
        @Timeout(180) // 50 settlements serializados no pior caso × ~3s/lock timeout = 150s + margem
        @DisplayName("HC-01 — 10 carteiras × 50 settlements concorrentes: zero deadlocks, zero falhas, conservação global")
        void highContention_tenWallets_fiftyConcurrentSettlements_zeroDeadlocks() throws Exception {

            /*
             * Saldo inicial seguro para o pior caso:
             *   - uma única carteira sendo buyer em todos os 50 settlements.
             * Mesmo nesse caso extremo, os fundos nunca se esgotam durante a execução.
             */
            BigDecimal safeBrlLocked = TOTAL_BRL.multiply(new BigDecimal(SETTLEMENT_COUNT));
            BigDecimal safeVibLocked = MATCH_AMOUNT.multiply(new BigDecimal(SETTLEMENT_COUNT));

            // Cria as 10 carteiras com saldo pré-fundido
            List<Wallet> wallets = new ArrayList<>(WALLET_COUNT);
            for (int i = 0; i < WALLET_COUNT; i++) {
                wallets.add(createWalletWithLockedFunds(safeBrlLocked, safeVibLocked));
            }

            // Snapshot do saldo global antes — invariante de conservação
            BigDecimal totalBrlBefore = wallets.stream()
                    .map(this::totalBrlWallet)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalVibBefore = wallets.stream()
                    .map(this::totalVibWallet)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            outboxMessageRepository.deleteAll();

            /*
             * Gera 50 pares (buyer, seller) com semente fixa (42L) para garantir
             * reprodutibilidade total entre execuções de CI:
             * - A mesma semente sempre gera os mesmos pares.
             * - Garante buyer ≠ seller em cada par (loop de rejeição).
             */
            Random rng = new Random(42L);
            List<int[]> pairs = new ArrayList<>(SETTLEMENT_COUNT);
            for (int i = 0; i < SETTLEMENT_COUNT; i++) {
                int buyer = rng.nextInt(WALLET_COUNT);
                int seller;
                do {
                    seller = rng.nextInt(WALLET_COUNT);
                } while (seller == buyer);
                pairs.add(new int[]{buyer, seller});
            }

            // Latch de 2 estágios: todos os 50 threads ficam prontos antes do start simultâneo.
            // Isso maximiza a contenção — todos os 50 settlements têm início quase simultâneo.
            CountDownLatch readyLatch = new CountDownLatch(SETTLEMENT_COUNT);
            CountDownLatch startLatch = new CountDownLatch(1);
            List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
            AtomicInteger completedCount = new AtomicInteger(0);

            // Um thread por settlement = contenção máxima simultânea no banco.
            ExecutorService executor = Executors.newFixedThreadPool(SETTLEMENT_COUNT);

            for (int[] pair : pairs) {
                final UUID buyerId  = wallets.get(pair[0]).getId();
                final UUID sellerId = wallets.get(pair[1]).getId();
                final String msgId  = UUID.randomUUID().toString();

                executor.submit(() -> {
                    readyLatch.countDown();
                    try {
                        startLatch.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    try {
                        walletService.settleFunds(buildCmd(buyerId, sellerId), msgId);
                        completedCount.incrementAndGet();
                    } catch (Throwable t) {
                        errors.add(t);
                    }
                });
            }

            // Todos os 50 threads prontos → disparo simultâneo
            assertThat(readyLatch.await(30, TimeUnit.SECONDS))
                    .as("[HC-01] %d threads não ficaram prontas em 30s", SETTLEMENT_COUNT)
                    .isTrue();
            startLatch.countDown();

            executor.shutdown();
            assertThat(executor.awaitTermination(120, TimeUnit.SECONDS))
                    .as("""
                        [HC-01] Executor não completou em 120s.
                        Deadlock não detectado pelo PostgreSQL? Verifique lock ordering em WalletService.settleFunds().
                        """)
                    .isTrue();

            // ------- Asserção 1: Zero exceções em qualquer thread -------
            assertThat(errors)
                    .as("[HC-01] Zero exceções esperadas nos %d settlements concorrentes", SETTLEMENT_COUNT)
                    .isEmpty();

            // ------- Asserção 2: Todos os 50 settlements completaram -------
            assertThat(completedCount.get())
                    .as("[HC-01] Todos os %d settlements devem completar com sucesso", SETTLEMENT_COUNT)
                    .isEqualTo(SETTLEMENT_COUNT);

            // ------- Asserção 3: Zero FundsSettlementFailedEvent -------
            // Qualquer evento desse tipo indicaria falha real (deadlock causou rollback
            // que foi capturado pelo WalletService e transformado em evento compensatório).
            assertThat(outboxMessageRepository.findAll())
                    .filteredOn(m -> "FundsSettlementFailedEvent".equals(m.getEventType()))
                    .as("[HC-01] Zero FundsSettlementFailedEvent esperado — sem falhas de liquidação")
                    .isEmpty();

            // ------- Asserção 4: Exatamente 50 FundsSettledEvent -------
            assertThat(outboxMessageRepository.findAll())
                    .filteredOn(m -> "FundsSettledEvent".equals(m.getEventType()))
                    .as("[HC-01] Exatamente %d FundsSettledEvent esperados", SETTLEMENT_COUNT)
                    .hasSize(SETTLEMENT_COUNT);

            // ------- Asserção 5: Conservação global de valor -------
            // Recarrega todas as 10 carteiras do banco para estado pós-settlement.
            List<UUID> walletIds = wallets.stream().map(Wallet::getId).toList();
            List<Wallet> updatedWallets = walletRepository.findAllById(walletIds);

            BigDecimal totalBrlAfter = updatedWallets.stream()
                    .map(this::totalBrlWallet)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalVibAfter = updatedWallets.stream()
                    .map(this::totalVibWallet)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            assertThat(totalBrlAfter)
                    .as("[HC-01] Conservação global de BRL violada: antes=%s, depois=%s",
                            totalBrlBefore, totalBrlAfter)
                    .isEqualByComparingTo(totalBrlBefore);

            assertThat(totalVibAfter)
                    .as("[HC-01] Conservação global de VIB violada: antes=%s, depois=%s",
                            totalVibBefore, totalVibAfter)
                    .isEqualByComparingTo(totalVibBefore);
        }

        // Helpers de saldo para o escopo desta classe inner (evita acesso direto a campos da outer class)
        private BigDecimal totalBrlWallet(Wallet w) {
            return w.getBrlAvailable().add(w.getBrlLocked());
        }

        private BigDecimal totalVibWallet(Wallet w) {
            return w.getVibAvailable().add(w.getVibLocked());
        }
    }
}
