package com.vibranium.orderservice.infrastructure.redis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testes unitários do método {@code parseResult} do {@link RedisMatchEngineAdapter}.
 *
 * <p><strong>Escopo:</strong> sem Spring, sem Redis, sem Docker — roda em &lt; 1 segundo.
 * Valida toda a lógica de parsing da resposta Lua incluindo casos degenerados
 * (null, lista vazia, elementos insuficientes, valores malformados).</p>
 *
 * <p>O método {@code parseResult} é package-private para permitir teste unitário.
 * O acesso direto é intencional — evita overhead de mock de {@code StringRedisTemplate}
 * e garante cobertura de branch total na lógica de parsing.</p>
 */
@DisplayName("RedisMatchEngineAdapter — parseResult() (Testes Unitários)")
class RedisMatchEngineAdapterParseResultTest {

    // -------------------------------------------------------------------------
    // Helpers para construir fixtures
    // -------------------------------------------------------------------------

    /**
     * Constrói um valor de contraparte válido no formato pipe-delimited:
     * {@code orderId|userId|walletId|qty|correlId|epochMs}
     */
    private static String validCounterpartValue(UUID orderId, String userId, UUID walletId) {
        return String.join("|",
                orderId.toString(),
                userId,
                walletId.toString(),
                "10.00",
                UUID.randomUUID().toString(),
                String.valueOf(System.currentTimeMillis())
        );
    }

    // =========================================================================
    // whenResultIsNull — entrada nula → noMatch
    // =========================================================================

    @Nested
    @DisplayName("Resultado nulo ou vazio")
    class NullOrEmptyResultTests {

        @Test
        @DisplayName("Quando result é null: deve retornar noMatch")
        void whenResultIsNull_returnsNoMatch() {
            List<RedisMatchEngineAdapter.MatchResult> results =
                    RedisMatchEngineAdapter.parseResult(null);

            assertThat(results).isEmpty();
        }

        @Test
        @DisplayName("Quando result é lista vazia: deve retornar noMatch")
        void whenResultIsEmpty_returnsNoMatch() {
            List<RedisMatchEngineAdapter.MatchResult> results =
                    RedisMatchEngineAdapter.parseResult(Collections.emptyList());

            assertThat(results).isEmpty();
        }
    }

    // =========================================================================
    // whenFirstElementIsNotMATCH — "NO_MATCH" literal ou qualquer outro valor
    // =========================================================================

    @Nested
    @DisplayName("Primeiro elemento não é MATCH")
    class FirstElementNotMatchTests {

        @Test
        @DisplayName("Quando primeiro elemento é NO_MATCH: deve retornar noMatch")
        void whenFirstElementIsNoMatch_returnsNoMatch() {
            List<Object> luaResponse = List.of("NO_MATCH");

            List<RedisMatchEngineAdapter.MatchResult> results =
                    RedisMatchEngineAdapter.parseResult(luaResponse);

            assertThat(results).isEmpty();
        }

        @Test
        @DisplayName("Quando primeiro elemento é string arbitrária: deve retornar noMatch")
        void whenFirstElementIsArbitraryString_returnsNoMatch() {
            List<Object> luaResponse = List.of("UNKNOWN_VALUE");

            List<RedisMatchEngineAdapter.MatchResult> results =
                    RedisMatchEngineAdapter.parseResult(luaResponse);

            assertThat(results).isEmpty();
        }

        @Test
        @DisplayName("Quando primeiro elemento é MATCH em minúsculas: deve retornar noMatch (case-sensitive)")
        void whenFirstElementIsLowerCaseMatch_returnsNoMatch() {
            List<Object> luaResponse = Arrays.asList(
                    "match", validCounterpartValue(UUID.randomUUID(), "user1", UUID.randomUUID()),
                    "10.00", "FULL"
            );

            List<RedisMatchEngineAdapter.MatchResult> results =
                    RedisMatchEngineAdapter.parseResult(luaResponse);

            assertThat(results).isEmpty();
        }
    }

    // =========================================================================
    // whenResultHasLessThan4Elements — estrutura insuficiente
    // =========================================================================

    @Nested
    @DisplayName("Resultado com menos de 4 elementos")
    class InsufficientElementsTests {

