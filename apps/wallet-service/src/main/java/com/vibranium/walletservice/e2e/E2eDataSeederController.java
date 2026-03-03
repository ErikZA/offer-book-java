package com.vibranium.walletservice.e2e;

import com.vibranium.walletservice.application.dto.WalletResponse;
import com.vibranium.walletservice.application.service.WalletService;
import com.vibranium.walletservice.domain.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Controller de setup de dados exclusivo para o perfil {@code e2e}.
 *
 * <p>Cria carteiras e deposita fundos para os usuários fictícios do
 * {@code SagaEndToEndIT}. Expõe um único endpoint de setup que:</p>
 * <ol>
 *   <li>Cria carteiras para cada usuário listado (se não existirem).</li>
 *   <li>Credita os saldos BRL/VIB conforme o payload recebido.</li>
 *   <li>Retorna os wallet IDs para uso no teste E2E.</li>
 * </ol>
 *
 * <p>Este controller é instanciado APENAS quando {@code SPRING_PROFILES_ACTIVE=e2e}.
 * Não deve existir em builds de produção.</p>
 */
@RestController
@RequestMapping("/e2e/setup")
@Profile("e2e")
public class E2eDataSeederController {

    private static final Logger logger = LoggerFactory.getLogger(E2eDataSeederController.class);

    private final WalletService walletService;
    private final WalletRepository walletRepository;

    /**
     * @param walletService     Serviço de negócio para operações de carteira.
     * @param walletRepository  Repositório JPA para verificar existência prévia.
     */
    public E2eDataSeederController(WalletService walletService,
                                   WalletRepository walletRepository) {
        this.walletService = walletService;
        this.walletRepository = walletRepository;
    }

    /**
     * Cria carteiras com saldo inicial para os usuários de teste E2E.
     *
     * <p>Payload esperado:</p>
     * <pre>{@code
     * [
     *   { "userId": "00000000-0000-4000-8000-000000000001", "brl": 1000.00, "vib": 0.0 },
     *   { "userId": "00000000-0000-4000-8000-000000000002", "brl": 0.0, "vib": 50.0 }
     * ]
     * }</pre>
     *
     * @param requests Lista de definições de usuário + saldo inicial.
     * @return Lista com walletId, userId e saldos criados.
     */
    @PostMapping("/wallets")
    @ResponseStatus(HttpStatus.CREATED)
    public List<Map<String, Object>> setupWallets(@RequestBody List<WalletSetupRequest> requests) {
        logger.info("[E2E] Configurando {} carteiras de teste", requests.size());

        List<Map<String, Object>> results = new ArrayList<>();

        for (WalletSetupRequest req : requests) {
            logger.info("[E2E] Setup carteira userId={} brl={} vib={}", req.userId(), req.brl(), req.vib());

            // Idempotência: cria carteira apenas se ainda não existir para este userId
            // countByUserId == 0 evita ConstraintViolationException em re-execuções do setup
            if (walletRepository.countByUserId(req.userId()) == 0) {
                walletService.createWallet(req.userId(), UUID.randomUUID(), UUID.randomUUID().toString());
                logger.info("[E2E] Carteira criada para userId={}", req.userId());
            } else {
                logger.info("[E2E] Carteira já existe para userId={} — usando existente", req.userId());
            }

            // Busca a carteira (recém-criada ou pré-existente)
            WalletResponse wallet = walletService.findByUserId(req.userId());

            // Credita BRL se fornecido e positivo
            if (req.brl() != null && req.brl().compareTo(BigDecimal.ZERO) > 0) {
                walletService.adjustBalance(wallet.walletId(), req.brl(), null);
                logger.info("[E2E] Creditado {} BRL na carteira {}", req.brl(), wallet.walletId());
            }

            // Credita VIB se fornecido e positivo
            if (req.vib() != null && req.vib().compareTo(BigDecimal.ZERO) > 0) {
                walletService.adjustBalance(wallet.walletId(), null, req.vib());
                logger.info("[E2E] Creditado {} VIB na carteira {}", req.vib(), wallet.walletId());
            }

            // Relê a carteira para obter saldos atualizados
            WalletResponse updated = walletService.findByUserId(req.userId());
            results.add(Map.of(
                    "walletId", updated.walletId().toString(),
                    "userId", updated.userId().toString(),
                    "brlAvailable", updated.brlAvailable(),
                    "vibAvailable", updated.vibAvailable()
            ));
        }

        return results;
    }

    /**
     * DTO interno para o request de setup E2E de carteiras.
     *
     * @param userId UUID do usuário (deve coincidir com o {@code sub} do JWT).
     * @param brl    Saldo BRL a depositar. Nulo ou zero = sem depósito BRL.
     * @param vib    Saldo VIB a depositar. Nulo ou zero = sem depósito VIB.
     */
    public record WalletSetupRequest(UUID userId, BigDecimal brl, BigDecimal vib) {}
}
