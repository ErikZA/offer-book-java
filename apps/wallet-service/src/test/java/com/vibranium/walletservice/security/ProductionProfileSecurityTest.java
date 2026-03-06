package com.vibranium.walletservice.security;

import com.vibranium.walletservice.AbstractIntegrationTest;
import com.vibranium.walletservice.e2e.E2eDataSeederController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifica que beans E2E NÃO existem quando o perfil {@code e2e} NÃO está ativo.
 *
 * <p>Herda de {@link AbstractIntegrationTest} que usa {@code @ActiveProfiles("test")}
 * e {@code @WithMockUser}. Com este perfil, nem {@code E2eSecurityConfig} nem
 * {@code E2eDataSeederController} devem ser instanciados.</p>
 */
@AutoConfigureMockMvc
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
    @DisplayName("Endpoint /e2e/setup/wallets deve retornar 404 com profile 'test'")
    void e2eEndpointShouldReturn404() throws Exception {
        mockMvc.perform(post("/e2e/setup/wallets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[]"))
                .andExpect(status().isNotFound());
    }
}
