package com.vibranium.walletservice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Teste de sanidade: verifica se o contexto Spring Boot inicializa corretamente.
 *
 * <p>Estende {@link AbstractIntegrationTest} para reutilizar os containers
 * Testcontainers (PostgreSQL + RabbitMQ) já gerenciados na classe base,
 * evitando tentativas de conexão em localhost:5432 que causariam falha
 * quando não há banco externo disponível no ambiente de CI.
 */
@DisplayName("WalletServiceApplicationTest - Testa inicialização da aplicação")
class WalletServiceApplicationTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("contexto da aplicação deve iniciar sem erros")
    void contextLoads() {
        // Este teste apenas verifica se o Spring Boot consegue carregar o contexto
        assertThat(true).isTrue();
    }
}
