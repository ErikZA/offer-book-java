package com.vibranium.orderservice.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Teste de guarda arquitetural: impede que strings literais de routing key
 * sejam introduzidas fora de {@code RabbitMQConfig}.
 *
 * <p><strong>Regra:</strong> nenhum arquivo {@code .java} fora de
 * {@code RabbitMQConfig.java} pode conter a substring {@code "order.events."}
 * — toda referência a routing key deve usar as constantes declaradas em
 * {@link com.vibranium.orderservice.config.RabbitMQConfig}.</p>
 *
 * <p><strong>Fase RED:</strong> este teste falha enquanto houver ocorrências
 * literais em outros arquivos. A fase GREEN é atingida ao substituir todas as
 * ocorrências pelas constantes correspondentes.</p>
 */
@DisplayName("Guarda arquitetural: routing keys não podem ser strings literais fora de RabbitMQConfig")
class RoutingKeyLiteralTest {

    /** Substring proibida fora de RabbitMQConfig. */
    private static final String FORBIDDEN_LITERAL = "order.events.";

    /**
     * Arquivos excluídos da verificação: o próprio
     * {@code RabbitMQConfig.java} (fonte única da verdade) e este próprio
     * arquivo de teste (que referencia o literal apenas nesta constante
     * privada acima para a comparação).
     */
    private static final List<String> EXCLUDED_FILES = List.of(
            "RabbitMQConfig.java",
            "RoutingKeyLiteralTest.java"
    );

    @Test
    @DisplayName("Nenhum arquivo .java fora de RabbitMQConfig deve conter literal 'order.events.'")
    void noRoutingKeyLiteralsOutsideRabbitMQConfig() throws IOException {

        // Caminho relativo ao diretório de execução do Maven (apps/order-service/)
        Path srcRoot = Paths.get("src");

        List<String> violations;
        try (Stream<Path> walk = Files.walk(srcRoot)) {
            violations = walk
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> EXCLUDED_FILES.stream()
                            .noneMatch(excluded -> p.getFileName().toString().equals(excluded)))
                    .filter(p -> {
                        try {
                            return Files.readString(p).contains(FORBIDDEN_LITERAL);
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .map(p -> p.toString().replace('\\', '/'))
                    .sorted()
                    .collect(Collectors.toList());
        }

        assertThat(violations)
                .as("""
                        Strings literais '%s' encontradas fora de RabbitMQConfig.
                        Substitua por constantes de RabbitMQConfig (ex.: RabbitMQConfig.RK_ORDER_RECEIVED).
                        Arquivos em violação:
                        """.formatted(FORBIDDEN_LITERAL))
                .isEmpty();
    }
}
