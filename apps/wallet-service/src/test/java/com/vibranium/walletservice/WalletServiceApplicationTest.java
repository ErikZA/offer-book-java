package com.vibranium.walletservice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Teste simples para verificar se a aplicação Spring Boot inicia corretamente.
 */
@SpringBootTest
@DisplayName("WalletServiceApplicationTest - Testa inicialização da aplicação")
class WalletServiceApplicationTest {

    @Test
    @DisplayName("contexto da aplicação deve iniciar sem erros")
    void contextLoads() {
        // Este teste apenas verifica se o Spring Boot consegue carregar o contexto
        assertThat(true).isTrue();
    }
}
