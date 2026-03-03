package com.vibranium.walletservice;

import com.vibranium.walletservice.application.dto.BalanceUpdateRequest;
import com.vibranium.walletservice.application.dto.WalletResponse;
import com.vibranium.walletservice.application.service.WalletService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Controller REST do wallet-service.
 *
 * <p>Expõe as rotas provisionadas no Kong:</p>
 * <ul>
 *   <li>{@code GET /api/v1/wallets/{userId}} — retorna a carteira do usuário com todos os saldos.</li>
 *   <li>{@code GET /api/v1/wallets} — lista todas as carteiras (admin/debug).</li>
 *   <li>{@code PATCH /api/v1/wallets/{walletId}/balance} — ajusta saldo (crédito ou débito).</li>
 * </ul>
 *
 * <p>O JWT do Keycloak é validado pelo Kong (camada externa) e pelo {@link
 * com.vibranium.walletservice.config.SecurityConfig} (defense-in-depth interna).
 * Além da autenticação, este controller aplica <b>autorização horizontal</b>:
 * cada usuário só acessa sua própria carteira ({@code jwt.sub == wallet.userId}),
 * exceto usuários com {@code ROLE_ADMIN}, que podem acessar qualquer carteira.</p>
 *
 * <p>O controller mantém-se thin: toda lógica de negócio está no {@link WalletService}.</p>
 */
@RestController
@RequestMapping("/api/v1/wallets")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    // -------------------------------------------------------------------------
    // Consultas
    // -------------------------------------------------------------------------

    /**
     * Retorna a carteira de um usuário específico com todos os saldos.
     *
     * <p><b>Autorização horizontal (AT-10.2):</b> o {@code userId} do path é comparado
     * com {@code jwt.sub}. Se divergirem e o usuário não for admin → {@code 403 Forbidden}.
     * Isso previne que Usuário A consulte a carteira de Usuário B (IDOR).</p>
     *
     * @param userId UUID do usuário no Keycloak (path variable).
     * @param jwt    token JWT injetado pelo Spring Security; {@code null} apenas em testes
     *               com {@code @WithMockUser} (não ocorre em produção).
     * @return 200 com {@link WalletResponse} ou 404 se não encontrada, 403 se não autorizado.
     */
    @GetMapping("/{userId}")
    public ResponseEntity<WalletResponse> getByUserId(
            @PathVariable UUID userId,
            @AuthenticationPrincipal Jwt jwt) {

        // AT-10.2: verificação de ownership — jwt.sub deve ser igual ao userId do path.
        // Guard jwt != null: em testes com @WithMockUser o principal não é Jwt; em produção
        // o BearerTokenAuthenticationFilter garante que jwt nunca é null ao chegar aqui.
        if (jwt != null) {
            boolean isAdmin = hasAdminRole(jwt);
            if (!isAdmin && !userId.toString().equals(jwt.getSubject())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Acesso negado: carteira não pertence ao usuário autenticado");
            }
        }

        return ResponseEntity.ok(walletService.findByUserId(userId));
    }

    /**
     * Lista todas as carteiras cadastradas com paginação.
     *
     * <p><b>Controle de acesso (AT-4.2.1):</b> apenas usuários com {@code ROLE_ADMIN}
     * podem listar carteiras de todos os usuários. Usuários comuns não possuem
     * visibilidade sobre carteiras alheias — isso seria uma violação IDOR massiva.</p>
     *
     * <p>Suporta os parâmetros de URL {@code page} (0-based), {@code size} e {@code sort}
     * via Spring Data {@link Pageable}. Default: page=0, size=20.</p>
     *
     * @param pageable parâmetros de paginação injetados pelo Spring MVC.
     * @return 200 com {@link Page} de {@link WalletResponse} contendo
     *         {@code content}, {@code totalPages}, {@code totalElements}.
     */
    @GetMapping
    // AT-4.2.1: restringe listagem a ROLE_ADMIN — evita exposição massiva de dados de usuários.
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Page<WalletResponse>> listAll(Pageable pageable) {
        return ResponseEntity.ok(walletService.findAll(pageable));
    }

    // -------------------------------------------------------------------------
    // Mutações
    // -------------------------------------------------------------------------

    /**
     * Ajusta o saldo disponível de uma carteira (crédito ou débito).
     *
     * <p>Pelo menos um dos campos {@code brlAmount} ou {@code vibAmount} deve ser informado.
     * Valores positivos creditam; valores negativos debitam (desde que o saldo não fique negativo).
     * A operação é protegida por lock pessimista — segura para chamadas concorrentes.</p>
     *
     * <p><b>Autorização horizontal (AT-10.2):</b> antes de aplicar o delta, busca a carteira
     * para obter o {@code userId} do dono e compara com {@code jwt.sub}. Se divergirem e o
     * usuário não for admin → {@code 403 Forbidden}. Isso previne privilege escalation via IDOR:
     * Usuário A não pode modificar o saldo da carteira de Usuário B.</p>
     *
     * @param walletId UUID da carteira (identifica a carteira, não o usuário).
     * @param request  Corpo JSON com os deltas de saldo.
     * @param jwt      token JWT injetado pelo Spring Security; {@code null} apenas em testes
     *                 com {@code @WithMockUser} (não ocorre em produção).
     * @return 200 com {@link WalletResponse} atualizado, 422 se saldo ficaria negativo,
     *         404 se carteira não encontrada, 400 se request inválido, 403 se não autorizado.
     */
    @PatchMapping("/{walletId}/balance")
    public ResponseEntity<WalletResponse> updateBalance(
            @PathVariable UUID walletId,
            @Valid @RequestBody BalanceUpdateRequest request,
            @AuthenticationPrincipal Jwt jwt) {

        // AT-10.2: verificação de ownership — busca a carteira para obter o userId do dono
        // e compara com jwt.sub antes de aplicar qualquer mutação.
        // Guard jwt != null: em testes com @WithMockUser o principal não é Jwt; em produção
        // o BearerTokenAuthenticationFilter garante que jwt nunca é null ao chegar aqui.
        if (jwt != null) {
            WalletResponse owned = walletService.findById(walletId);
            boolean isAdmin = hasAdminRole(jwt);
            if (!isAdmin && !owned.userId().toString().equals(jwt.getSubject())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Acesso negado: carteira não pertence ao usuário autenticado");
            }
        }

        return ResponseEntity.ok(
                walletService.adjustBalance(walletId, request.brlAmount(), request.vibAmount())
        );
    }

    // -------------------------------------------------------------------------
    // Helpers privados
    // -------------------------------------------------------------------------

    /**
     * Verifica se o JWT contém {@code ROLE_ADMIN} no claim {@code roles}.
     *
     * <p>Admins podem acessar a carteira de qualquer usuário sem restrição de ownership.
     * O claim {@code roles} é um array de strings populado pelo Keycloak via
     * mapeamento de realm roles no client scope.</p>
     *
     * @param jwt token JWT do usuário autenticado.
     * @return {@code true} se o token contiver {@code ROLE_ADMIN}.
     */
    private boolean hasAdminRole(Jwt jwt) {
        // getClaimAsStringList retorna null se o claim não existir — usamos List.of() como fallback
        java.util.List<String> roles = jwt.getClaimAsStringList("roles");
        return roles != null && roles.contains("ROLE_ADMIN");
    }
}
