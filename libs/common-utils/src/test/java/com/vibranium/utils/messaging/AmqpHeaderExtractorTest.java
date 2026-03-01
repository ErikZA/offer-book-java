package com.vibranium.utils.messaging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageProperties;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testes unitários para {@link AmqpHeaderExtractor}.
 *
 * <p>Fase RED: os testes devem FALHAR até que a implementação exista.</p>
 *
 * <p>Contrato verificado:</p>
 * <ul>
 *   <li>Extração de {@code messageId} do cabeçalho AMQP (campo nativo do protocolo)</li>
 *   <li>Fallback para header customizado {@code X-Correlation-ID} quando messageId ausente</li>
 *   <li>Retorno de {@link Optional#empty()} quando nenhuma fonte de ID estiver disponível</li>
 *   <li>Lançamento de {@link NullPointerException} com mensagem clara para props nulo</li>
 *   <li>Não instanciável (utilitário estático final)</li>
 * </ul>
 */
@DisplayName("AmqpHeaderExtractor — extração de messageId e correlation ID de headers AMQP")
class AmqpHeaderExtractorTest {

    // -------------------------------------------------------------------------
    // Casos de teste — extração de messageId nativo
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("extractMessageId() deve retornar messageId quando presente nas MessageProperties")
    void extractMessageId_shouldReturnMessageIdWhenPresent() {
        String expectedId = UUID.randomUUID().toString();
        MessageProperties props = new MessageProperties();
        props.setMessageId(expectedId);

        Optional<String> result = AmqpHeaderExtractor.extractMessageId(props);

        assertThat(result).isPresent().hasValue(expectedId);
    }

    @Test
    @DisplayName("extractMessageId() deve retornar empty quando messageId for null")
    void extractMessageId_shouldReturnEmptyWhenMessageIdIsNull() {
        MessageProperties props = new MessageProperties();
        // messageId não definido — é null por padrão

        Optional<String> result = AmqpHeaderExtractor.extractMessageId(props);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("extractMessageId() deve retornar empty quando messageId for string vazia")
    void extractMessageId_shouldReturnEmptyWhenMessageIdIsBlank() {
        MessageProperties props = new MessageProperties();
        props.setMessageId("   "); // blank

        Optional<String> result = AmqpHeaderExtractor.extractMessageId(props);

        assertThat(result).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Casos de teste — fallback para X-Correlation-ID header customizado
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("extractCorrelationId() deve retornar X-Correlation-ID quando messageId ausente")
    void extractCorrelationId_shouldFallbackToCustomHeader() {
        String correlationId = UUID.randomUUID().toString();
        MessageProperties props = new MessageProperties();
        props.setHeader("X-Correlation-ID", correlationId);
        // messageId não definido

        Optional<String> result = AmqpHeaderExtractor.extractCorrelationId(props);

        assertThat(result).isPresent().hasValue(correlationId);
    }

    @Test
    @DisplayName("extractCorrelationId() deve preferir messageId em relação ao X-Correlation-ID")
    void extractCorrelationId_shouldPreferMessageIdOverCustomHeader() {
        String messageId = "msg-id-" + UUID.randomUUID();
        String correlationId = "corr-id-" + UUID.randomUUID();

        MessageProperties props = new MessageProperties();
        props.setMessageId(messageId);
        props.setHeader("X-Correlation-ID", correlationId);

        Optional<String> result = AmqpHeaderExtractor.extractCorrelationId(props);

        assertThat(result).isPresent().hasValue(messageId);
    }

    @Test
    @DisplayName("extractCorrelationId() deve retornar empty quando nenhuma fonte disponível")
    void extractCorrelationId_shouldReturnEmptyWhenNoSourceAvailable() {
        MessageProperties props = new MessageProperties();

        Optional<String> result = AmqpHeaderExtractor.extractCorrelationId(props);

        assertThat(result).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Casos de teste — defesa contra null
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("extractMessageId() deve lançar NullPointerException para MessageProperties nulo")
    void extractMessageId_shouldThrowNpeForNullProperties() {
        assertThatThrownBy(() -> AmqpHeaderExtractor.extractMessageId(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("messageProperties");
    }

    // -------------------------------------------------------------------------
    // Casos de teste — contrato de utilitário
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("AmqpHeaderExtractor não deve possuir construtor público")
    void amqpHeaderExtractor_shouldNotBePubliclyInstantiable() {
        var constructors = AmqpHeaderExtractor.class.getDeclaredConstructors();
        assertThat(constructors).hasSize(1);
        assertThat(constructors[0].getModifiers())
                .isEqualTo(java.lang.reflect.Modifier.PRIVATE);
    }
}
