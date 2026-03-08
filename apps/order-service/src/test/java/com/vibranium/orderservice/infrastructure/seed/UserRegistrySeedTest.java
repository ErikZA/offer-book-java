package com.vibranium.orderservice.infrastructure.seed;

import com.vibranium.orderservice.domain.model.UserRegistry;
import com.vibranium.orderservice.domain.repository.UserRegistryRepository;
import com.vibranium.orderservice.integration.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes de integração para o {@link UserRegistrySeedRunner}.
 *
 * <p>Valida que usuários pré-importados no Keycloak via realm-export.json
 * são automaticamente inseridos na {@code tb_user_registry} nos profiles
 * de teste/staging/perf.</p>
 */
class UserRegistrySeedTest extends AbstractIntegrationTest {

    private static final String TESTER_KEYCLOAK_ID = "00000000-0000-0000-0000-000000000001";

    @Autowired
    private UserRegistryRepository userRegistryRepository;

    @Autowired
    private UserRegistrySeedRunner seedRunner;

    @BeforeEach
    void cleanUp() {
        userRegistryRepository.deleteAll();
    }

    @Test
    @DisplayName("Usuário importado no Keycloak deve existir em tb_user_registry após seed")
    void shouldHaveTestUserInRegistryAfterSeed() {
        // WHEN: Seed/reconciliation é executado
        seedRunner.run(null);

        // THEN: tb_user_registry contém registro para 'tester'
        Optional<UserRegistry> user = userRegistryRepository.findByKeycloakId(TESTER_KEYCLOAK_ID);
        assertThat(user).isPresent();
        assertThat(user.get().getKeycloakId()).isEqualTo(TESTER_KEYCLOAK_ID);
    }

    @Test
    @DisplayName("Seed deve ser idempotente — não gerar duplicatas")
    void shouldBeIdempotent() {
        // Executar seed 2x
        seedRunner.run(null);
        seedRunner.run(null);

        long count = userRegistryRepository.countByKeycloakId(TESTER_KEYCLOAK_ID);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("Seed não deve sobrescrever registro existente")
    void shouldNotOverwriteExistingRecord() {
        // GIVEN: Usuário já existe (simulando registro via evento REGISTER)
        userRegistryRepository.save(new UserRegistry(TESTER_KEYCLOAK_ID));
        long countBefore = userRegistryRepository.count();

        // WHEN: Seed é executado
        seedRunner.run(null);

        // THEN: Contagem não mudou
        assertThat(userRegistryRepository.count()).isEqualTo(countBefore);
    }
}
