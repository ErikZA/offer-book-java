package com.vibranium.utils.jackson;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Testes unitários para {@link VibraniumJacksonConfig}.
 *
 * <p>Fase RED: verifica o contrato do utilitário antes de sua implementação.
 * Estes testes devem FALHAR até que a classe seja criada.</p>
 *
 * <p>Contrato verificado:</p>
 * <ul>
 *   <li>Instant deve ser serializado como ISO-8601 (não epoch-millis)</li>
 *   <li>Propriedades desconhecidas devem ser ignoradas na desserialização</li>
 *   <li>O utilitário não pode ser instanciado (classe final com construtor privado)</li>
 *   <li>O método {@code configure} deve aceitar um ObjectMapper externo e retornar o mesmo</li>
 * </ul>
 */
@DisplayName("VibraniumJacksonConfig — contrato de configuração unificada")
class VibraniumJacksonConfigTest {

    // -------------------------------------------------------------------------
    // Casos de teste — serialização de datas
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("configure() deve desabilitar WRITE_DATES_AS_TIMESTAMPS (ISO-8601)")
    void configure_shouldDisableWriteDatesAsTimestamps() throws Exception {
        ObjectMapper mapper = VibraniumJacksonConfig.configure(new ObjectMapper());

        String json = mapper.writeValueAsString(Instant.parse("2025-01-15T12:00:00Z"));

        // ISO-8601 — não deve ser um número puro
        assertThat(json).contains("2025-01-15");
        assertThat(json).doesNotMatch("^\\d+$"); // não epoch-millis
    }

    @Test
    @DisplayName("configure() deve desserializar ISO-8601 de volta para Instant")
    void configure_shouldDeserializeIso8601ToInstant() throws Exception {
        ObjectMapper mapper = VibraniumJacksonConfig.configure(new ObjectMapper());

        Instant original = Instant.parse("2025-06-01T09:30:00Z");
        String json = mapper.writeValueAsString(original);
        Instant restored = mapper.readValue(json, Instant.class);

        assertThat(restored).isEqualTo(original);
    }

    // -------------------------------------------------------------------------
    // Casos de teste — tolerância a contratos evoluídos
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("configure() deve ignorar propriedades desconhecidas na desserialização")
    void configure_shouldIgnoreUnknownProperties() {
        ObjectMapper mapper = VibraniumJacksonConfig.configure(new ObjectMapper());

        String jsonComCampoExtra = "{\"name\":\"vibranium\",\"unknownField\":\"future-value\"}";

        // Não deve lançar UnrecognizedPropertyException
        assertThatCode(() -> mapper.readValue(jsonComCampoExtra, SimpleDto.class))
                .doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // Casos de teste — contrato de utilitário (não instanciável)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("configure() deve retornar o mesmo ObjectMapper passado como parâmetro (fluent)")
    void configure_shouldReturnTheSameObjectMapper() {
        ObjectMapper input = new ObjectMapper();
        ObjectMapper output = VibraniumJacksonConfig.configure(input);

        assertThat(output).isSameAs(input);
    }

    @Test
    @DisplayName("VibraniumJacksonConfig não deve possuir construtor público")
    void vibraniumJacksonConfig_shouldNotBePubliclyInstantiable() {
        var constructors = VibraniumJacksonConfig.class.getDeclaredConstructors();
        assertThat(constructors).hasSize(1);
        assertThat(constructors[0].getModifiers())
                .isEqualTo(java.lang.reflect.Modifier.PRIVATE);
    }

    // -------------------------------------------------------------------------
    // DTO auxiliar para o teste de unknown properties
    // -------------------------------------------------------------------------

    /** DTO simples sem campos extras. */
    record SimpleDto(String name) {}
}
