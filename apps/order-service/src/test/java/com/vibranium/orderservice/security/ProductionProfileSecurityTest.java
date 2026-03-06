package com.vibranium.orderservice.security;

import com.vibranium.orderservice.e2e.E2eDataSeederController;
import com.vibranium.orderservice.integration.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifica que beans E2E NÃO existem quando o perfil {@code e2e} NÃO está ativo.
 *
 * <p>Herda de {@link AbstractIntegrationTest} que usa {@code @ActiveProfiles("test")}.
 * Com este perfil, nem {@code E2eSecurityConfig} nem {@code E2eDataSeederController}
 * devem ser instanciados (ambos exigem {@code @Profile("e2e")}).</p>
 */
@DisplayName("ProductionProfileSecurityTest — Sem beans E2E em profile não-e2e")
class ProductionProfileSecurityTest extends AbstractIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("E2eSecurityConfig NÃO deve existir com profile 'test'")
    void e2eSecurityConfigBeanShouldNotExist() {
        assertThat(context.getBeanNamesForType(E2eSecurityConfig.class))
                .as("E2eSecurityConfig só deve existir com profile 'e2e'")
                .isEmpty();
    }

    @Test
    @DisplayName("E2eDataSeederController NÃO deve existir com profile 'test'")
    void e2eDataSeederControllerBeanShouldNotExist() {
        assertThat(context.getBeanNamesForType(E2eDataSeederController.class))
                .as("E2eDataSeederController só deve existir com profile 'e2e'")
                .isEmpty();
    }

    @Test
    @DisplayName("Endpoint /e2e/setup/users deve retornar 404 com profile 'test'")
    void e2eEndpointShouldReturn404() throws Exception {
        mockMvc.perform(post("/e2e/setup/users")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[\"e2e00000-0000-4000-8000-000000000099\"]"))
                .andExpect(status().isNotFound());
    }
}
