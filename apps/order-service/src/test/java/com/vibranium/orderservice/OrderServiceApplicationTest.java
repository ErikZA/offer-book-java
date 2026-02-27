package com.vibranium.orderservice;

import com.vibranium.orderservice.integration.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test: verifica se o contexto Spring Boot sobe corretamente com os containers Testcontainers.
 * Estende AbstractIntegrationTest para usar o @DynamicPropertySource com credenciais dos containers.
 */
@DisplayName("OrderServiceApplicationTest - Testa inicialização da aplicação")
class OrderServiceApplicationTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("contexto da aplicação deve iniciar sem erros")
    void contextLoads() {
        // Este teste apenas verifica se o Spring Boot consegue carregar o contexto
        assertThat(true).isTrue();
    }
}
