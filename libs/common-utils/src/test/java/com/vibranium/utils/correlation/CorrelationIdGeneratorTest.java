package com.vibranium.utils.correlation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes unitários para {@link CorrelationIdGenerator}.
 *
 * <p>Fase RED: os testes devem FALHAR até que a implementação exista.</p>
 *
 * <p>Contrato verificado:</p>
 * <ul>
 *   <li>Geração de UUIDs v4 (variant = RFC 4122, version = 4)</li>
 *   <li>Unicidade estatística — 1000 IDs gerados não devem colidir</li>
 *   <li>Representação String no formato UUID padrão</li>
 *   <li>Classe não instanciável (utilitário estático final)</li>
 * </ul>
 */
@DisplayName("CorrelationIdGenerator — geração de IDs de correlação UUID v4")
class CorrelationIdGeneratorTest {

    // -------------------------------------------------------------------------
    // Casos de teste — formato e versão UUID
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("generate() deve retornar um UUID não-nulo")
    void generate_shouldReturnNonNull() {
        UUID id = CorrelationIdGenerator.generate();

        assertThat(id).isNotNull();
    }

    @Test
    @DisplayName("generate() deve retornar UUID version 4 (random)")
    void generate_shouldReturnUuidVersion4() {
        UUID id = CorrelationIdGenerator.generate();

        // Version 4 = random UUID (RFC 4122)
        assertThat(id.version()).isEqualTo(4);
    }

    @Test
    @DisplayName("generate() deve retornar UUID com variant IETF RFC 4122 (variant = 2)")
    void generate_shouldReturnUuidWithIetfVariant() {
        UUID id = CorrelationIdGenerator.generate();

        // IETF RFC 4122 variant = 2
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    @DisplayName("generateAsString() deve retornar representação UUID no formato padrão")
    void generateAsString_shouldReturnStandardUuidFormat() {
        String id = CorrelationIdGenerator.generateAsString();

        // Formato: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
        assertThat(id).matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    }

    // -------------------------------------------------------------------------
    // Casos de teste — unicidade estatística
    // -------------------------------------------------------------------------

    @RepeatedTest(3)
    @DisplayName("generate() deve produzir 1000 IDs únicos consecutivos")
    void generate_shouldProduceUniqueIds() {
        Set<UUID> ids = IntStream.range(0, 1000)
                .mapToObj(i -> CorrelationIdGenerator.generate())
                .collect(Collectors.toSet());

        assertThat(ids).hasSize(1000);
    }

    // -------------------------------------------------------------------------
    // Casos de teste — contrato de utilitário
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("CorrelationIdGenerator não deve possuir construtor público")
    void correlationIdGenerator_shouldNotBePubliclyInstantiable() {
        var constructors = CorrelationIdGenerator.class.getDeclaredConstructors();
        assertThat(constructors).hasSize(1);
        assertThat(constructors[0].getModifiers())
                .isEqualTo(java.lang.reflect.Modifier.PRIVATE);
    }
}
