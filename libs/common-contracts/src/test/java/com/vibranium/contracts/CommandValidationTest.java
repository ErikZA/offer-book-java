package com.vibranium.contracts;

import com.vibranium.contracts.commands.order.CreateOrderCommand;
import com.vibranium.contracts.commands.wallet.CreateWalletCommand;
import com.vibranium.contracts.commands.wallet.ReleaseFundsCommand;
import com.vibranium.contracts.commands.wallet.ReserveFundsCommand;
import com.vibranium.contracts.commands.wallet.SettleFundsCommand;
import com.vibranium.contracts.enums.AssetType;
import com.vibranium.contracts.enums.OrderType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testa as constraints JSR-380 (@NotNull, @DecimalMin) em todos os Commands.
 *
 * Usa Hibernate Validator como provider (escopo test) sem Spring context.
 * Garante que contratos inválidos sejam detectados antes de serem publicados
 * no RabbitMQ.
 */
@DisplayName("Command Validation — Jakarta Validation / JSR-380")
class CommandValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        // Inicializa o Hibernate Validator sem Spring
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    // =========================================================================
    // CreateWalletCommand
    // =========================================================================
    @Nested
    @DisplayName("CreateWalletCommand")
    class CreateWalletCommandTests {

        @Test
        @DisplayName("válido: todos os campos preenchidos")
        void valid_allFieldsPresent() {
            var cmd = new CreateWalletCommand(UUID.randomUUID(), UUID.randomUUID(), 1);
            assertThat(validator.validate(cmd)).isEmpty();
        }

        @Test
        @DisplayName("inválido: correlationId nulo")
        void invalid_nullCorrelationId() {
            var cmd = new CreateWalletCommand(null, UUID.randomUUID(), 1);
            Set<ConstraintViolation<CreateWalletCommand>> violations = validator.validate(cmd);
            assertThat(violations).isNotEmpty();
            assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("correlationId"));
        }

        @Test
        @DisplayName("inválido: userId nulo")
        void invalid_nullUserId() {
            var cmd = new CreateWalletCommand(UUID.randomUUID(), null, 1);
            Set<ConstraintViolation<CreateWalletCommand>> violations = validator.validate(cmd);
            assertThat(violations).isNotEmpty();
            assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("userId"));
        }
    }

    // =========================================================================
    // ReserveFundsCommand
    // =========================================================================
    @Nested
    @DisplayName("ReserveFundsCommand")
    class ReserveFundsCommandTests {

        private final UUID cid = UUID.randomUUID();
        private final UUID oid = UUID.randomUUID();
        private final UUID wid = UUID.randomUUID();

        @Test
        @DisplayName("válido: todos os campos corretos")
        void valid() {
            var cmd = new ReserveFundsCommand(cid, oid, wid, AssetType.BRL, new BigDecimal("100.00"), 1);
            assertThat(validator.validate(cmd)).isEmpty();
        }

        @Test
        @DisplayName("inválido: amount zero deve falhar @DecimalMin(exclusive)")
        void invalid_zeroAmount() {
            var cmd = new ReserveFundsCommand(cid, oid, wid, AssetType.BRL, BigDecimal.ZERO, 1);
            assertThat(validator.validate(cmd))
                .anyMatch(v -> v.getPropertyPath().toString().equals("amount"));
        }

        @Test
        @DisplayName("inválido: amount negativo deve falhar @DecimalMin")
        void invalid_negativeAmount() {
            var cmd = new ReserveFundsCommand(cid, oid, wid, AssetType.VIBRANIUM, new BigDecimal("-1.00"), 1);
            assertThat(validator.validate(cmd))
                .anyMatch(v -> v.getPropertyPath().toString().equals("amount"));
        }

        @Test
        @DisplayName("inválido: asset nulo")
        void invalid_nullAsset() {
            var cmd = new ReserveFundsCommand(cid, oid, wid, null, new BigDecimal("50.00"), 1);
            assertThat(validator.validate(cmd))
                .anyMatch(v -> v.getPropertyPath().toString().equals("asset"));
        }

        @Test
        @DisplayName("inválido: walletId nulo")
        void invalid_nullWalletId() {
            var cmd = new ReserveFundsCommand(cid, oid, null, AssetType.BRL, new BigDecimal("50.00"), 1);
            assertThat(validator.validate(cmd))
                .anyMatch(v -> v.getPropertyPath().toString().equals("walletId"));
        }
    }

    // =========================================================================
    // ReleaseFundsCommand
    // =========================================================================
    @Nested
    @DisplayName("ReleaseFundsCommand")
    class ReleaseFundsCommandTests {

        private final UUID cid = UUID.randomUUID();
        private final UUID oid = UUID.randomUUID();
        private final UUID wid = UUID.randomUUID();

        @Test
        @DisplayName("válido: todos os campos corretos")
        void valid() {
            var cmd = new ReleaseFundsCommand(cid, oid, wid, AssetType.BRL, new BigDecimal("100.00"), 1);
            assertThat(validator.validate(cmd)).isEmpty();
        }

        @Test
        @DisplayName("inválido: correlationId nulo")
        void invalid_nullCorrelationId() {
            var cmd = new ReleaseFundsCommand(null, oid, wid, AssetType.BRL, new BigDecimal("50.00"), 1);
            assertThat(validator.validate(cmd))
                .anyMatch(v -> v.getPropertyPath().toString().equals("correlationId"));
        }

        @Test
        @DisplayName("inválido: orderId nulo")
        void invalid_nullOrderId() {
            var cmd = new ReleaseFundsCommand(cid, null, wid, AssetType.BRL, new BigDecimal("50.00"), 1);
            assertThat(validator.validate(cmd))
                .anyMatch(v -> v.getPropertyPath().toString().equals("orderId"));
        }

        @Test
        @DisplayName("inválido: walletId nulo")
        void invalid_nullWalletId() {
            var cmd = new ReleaseFundsCommand(cid, oid, null, AssetType.BRL, new BigDecimal("50.00"), 1);
            assertThat(validator.validate(cmd))
                .anyMatch(v -> v.getPropertyPath().toString().equals("walletId"));
        }

        @Test
        @DisplayName("inválido: asset nulo")
        void invalid_nullAsset() {
            var cmd = new ReleaseFundsCommand(cid, oid, wid, null, new BigDecimal("50.00"), 1);
            assertThat(validator.validate(cmd))
                .anyMatch(v -> v.getPropertyPath().toString().equals("asset"));
        }

        @Test
        @DisplayName("inválido: amount zero deve falhar @DecimalMin(exclusive)")
        void invalid_zeroAmount() {
            var cmd = new ReleaseFundsCommand(cid, oid, wid, AssetType.VIBRANIUM, BigDecimal.ZERO, 1);
            assertThat(validator.validate(cmd))
                .anyMatch(v -> v.getPropertyPath().toString().equals("amount"));
        }

        @Test
        @DisplayName("inválido: amount negativo deve falhar @DecimalMin")
        void invalid_negativeAmount() {
            var cmd = new ReleaseFundsCommand(cid, oid, wid, AssetType.BRL, new BigDecimal("-1.00"), 1);
            assertThat(validator.validate(cmd))
                .anyMatch(v -> v.getPropertyPath().toString().equals("amount"));
        }
    }

    // =========================================================================
    // SettleFundsCommand
    // =========================================================================
    @Nested
    @DisplayName("SettleFundsCommand")
    class SettleFundsCommandTests {

        private final UUID cid = UUID.randomUUID();
        private final UUID mid = UUID.randomUUID();
        private final UUID boid = UUID.randomUUID();
        private final UUID soid = UUID.randomUUID();
        private final UUID bwid = UUID.randomUUID();
        private final UUID swid = UUID.randomUUID();

        @Test
        @DisplayName("válido: todos os campos corretos")
        void valid() {
            var cmd = new SettleFundsCommand(cid, mid, boid, soid, bwid, swid,
                    new BigDecimal("150.00"), new BigDecimal("10.00"), 1);
            assertThat(validator.validate(cmd)).isEmpty();
        }

        @Test
        @DisplayName("inválido: matchPrice zero deve falhar")
        void invalid_zeroMatchPrice() {
            var cmd = new SettleFundsCommand(cid, mid, boid, soid, bwid, swid,
                    BigDecimal.ZERO, new BigDecimal("10.00"), 1);
            assertThat(validator.validate(cmd))
                .anyMatch(v -> v.getPropertyPath().toString().equals("matchPrice"));
        }

        @Test
        @DisplayName("inválido: matchAmount negativo deve falhar")
        void invalid_negativeMatchAmount() {
            var cmd = new SettleFundsCommand(cid, mid, boid, soid, bwid, swid,
                    new BigDecimal("150.00"), new BigDecimal("-5.00"), 1);
            assertThat(validator.validate(cmd))
                .anyMatch(v -> v.getPropertyPath().toString().equals("matchAmount"));
        }

        @Test
        @DisplayName("inválido: múltiplos campos nulos devem gerar múltiplas violações")
        void invalid_multipleNulls() {
            var cmd = new SettleFundsCommand(null, null, boid, soid, bwid, swid,
                    new BigDecimal("150.00"), new BigDecimal("10.00"), 1);
            assertThat(validator.validate(cmd)).hasSizeGreaterThanOrEqualTo(2);
        }
    }

    // =========================================================================
    // CreateOrderCommand
    // =========================================================================
    @Nested
    @DisplayName("CreateOrderCommand")
    class CreateOrderCommandTests {

        private final UUID cid = UUID.randomUUID();
        private final UUID oid = UUID.randomUUID();
        private final UUID uid = UUID.randomUUID();
        private final UUID wid = UUID.randomUUID();

        @Test
        @DisplayName("válido: BUY com price e amount positivos")
        void valid_buy() {
            var cmd = new CreateOrderCommand(cid, oid, uid, wid, OrderType.BUY,
                    new BigDecimal("150.00"), new BigDecimal("5.00"), 1);
            assertThat(validator.validate(cmd)).isEmpty();
        }

        @Test
        @DisplayName("válido: SELL com price e amount positivos")
        void valid_sell() {
            var cmd = new CreateOrderCommand(cid, oid, uid, wid, OrderType.SELL,
                    new BigDecimal("160.00"), new BigDecimal("2.50"), 1);
            assertThat(validator.validate(cmd)).isEmpty();
        }

        @Test
        @DisplayName("inválido: price zero deve falhar")
        void invalid_zeroPrice() {
            var cmd = new CreateOrderCommand(cid, oid, uid, wid, OrderType.BUY,
                    BigDecimal.ZERO, new BigDecimal("5.00"), 1);
            assertThat(validator.validate(cmd))
                .anyMatch(v -> v.getPropertyPath().toString().equals("price"));
        }

        @Test
        @DisplayName("inválido: amount negativo deve falhar")
        void invalid_negativeAmount() {
            var cmd = new CreateOrderCommand(cid, oid, uid, wid, OrderType.SELL,
                    new BigDecimal("100.00"), new BigDecimal("-1.00"), 1);
            assertThat(validator.validate(cmd))
                .anyMatch(v -> v.getPropertyPath().toString().equals("amount"));
        }

        @Test
        @DisplayName("inválido: orderType nulo deve falhar")
        void invalid_nullOrderType() {
            var cmd = new CreateOrderCommand(cid, oid, uid, wid, null,
                    new BigDecimal("100.00"), new BigDecimal("1.00"), 1);
            assertThat(validator.validate(cmd))
                .anyMatch(v -> v.getPropertyPath().toString().equals("orderType"));
        }
    }
}
