package com.vibranium.walletservice.security;

import com.vibranium.walletservice.e2e.E2eDataSeederController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifica que o perfil {@code e2e} continua funcional após a migração de
 * {@link E2eSecurityConfig} e {@link E2eDataSeederController} para {@code src/test/java}.
 *
 * <p>Com {@code @ActiveProfiles("e2e")}:</p>
 * <ul>
 *   <li>{@link E2eSecurityConfig} deve ser ativado (JwtDecoder sem validação)</li>
 *   <li>{@link E2eDataSeederController} deve expor {@code /e2e/setup/wallets}</li>
 *   <li>Requisições ao endpoint E2E não exigem token JWT (permitAll)</li>
 * </ul>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // Desabilita issuer-uri (sem Keycloak em teste E2E)
                "spring.security.oauth2.resourceserver.jwt.issuer-uri="
        }
)
@ActiveProfiles("e2e")
@AutoConfigureMockMvc
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("E2eProfileStillWorksTest — Perfil e2e funciona com classes em src/test")
class E2eProfileStillWorksTest {

    // =========================================================================
    // Testcontainers — mesma infra da AbstractIntegrationTest
    // =========================================================================

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15-alpine")
                    .withDatabaseName("vibranium_wallet_e2e_test")
                    .withUsername("test")
                    .withPassword("test");

    static final RabbitMQContainer RABBIT =
            new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    static {
        POSTGRES.start();
        RABBIT.start();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    }

    // =========================================================================
    // Beans injetados
    // =========================================================================

    @Autowired
    private ApplicationContext context;

    @Autowired
    private MockMvc mockMvc;

    // =========================================================================
    // Testes
    // =========================================================================

    @Test
    @DisplayName("E2eSecurityConfig DEVE estar ativo com profile 'e2e'")
    void e2eSecurityConfigBeanShouldBeActive() {
        assertThat(context.getBeanNamesForType(E2eSecurityConfig.class))
                .as("E2eSecurityConfig deve ser instanciado com profile 'e2e'")
                .isNotEmpty();
    }

    @Test
    @DisplayName("E2eDataSeederController DEVE estar ativo com profile 'e2e'")
    void e2eDataSeederControllerBeanShouldBeActive() {
        assertThat(context.getBeanNamesForType(E2eDataSeederController.class))
                .as("E2eDataSeederController deve ser instanciado com profile 'e2e'")
                .isNotEmpty();
    }

    @Test
    @DisplayName("Endpoint /e2e/setup/wallets deve ser acessível sem autenticação")
    void e2eEndpointShouldBeAccessibleWithoutAuth() throws Exception {
        mockMvc.perform(post("/e2e/setup/wallets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[]"))
                .andExpect(status().isCreated());
    }
}