        @Test
        @DisplayName("Quando result tem apenas [MATCH]: deve retornar noMatch (< 4 elementos)")
        void whenResultHasOnly1Element_returnsNoMatch() {
            List<Object> luaResponse = List.of("MATCH");

            List<RedisMatchEngineAdapter.MatchResult> results =
                    RedisMatchEngineAdapter.parseResult(luaResponse);

            assertThat(results).isEmpty();
        }

        @Test
        @DisplayName("Quando result tem 3 elementos [MATCH, valor, qty]: deve retornar noMatch (< 4)")
        void whenResultHas3Elements_returnsNoMatch() {
            List<Object> luaResponse = Arrays.asList(
                    "MATCH",
                    validCounterpartValue(UUID.randomUUID(), "u1", UUID.randomUUID()),
                    "5.00"
                    // falta o 4º elemento: fillType
            );

            List<RedisMatchEngineAdapter.MatchResult> results =
                    RedisMatchEngineAdapter.parseResult(luaResponse);

            assertThat(results).isEmpty();
        }
    }

    // =========================================================================
    // whenCounterpartValueIsMalformed — pipe-delimited < 5 partes
    // =========================================================================

    @Nested
    @DisplayName("Valor da contraparte malformado")
    class MalformedCounterpartValueTests {

        @Test
        @DisplayName("Quando counterpartValue não tem campos suficientes: deve retornar noMatch")
        void whenCounterpartValueIsMalformed_returnsNoMatch() {
            // Formato correto: orderId|userId|walletId|qty|correlId|epochMs (6 partes)
            // Aqui enviamos apenas 2 partes → malformado
            String malformedValue = "only-two|parts";
            List<Object> luaResponse = Arrays.asList(
                    "MATCH", malformedValue, "10.00", "FULL"
            );

            List<RedisMatchEngineAdapter.MatchResult> results =
                    RedisMatchEngineAdapter.parseResult(luaResponse);

            assertThat(results).isEmpty();
        }

        @Test
        @DisplayName("Quando orderId não é UUID válido: deve retornar noMatch")
        void whenOrderIdIsNotValidUUID_returnsNoMatch() {
            // UUID inválido no primeiro campo
            String malformedUUID = "NOT-A-UUID|userId|" + UUID.randomUUID() + "|10.00|"
                    + UUID.randomUUID() + "|" + System.currentTimeMillis();
            List<Object> luaResponse = Arrays.asList(
                    "MATCH", malformedUUID, "10.00", "FULL"
            );

            List<RedisMatchEngineAdapter.MatchResult> results =
                    RedisMatchEngineAdapter.parseResult(luaResponse);

            assertThat(results).isEmpty();
        }

        @Test
        @DisplayName("Quando walletId não é UUID válido: deve retornar noMatch")
        void whenWalletIdIsNotValidUUID_returnsNoMatch() {
            String malformedWallet = UUID.randomUUID() + "|userId|NOT-A-UUID|10.00|"
                    + UUID.randomUUID() + "|" + System.currentTimeMillis();
            List<Object> luaResponse = Arrays.asList(
                    "MATCH", malformedWallet, "10.00", "FULL"
            );

            List<RedisMatchEngineAdapter.MatchResult> results =
                    RedisMatchEngineAdapter.parseResult(luaResponse);

            assertThat(results).isEmpty();
        }
    }

    // =========================================================================
    // whenResultIsValid — parsing completo e correto
    // =========================================================================

    @Nested
    @DisplayName("Resultado válido")
    class ValidResultTests {

