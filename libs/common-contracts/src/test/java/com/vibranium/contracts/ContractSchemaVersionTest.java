package com.vibranium.contracts;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.vibranium.contracts.events.wallet.WalletCreatedEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Testes de compatibilidade de contrato para o campo {@code schemaVersion}.
 *
 * <h2>Fluxo TDD</h2>
 * <ol>
 *   <li><strong>FASE RED</strong> — os testes do Cenário 1 e 2 falham em tempo de
 *       compilação enquanto {@code schemaVersion} não estiver nos records, pois
 *       {@code event.schemaVersion()} não existe. O Cenário 3 falha em runtime
 *       com {@link com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException}
 *       se o mapper <em>padrão não configurado</em> receber um campo desconhecido.</li>
 *   <li><strong>FASE GREEN</strong> — após adicionar {@code int schemaVersion} a todos
 *       os records com compact constructor (default = 1) e configurar o mapper com
 *       {@code FAIL_ON_UNKNOWN_PROPERTIES = false}, todos os cenários passam.</li>
 * </ol>
 *
 * <h2>Mappers utilizados</h2>
 * <ul>
 *   <li>{@code configuredMapper} — replica a config de produção de
 *       {@code VibraniumJacksonConfig} (common-utils): JavaTimeModule +
 *       epoch-millis + {@code FAIL_ON_UNKNOWN_PROPERTIES=false}.</li>
 *   <li>{@code strictMapper} — {@link ObjectMapper} padrão com
 *       {@code FAIL_ON_UNKNOWN_PROPERTIES = true}; representa consumer SEM configuração.</li>
 * </ul>
 */
@DisplayName("Schema Version e Tolerância Jackson — compatibilidade backward/forward")
class ContractSchemaVersionTest {

    /**
     * Mapper que replica a configuração de produção (equivalente a VibraniumJacksonConfig
     * de common-utils): FAIL_ON_UNKNOWN_PROPERTIES=false + JavaTimeModule + epoch-millis.
     * Não importa VibraniumJacksonConfig aqui para manter common-contracts independente.
     */
    private static ObjectMapper configuredMapper;

    /**
     * Mapper sem configuração de tolerância — simula um consumer que NÃO usa
     * a configuração padrão da plataforma. Representa o problema que a AT-16.1 resolve.
     */
    private static ObjectMapper strictMapper;

