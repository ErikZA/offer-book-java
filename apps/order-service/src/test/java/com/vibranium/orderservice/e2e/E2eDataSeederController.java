package com.vibranium.orderservice.e2e;

import com.vibranium.orderservice.domain.model.UserRegistry;
import com.vibranium.orderservice.domain.repository.UserRegistryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Controller de setup de dados exclusivo para o perfil {@code e2e}.
 *
 * <p>Registra usuários fictícios no {@code tb_user_registry} do order-service
 * para que as ordens submetidas pelo {@code SagaEndToEndIT} passem na validação
 * de existência de usuário ({@code OrderCommandService.placeOrder}).</p>
 *
 * <p>Este controller é instanciado APENAS quando {@code SPRING_PROFILES_ACTIVE=e2e}.
 * Não deve existir em builds de produção — use o plug-in Keycloak RabbitMQ para
 * o fluxo de registro real.</p>
 *
 * <p>Endpoint: {@code POST /e2e/setup/users}</p>
 */
@RestController
@RequestMapping("/e2e/setup")
@Profile("e2e")
public class E2eDataSeederController {

    private static final Logger logger = LoggerFactory.getLogger(E2eDataSeederController.class);

    private final UserRegistryRepository userRegistryRepository;

    /**
     * @param userRegistryRepository Repositório JPA da tabela {@code tb_user_registry}.
     */
    public E2eDataSeederController(UserRegistryRepository userRegistryRepository) {
        this.userRegistryRepository = userRegistryRepository;
    }

    /**
     * Registra uma lista de user IDs (UUIDs string) no registry local do order-service.
     *
     * <p>Idempotente: ignora IDs já existentes no registry (não lança exceção).</p>
     *
     * @param userIds Lista de keycloak IDs (UUIDs em formato string) a registrar.
     * @return Confirmação com quantidade de usuários registrados.
     */
    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> registerUsers(@RequestBody List<String> userIds) {
        logger.info("[E2E] Registrando {} usuários no order-service registry", userIds.size());

        int registered = 0;
        for (String userId : userIds) {
            // Idempotência: verifica antes de inserir para evitar ConstraintViolationException
            if (!userRegistryRepository.existsByKeycloakId(userId)) {
                userRegistryRepository.save(new UserRegistry(userId));
                registered++;
                logger.info("[E2E] Usuário registrado: keycloakId={}", userId);
            } else {
                logger.info("[E2E] Usuário já registrado (skipping): keycloakId={}", userId);
            }
        }

        return Map.of(
                "registered", registered,
                "total", userIds.size(),
                "status", "OK"
        );
    }
}