        @Test
        @DisplayName("Quando result é válido com 4 elementos: deve parsear todos os campos corretamente")
        void whenResultIsValid_parsesAllFieldsCorrectly() {
            UUID counterpartOrderId = UUID.randomUUID();
            UUID counterpartWalletId = UUID.randomUUID();
            String counterpartUserId = "keycloak-user-123";
            String matchedQty = "7.50";
            String fillType = "FULL";

            String counterpartValue = String.join("|",
                    counterpartOrderId.toString(),
                    counterpartUserId,
                    counterpartWalletId.toString(),
                    "7.50",
                    UUID.randomUUID().toString(),
                    String.valueOf(System.currentTimeMillis())
            );

            List<Object> luaResponse = Arrays.asList(
                    "MATCH", counterpartValue, matchedQty, fillType
            );

            List<RedisMatchEngineAdapter.MatchResult> results =
                    RedisMatchEngineAdapter.parseResult(luaResponse);
            assertThat(results).hasSize(1);
            RedisMatchEngineAdapter.MatchResult result = results.get(0);

            assertThat(result.matched()).isTrue();
            assertThat(result.counterpartId()).isEqualTo(counterpartOrderId);
            assertThat(result.counterpartUserId()).isEqualTo(counterpartUserId);
            assertThat(result.counterpartWalletId()).isEqualTo(counterpartWalletId);
            assertThat(result.matchedQty()).isEqualByComparingTo(matchedQty);
            assertThat(result.fillType()).isEqualTo(fillType);
            // Sem 5º elemento: remainingCounterpartQty deve ser ZERO (fallback)
            assertThat(result.remainingCounterpartQty()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("Quando result tem 5 elementos: deve parsear remainingCounterpartQty corretamente")
        void whenResultHas5Elements_parsesRemainingQtyCorrectly() {
            UUID counterpartOrderId = UUID.randomUUID();
            UUID counterpartWalletId = UUID.randomUUID();

            String counterpartValue = String.join("|",
                    counterpartOrderId.toString(),
                    "seller-user",
                    counterpartWalletId.toString(),
                    "10.00",
                    UUID.randomUUID().toString(),
                    String.valueOf(System.currentTimeMillis())
            );

            List<Object> luaResponse = Arrays.asList(
                    "MATCH", counterpartValue, "3.00", "PARTIAL_ASK", "7.00"
            );

            List<RedisMatchEngineAdapter.MatchResult> results =
                    RedisMatchEngineAdapter.parseResult(luaResponse);
            assertThat(results).hasSize(1);
            RedisMatchEngineAdapter.MatchResult result = results.get(0);

            assertThat(result.matched()).isTrue();
            assertThat(result.matchedQty()).isEqualByComparingTo("3.00");
            assertThat(result.fillType()).isEqualTo("PARTIAL_ASK");
            assertThat(result.remainingCounterpartQty()).isEqualByComparingTo("7.00");
        }

        @Test
        @DisplayName("Quando result tem fillType PARTIAL_BID: deve parsear corretamente")
        void whenFillTypeIsPartialBid_parsesCorrectly() {
            String counterpartValue = String.join("|",
                    UUID.randomUUID().toString(),
                    "buyer-user",
                    UUID.randomUUID().toString(),
                    "10.00",
                    UUID.randomUUID().toString(),
                    String.valueOf(System.currentTimeMillis())
            );

            List<Object> luaResponse = Arrays.asList(
                    "MATCH", counterpartValue, "4.00", "PARTIAL_BID", "6.00"
            );

            List<RedisMatchEngineAdapter.MatchResult> results =
                    RedisMatchEngineAdapter.parseResult(luaResponse);
            assertThat(results).hasSize(1);
            RedisMatchEngineAdapter.MatchResult result = results.get(0);

            assertThat(result.matched()).isTrue();
            assertThat(result.fillType()).isEqualTo("PARTIAL_BID");
            assertThat(result.matchedQty()).isEqualByComparingTo("4.00");
            assertThat(result.remainingCounterpartQty()).isEqualByComparingTo("6.00");
        }
    }

    // =========================================================================
    // whenResultContainsByteArray — Redis pode retornar byte[] ou String
    // =========================================================================

    @Nested
    @DisplayName("Elementos como byte[]")
    class ByteArrayElementTests {

        @Test
        @DisplayName("Quando elementos são byte[]: deve converter para String e parsear corretamente")
        void whenResultContainsByteArray_convertsToStringCorrectly() {
            UUID counterpartOrderId = UUID.randomUUID();
            UUID counterpartWalletId = UUID.randomUUID();
            String userId = "byte-array-user";

            String counterpartValue = String.join("|",
                    counterpartOrderId.toString(),
                    userId,
                    counterpartWalletId.toString(),
                    "5.00",
                    UUID.randomUUID().toString(),
                    String.valueOf(System.currentTimeMillis())
            );

            // Redis pode retornar strings como byte[] dependendo do serializer configurado
            List<Object> luaResponse = Arrays.asList(
                    "MATCH".getBytes(StandardCharsets.UTF_8),
                    counterpartValue.getBytes(StandardCharsets.UTF_8),
                    "5.00".getBytes(StandardCharsets.UTF_8),
                    "FULL".getBytes(StandardCharsets.UTF_8)
            );

            List<RedisMatchEngineAdapter.MatchResult> results =
                    RedisMatchEngineAdapter.parseResult(luaResponse);
            assertThat(results).hasSize(1);
            RedisMatchEngineAdapter.MatchResult result = results.get(0);

            assertThat(result.matched()).isTrue();
            assertThat(result.counterpartId()).isEqualTo(counterpartOrderId);
            assertThat(result.counterpartUserId()).isEqualTo(userId);
            assertThat(result.counterpartWalletId()).isEqualTo(counterpartWalletId);
            assertThat(result.matchedQty()).isEqualByComparingTo("5.00");
            assertThat(result.fillType()).isEqualTo("FULL");
        }

        @Test
        @DisplayName("Quando primeiro elemento é byte[] com NO_MATCH: deve retornar noMatch")
        void whenFirstByteArrayIsNoMatch_returnsNoMatch() {
            List<Object> luaResponse = List.of(
                    "NO_MATCH".getBytes(StandardCharsets.UTF_8)
            );

            List<RedisMatchEngineAdapter.MatchResult> results =
                    RedisMatchEngineAdapter.parseResult(luaResponse);

            assertThat(results).isEmpty();
        }

        @Test
        @DisplayName("Quando 5º elemento byte[] contém remainingQty: deve parsear corretamente")
        void whenFifthElementIsByteArray_parsesRemainingQtyCorrectly() {
            String counterpartValue = String.join("|",
                    UUID.randomUUID().toString(), "user1", UUID.randomUUID().toString(),
                    "8.00", UUID.randomUUID().toString(), String.valueOf(System.currentTimeMillis())
            );

            List<Object> luaResponse = Arrays.asList(
                    "MATCH",
                    counterpartValue,
                    "2.00",
                    "PARTIAL_ASK",
                    "6.00".getBytes(StandardCharsets.UTF_8)
            );

            List<RedisMatchEngineAdapter.MatchResult> results =
                    RedisMatchEngineAdapter.parseResult(luaResponse);
            assertThat(results).hasSize(1);
            RedisMatchEngineAdapter.MatchResult result = results.get(0);

            assertThat(result.matched()).isTrue();
            assertThat(result.remainingCounterpartQty()).isEqualByComparingTo("6.00");
        }
    }

    // =========================================================================
    // Casos degenerados — 5º elemento malformado
    // =========================================================================

    @Nested
    @DisplayName("Quinto elemento malformado")
    class FifthElementMalformedTests {

        @Test
        @DisplayName("Quando 5º elemento não é número: deve usar ZERO como fallback sem exceção")
        void whenFifthElementIsNotNumber_usesFallbackZero() {
            String counterpartValue = String.join("|",
                    UUID.randomUUID().toString(), "user", UUID.randomUUID().toString(),
                    "5.00", UUID.randomUUID().toString(), String.valueOf(System.currentTimeMillis())
            );

            // 5º elemento malformado — não deve lançar exceção, apenas usar fallback ZERO
            List<Object> luaResponse = Arrays.asList(
                    "MATCH", counterpartValue, "5.00", "FULL", "NOT_A_NUMBER"
            );

            List<RedisMatchEngineAdapter.MatchResult> results =
                    RedisMatchEngineAdapter.parseResult(luaResponse);
            assertThat(results).hasSize(1);
            RedisMatchEngineAdapter.MatchResult result = results.get(0);

            // Fallback: ZERO quando o 5º elemento não pôde ser parseado
            assertThat(result.matched()).isTrue();
            assertThat(result.remainingCounterpartQty()).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    // =========================================================================
    // Formato novo MULTI_MATCH / PARTIAL (AT-3.1.1)
    // =========================================================================

    @Nested
    @DisplayName("Formato MULTI_MATCH — N matches, ordem totalmente preenchida")
    class MultiMatchFormatTests {

        private String makeCounterpart(UUID orderId, String userId, UUID walletId) {
            return String.join("|",
                    orderId.toString(), userId, walletId.toString(),
                    "20.00", UUID.randomUUID().toString(), String.valueOf(System.currentTimeMillis()));
        }

        @Test
        @DisplayName("MULTI_MATCH com 2 contrapartes: deve retornar 2 MatchResults")
        void whenMultiMatch2Counterparts_returns2Results() {
            UUID c1Id = UUID.randomUUID();
            UUID c1Wallet = UUID.randomUUID();
            UUID c2Id = UUID.randomUUID();
            UUID c2Wallet = UUID.randomUUID();

            String c1val = makeCounterpart(c1Id, "user1", c1Wallet);
            String c2val = makeCounterpart(c2Id, "user2", c2Wallet);

            // ["MULTI_MATCH", "2", c1val, "20.00", "FULL", "0.00000000", c2val, "20.00", "FULL", "0.00000000"]
            List<Object> luaResponse = Arrays.asList(
                    "MULTI_MATCH", "2",
                    c1val, "20.00000000", "FULL", "0.00000000",
                    c2val, "20.00000000", "FULL", "0.00000000"
            );

            List<RedisMatchEngineAdapter.MatchResult> results =
                    RedisMatchEngineAdapter.parseResult(luaResponse);

            assertThat(results).hasSize(2);
            assertThat(results.get(0).counterpartId()).isEqualTo(c1Id);
            assertThat(results.get(0).matchedQty()).isEqualByComparingTo("20.00");
            assertThat(results.get(0).fillType()).isEqualTo("FULL");
            assertThat(results.get(0).remainingCounterpartQty()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(results.get(1).counterpartId()).isEqualTo(c2Id);
            assertThat(results.get(1).matchedQty()).isEqualByComparingTo("20.00");
            assertThat(results.get(1).fillType()).isEqualTo("FULL");
        }

        @Test
        @DisplayName("MULTI_MATCH com count=0: deve retornar lista vazia")
        void whenMultiMatch0Count_returnsEmptyList() {
            List<Object> luaResponse = Arrays.asList("MULTI_MATCH", "0");

            List<RedisMatchEngineAdapter.MatchResult> results =
                    RedisMatchEngineAdapter.parseResult(luaResponse);

            assertThat(results).isEmpty();
        }

        @Test
        @DisplayName("MULTI_MATCH sem campo count: deve retornar lista vazia")
        void whenMultiMatchMissingCount_returnsEmptyList() {
            List<Object> luaResponse = List.of("MULTI_MATCH");

            List<RedisMatchEngineAdapter.MatchResult> results =
                    RedisMatchEngineAdapter.parseResult(luaResponse);

            assertThat(results).isEmpty();
        }

        @Test
        @DisplayName("MULTI_MATCH com último match PARTIAL_ASK: deve preservar remainingQty")
        void whenMultiMatchLastIsPartialAsk_returnsCorrectRemaining() {
            UUID cId = UUID.randomUUID();
            String cval = makeCounterpart(cId, "seller", UUID.randomUUID());

            // Último match: BID consumiu qty=15 de um ASK de 20 → ASK remaining = 5
            List<Object> luaResponse = Arrays.asList(
                    "MULTI_MATCH", "1",
                    cval, "15.00000000", "PARTIAL_ASK", "5.00000000"
            );

            List<RedisMatchEngineAdapter.MatchResult> results =
                    RedisMatchEngineAdapter.parseResult(luaResponse);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).fillType()).isEqualTo("PARTIAL_ASK");
            assertThat(results.get(0).matchedQty()).isEqualByComparingTo("15.00000000");
            assertThat(results.get(0).remainingCounterpartQty()).isEqualByComparingTo("5.00000000");
        }
    }

    @Nested
    @DisplayName("Formato PARTIAL — N matches, livro esgotou antes de preencher a ordem")
    class PartialFormatTests {

        private String makeCounterpart() {
            return String.join("|",
                    UUID.randomUUID().toString(), "seller", UUID.randomUUID().toString(),
                    "30.00", UUID.randomUUID().toString(), String.valueOf(System.currentTimeMillis()));
        }

        @Test
        @DisplayName("PARTIAL com 2 matches: deve retornar 2 MatchResults (remainingIncoming ignorado)")
        void whenPartial2Matches_returns2Results() {
            String c1val = makeCounterpart();
            String c2val = makeCounterpart();

            // ["PARTIAL", "2", c1val, "30", "FULL", "0", c2val, "30", "FULL", "0", "40.00000000"]
            // último elemento é remainingIncoming — ignorado no parsing de MatchResult
            List<Object> luaResponse = Arrays.asList(
                    "PARTIAL", "2",
                    c1val, "30.00000000", "FULL", "0.00000000",
                    c2val, "30.00000000", "FULL", "0.00000000",
                    "40.00000000"   // remainingIncoming (não incluído nos MatchResult)
            );

            List<RedisMatchEngineAdapter.MatchResult> results =
                    RedisMatchEngineAdapter.parseResult(luaResponse);

            assertThat(results).hasSize(2);
            assertThat(results.get(0).fillType()).isEqualTo("FULL");
            assertThat(results.get(0).matchedQty()).isEqualByComparingTo("30.00");
            assertThat(results.get(1).fillType()).isEqualTo("FULL");
            assertThat(results.get(1).matchedQty()).isEqualByComparingTo("30.00");
        }

        @Test
        @DisplayName("PARTIAL com 1 match e remainingIncoming: deve retornar 1 MatchResult")
        void whenPartial1Match_returns1Result() {
            String cval = makeCounterpart();

            List<Object> luaResponse = Arrays.asList(
                    "PARTIAL", "1",
                    cval, "30.00000000", "FULL", "0.00000000",
                    "70.00000000"   // remainingIncoming — BUY de 100 consumiu 30, restam 70
            );

            List<RedisMatchEngineAdapter.MatchResult> results =
                    RedisMatchEngineAdapter.parseResult(luaResponse);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).matched()).isTrue();
            assertThat(results.get(0).matchedQty()).isEqualByComparingTo("30.00");
        }
    }

