package com.vibranium.walletservice.application.dto;

import jakarta.validation.constraints.AssertTrue;

import java.math.BigDecimal;

/**
 * DTO de request para {@code PATCH /api/v1/wallets/{walletId}/balance}.
 *
 * <p>Ambos os campos são opcionais individualmente, mas ao menos um deve ser
 * informado. O valor pode ser positivo (crédito) ou negativo (débito), desde
 * que o saldo resultante não fique negativo (validado no domínio).</p>
 *
 * <p>Exemplos válidos:</p>
 * <ul>
 *   <li>{@code {"brlAmount": 100.00}} — credita R$100 em BRL.</li>
 *   <li>{@code {"vibAmount": -5}} — debita 5 VIB.</li>
 *   <li>{@code {"brlAmount": 50.00, "vibAmount": 10}} — credita ambos.</li>
 * </ul>
 *
 * <p>Exemplo inválido (retorna 400 Bad Request):</p>
 * <ul>
 *   <li>{@code {}} — nenhum campo informado.</li>
 * </ul>
 */
public record BalanceUpdateRequest(

        /** Delta de BRL a aplicar ao saldo disponível. Nulo = sem alteração em BRL. */
        BigDecimal brlAmount,

        /** Delta de VIB a aplicar ao saldo disponível. Nulo = sem alteração em VIB. */
        BigDecimal vibAmount

) {

    /**
     * Validação Bean Validation: ao menos um dos campos deve ser não-nulo.
     *
     * <p>O método segue a convenção de nomenclatura {@code is*} exigida pelo
     * {@code @AssertTrue} do Hibernate Validator, que o trata como uma pseudo-propriedade
     * e valida que seu retorno seja {@code true}.</p>
     *
     * @return {@code true} se ao menos um campo foi informado.
     */
    @AssertTrue(message = "Ao menos um dos campos brlAmount ou vibAmount deve ser informado")
    public boolean isAtLeastOneFieldPresent() {
        return brlAmount != null || vibAmount != null;
    }
}
