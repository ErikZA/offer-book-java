package com.vibranium.orderservice.infrastructure.seed;

import com.vibranium.orderservice.domain.model.UserRegistry;
import com.vibranium.orderservice.domain.repository.UserRegistryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Seed de usuários pré-importados no Keycloak para ambientes de teste/performance.
 *
 * <p>Resolve o BUG-02: quando o realm é importado via {@code realm-export.json},
 * o Keycloak NÃO dispara o evento {@code REGISTER} — o usuário existe no Keycloak
 * mas não na {@code tb_user_registry}. Este runner insere os usuários conhecidos
 * de forma idempotente no startup do serviço.</p>
 *
 * <p>Ativo APENAS nos profiles {@code staging}, {@code perf} e {@code test}.
 * Em produção, o fluxo event-driven via RabbitMQ permanece como fonte única.</p>
 *
 * <p>Os IDs são fixos e correspondem aos definidos no {@code realm-export.json}
 * (campo {@code id} do usuário). Essa abordagem é self-contained e não requer
 * chamadas ao Keycloak Admin API em runtime.</p>
 */
@Component
@Profile({"staging", "perf", "test"})
public class UserRegistrySeedRunner implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(UserRegistrySeedRunner.class);

    /**
     * IDs de usuários pré-importados no Keycloak via realm-export.json.
     * Cada entry: keycloakId (UUID fixo definido no realm-export.json).
     */
    private static final List<String> SEED_KEYCLOAK_IDS = List.of(
            "00000000-0000-0000-0000-000000000001" // tester
    );

    private final UserRegistryRepository userRegistryRepository;

    public UserRegistrySeedRunner(UserRegistryRepository userRegistryRepository) {
        this.userRegistryRepository = userRegistryRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        logger.info("UserRegistrySeedRunner: reconciliando {} usuário(s) pré-importados no Keycloak",
                SEED_KEYCLOAK_IDS.size());

        int reconciled = 0;
        for (String keycloakId : SEED_KEYCLOAK_IDS) {
            if (!isValidUuid(keycloakId)) {
                logger.warn("Seed ignorado — keycloakId inválido (não é UUID): {}", keycloakId);
                continue;
            }
            if (!userRegistryRepository.existsByKeycloakId(keycloakId)) {
                userRegistryRepository.save(new UserRegistry(keycloakId));
                logger.info("Reconciled user tester (keycloakId={})", keycloakId);
                reconciled++;
            } else {
                logger.debug("Seed user já existe (idempotente): keycloakId={}", keycloakId);
            }
        }

        logger.info("UserRegistrySeedRunner: {} usuário(s) reconciliado(s), {} já existiam",
                reconciled, SEED_KEYCLOAK_IDS.size() - reconciled);
    }

    private static boolean isValidUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