    // =========================================================================
    // AT-16: ALREADY_IN_BOOK — deduplicação de orderId no Lua
    // =========================================================================

    @Nested
    @DisplayName("ALREADY_IN_BOOK (AT-16 — deduplicação Lua)")
    class AlreadyInBookTests {

        @Test
        @DisplayName("Quando Lua retorna ALREADY_IN_BOOK: deve retornar 1 MatchResult com fillType=ALREADY_IN_BOOK e matched=false")
        void whenAlreadyInBook_returnsSingleResultWithCorrectFillType() {
            List<Object> luaResponse = List.of("ALREADY_IN_BOOK");

            List<RedisMatchEngineAdapter.MatchResult> results =
                    RedisMatchEngineAdapter.parseResult(luaResponse);

            assertThat(results)
                    .as("ALREADY_IN_BOOK deve retornar exatamente 1 resultado")
                    .hasSize(1);

            RedisMatchEngineAdapter.MatchResult result = results.get(0);
            assertThat(result.matched()).isFalse();
            assertThat(result.fillType()).isEqualTo("ALREADY_IN_BOOK");
            assertThat(result.counterpartId()).isNull();
            assertThat(result.matchedQty()).isEqualByComparingTo(java.math.BigDecimal.ZERO);
        }

        @Test
        @DisplayName("Quando ALREADY_IN_BOOK como byte[]: parsing funciona com serializer de bytes")
        void whenAlreadyInBookAsByteArray_returnsSingleResult() {
            List<Object> luaResponse = List.of(
                    "ALREADY_IN_BOOK".getBytes(StandardCharsets.UTF_8)
            );

            List<RedisMatchEngineAdapter.MatchResult> results =
                    RedisMatchEngineAdapter.parseResult(luaResponse);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).fillType()).isEqualTo("ALREADY_IN_BOOK");
            assertThat(results.get(0).matched()).isFalse();
        }
    }
}