    @BeforeAll
    static void setUp() {
        // Replica a configuração de VibraniumJacksonConfig (common-utils) sem importá-la
        // para manter common-contracts livre de dependência cíclica.
        configuredMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .enable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(SerializationFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS)
                .disable(DeserializationFeature.READ_DATE_TIMESTAMPS_AS_NANOSECONDS)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        // Mapper estrito — padrão Jackson com FAIL_ON_UNKNOWN_PROPERTIES=true (explícito)
        // Representa um consumer desatualizado que não aplicou a configuração correta.
        strictMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .enable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(SerializationFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    }

    // =========================================================================
    // Cenário 1 — Ausência de schemaVersion no JSON (backward compatibility)
    // FASE RED: falha em compilação — event.schemaVersion() não existe antes
    //           de adicionar o campo ao record.
    // =========================================================================

    @Nested
    @DisplayName("Cenário 1 — Ausência de schemaVersion (backward compatibility)")
    class AusenciaSchemaVersion {

        /**
         * JSON que representa uma mensagem antiga (v1 do producer) sem o campo
         * {@code schemaVersion}. O consumer v2 (com o campo) deve aceitar esta
         * mensagem e usar o valor padrão 1.
         *
         * <p><strong>FASE RED:</strong> falha em compilação porque
         * {@code event.schemaVersion()} não existe nos records.</p>
         */
        @Test
        @DisplayName("JSON sem schemaVersion → desserializa sem erro e assume versão 1")
        void givenJsonWithoutSchemaVersion_whenDeserializing_thenDefaultsToOne()
                throws Exception {

            // JSON de producer antigo — sem schemaVersion, sem campos futuros
            // occurredOn como epoch-millis (compatível com @JsonFormat(NUMBER_INT))
            String json = """
                    {
                      "eventId": "11111111-1111-1111-1111-111111111111",
                      "correlationId": "22222222-2222-2222-2222-222222222222",
                      "aggregateId": "55555555-5555-5555-5555-555555555555",
                      "occurredOn": 1000000000000,
                      "walletId": "55555555-5555-5555-5555-555555555555",
                      "userId": "44444444-4444-4444-4444-444444444444"
                    }
                    """;

            WalletCreatedEvent event = configuredMapper.readValue(json, WalletCreatedEvent.class);

            // FASE RED — esta linha falha em COMPILAÇÃO antes de adicionar
            // int schemaVersion ao record WalletCreatedEvent.
            assertThat(event.schemaVersion())
                    .as("schemaVersion ausente no JSON deve assumir valor padrão 1")
                    .isEqualTo(1);
        }
    }

    // =========================================================================
    // Cenário 2 — Campo adicional desconhecido no JSON (forward compatibility)
    // =========================================================================

    @Nested
    @DisplayName("Cenário 2 — Campo desconhecido no JSON (forward compatibility)")
    class CampoDesconhecido {

        /**
         * JSON de um producer v2 com campos novos que o consumer v1 não conhece.
         * O consumer configurado com {@code FAIL_ON_UNKNOWN_PROPERTIES=false} DEVE
         * ignorar o campo desconhecido silenciosamente.
         */
        @Test
        @DisplayName("Mapper configurado: JSON com campo desconhecido NÃO lança exceção")
        void givenJsonWithUnknownField_withConfiguredMapper_thenSucceeds() {

            String jsonFromFutureProducer = """
                    {
                      "eventId": "11111111-1111-1111-1111-111111111111",
                      "correlationId": "22222222-2222-2222-2222-222222222222",
                      "aggregateId": "55555555-5555-5555-5555-555555555555",
                      "occurredOn": 1000000000000,
                      "walletId": "55555555-5555-5555-5555-555555555555",
                      "userId": "44444444-4444-4444-4444-444444444444",
                      "schemaVersion": 1,
                      "newFieldFromFutureVersion": "must be ignored silently"
                    }
                    """;

            // configuredMapper (FAIL_ON_UNKNOWN_PROPERTIES=false) NÃO deve lançar exceção
            assertThatCode(() ->
                    configuredMapper.readValue(jsonFromFutureProducer, WalletCreatedEvent.class))
                    .as("Mapper configurado deve ignorar campos desconhecidos de versões futuras")
                    .doesNotThrowAnyException();
        }

        /**
         * Confirma que {@code @JsonIgnoreProperties(ignoreUnknown = true)} aplicada ao
         * record tem <strong>precedência</strong> sobre {@code FAIL_ON_UNKNOWN_PROPERTIES=true}
         * configurado no mapper (AT-5.2.1).
         *
         * <p>A anotação em nível de tipo sobrepõe a configuração global do
         * {@link ObjectMapper}: mesmo um {@code strictMapper} com
         * {@code FAIL_ON_UNKNOWN_PROPERTIES=true} não lança exceção ao desserializar
         * um record anotado com {@code @JsonIgnoreProperties(ignoreUnknown = true)}.</p>
         *
         * <p><em>Comportamento anterior à AT-5.2.1:</em> sem a anotação, este cenário
         * lançava {@link com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException}
         * — essa era a motivação do requisito. Após a anotação, o record é auto-protegido
         * independentemente da configuração do consumer.</p>
         */
        @Test
        @DisplayName("@JsonIgnoreProperties no record sobrepõe FAIL_ON_UNKNOWN_PROPERTIES=true do mapper (AT-5.2.1)")
        void givenJsonWithUnknownField_withStrictMapper_thenAnnotationOverridesMapperConfig() {

            String jsonFromFutureProducer = """
                    {
                      "eventId": "11111111-1111-1111-1111-111111111111",
                      "correlationId": "22222222-2222-2222-2222-222222222222",
                      "aggregateId": "55555555-5555-5555-5555-555555555555",
                      "occurredOn": 1000000000000,
                      "walletId": "55555555-5555-5555-5555-555555555555",
                      "userId": "44444444-4444-4444-4444-444444444444",
                      "newFieldFromFutureVersion": "causes_exception_in_old_consumer"
                    }
                    """;

            // AT-5.2.1: @JsonIgnoreProperties(ignoreUnknown = true) no record sobrepõe
            // FAIL_ON_UNKNOWN_PROPERTIES=true do mapper — o record é auto-protegido.
            assertThatCode(() ->
                    strictMapper.readValue(jsonFromFutureProducer, WalletCreatedEvent.class))
                    .as("@JsonIgnoreProperties(ignoreUnknown=true) no record sobrepõe a config "
                        + "FAIL_ON_UNKNOWN_PROPERTIES=true do mapper")
                    .doesNotThrowAnyException();
        }

        /**
         * Valida que o schemaVersion desserializado do JSON é preservado corretamente
         * (não sobrescrito pelo compact constructor quando já vem com valor válido).
         *
         * <p><strong>FASE RED:</strong> falha em compilação antes de adicionar
         * {@code schemaVersion} ao record.</p>
         */
        @Test
        @DisplayName("JSON com schemaVersion=2 explícito → valor preservado na desserialização")
        void givenJsonWithExplicitSchemaVersion_thenPreservesValue() throws Exception {

            String jsonV2 = """
                    {
                      "eventId": "11111111-1111-1111-1111-111111111111",
                      "correlationId": "22222222-2222-2222-2222-222222222222",
                      "aggregateId": "55555555-5555-5555-5555-555555555555",
                      "occurredOn": 1000000000000,
                      "walletId": "55555555-5555-5555-5555-555555555555",
                      "userId": "44444444-4444-4444-4444-444444444444",
                      "schemaVersion": 2
                    }
                    """;

            WalletCreatedEvent event = configuredMapper.readValue(jsonV2, WalletCreatedEvent.class);

            // FASE RED — falha em compilação antes de adicionar schemaVersion ao record
            assertThat(event.schemaVersion())
                    .as("schemaVersion explícito no JSON deve ser preservado")
                    .isEqualTo(2);
        }
    }

    // =========================================================================
    // Cenário 3 — Round-trip: serialização inclui schemaVersion=1
    // =========================================================================

    @Nested
    @DisplayName("Cenário 3 — Round-trip JSON inclui schemaVersion no payload")
    class RoundTripComSchemaVersion {

        /**
         * Garante que um evento criado via factory method serializa com
         * {@code schemaVersion: 1} no JSON, compatibilizando consumers que
         * já conhecem o campo.
         *
         * <p><strong>FASE RED:</strong> falha em compilação antes de adicionar
         * {@code schemaVersion} ao record.</p>
         */
        @Test
        @DisplayName("Factory method produz evento com schemaVersion=1; round-trip preserva o valor")
        void givenEventCreatedViaFactory_whenRoundTrip_thenSchemaVersionIsOne()
                throws Exception {

            // Criado via factory method (schemaVersion=1 implícito)
            WalletCreatedEvent original = WalletCreatedEvent.of(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID());

            String json = configuredMapper.writeValueAsString(original);
            WalletCreatedEvent restored = configuredMapper.readValue(json, WalletCreatedEvent.class);

            // FASE RED — falha em compilação antes de adicionar schemaVersion ao record
            assertThat(restored.schemaVersion())
                    .as("evento criado por factory method deve ter schemaVersion=1")
                    .isEqualTo(1);

            // O JSON deve conter o campo schemaVersion
            assertThat(json)
                    .as("payload JSON deve incluir schemaVersion para forward compatibility")
                    .contains("\"schemaVersion\"");
        }
    }

    // =========================================================================
    // Cenário 4 — @JsonIgnoreProperties(ignoreUnknown = true) no record (AT-5.2.1)
    // Forward compat via anotação no record, sem depender de config global do mapper
    // =========================================================================

    @Nested
    @DisplayName("Cenário 4 — @JsonIgnoreProperties(ignoreUnknown=true) no record (AT-5.2.1)")
    class ForwardCompatAnnotation {

        /**
         * Valida que um record anotado com {@code @JsonIgnoreProperties(ignoreUnknown = true)}
         * tolera campos desconhecidos mesmo com um {@link ObjectMapper} <em>padrão</em>,
         * sem nenhuma configuração global de {@code FAIL_ON_UNKNOWN_PROPERTIES}.
         *
         * <p>Por padrão, o Jackson tem {@code FAIL_ON_UNKNOWN_PROPERTIES = true} em nível
         * global. Sem a anotação no record, qualquer campo extra no JSON lança
         * {@link com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException}.
         * A anotação {@code @JsonIgnoreProperties(ignoreUnknown = true)} aplicada
         * diretamente ao record sobrescreve o comportamento do mapper — garantindo
         * forward compatibility <strong>independente</strong> da configuração do consumer.</p>
         *
         * <p><strong>FASE RED:</strong> falha com {@code UnrecognizedPropertyException}
         * enquanto a anotação não estiver presente nos records.</p>
         * <p><strong>FASE GREEN:</strong> passa após adicionar
         * {@code @JsonIgnoreProperties(ignoreUnknown = true)} a todos os records.</p>
         */
        @Test
        @DisplayName("ObjectMapper padrão + campo desconhecido → não lança exceção (AT-5.2.1)")
        void testForwardCompat_withDefaultObjectMapper_unknownFieldsIgnored() {

            // ObjectMapper padrão sem configurar FAIL_ON_UNKNOWN_PROPERTIES.
            // O padrão Jackson é FAIL = true; a anotação no record deve sobrescrever.
            ObjectMapper defaultMapper = new ObjectMapper()
                    .registerModule(new JavaTimeModule());

            String jsonWithExtraField = """
                    {
                      "eventId": "11111111-1111-1111-1111-111111111111",
                      "correlationId": "22222222-2222-2222-2222-222222222222",
                      "aggregateId": "55555555-5555-5555-5555-555555555555",
                      "occurredOn": 1000000000000,
                      "walletId": "55555555-5555-5555-5555-555555555555",
                      "userId": "44444444-4444-4444-4444-444444444444",
                      "schemaVersion": 1,
                      "campoFuturoDesconhecido": "deve ser ignorado pelo record anotado"
                    }
                    """;

            // VERDE após adicionar @JsonIgnoreProperties(ignoreUnknown = true) ao record.
            // VERMELHO antes: lança UnrecognizedPropertyException pois FAIL = true por padrão.
            assertThatCode(() ->
                    defaultMapper.readValue(jsonWithExtraField, WalletCreatedEvent.class))
                    .as("@JsonIgnoreProperties(ignoreUnknown=true) no record deve silenciar "
                        + "campos extras mesmo com ObjectMapper padrão (sem config global)")
                    .doesNotThrowAnyException();
        }
    }
}
