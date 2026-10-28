package com.centauro.billing.domain.vo;

/**
 * Resultado de crear el preauthorization. {@code initPoint} queda nullable: el flujo tenant usa tarjeta
 * tokenizada ({@code status=authorized}); el link solo aparece en checkouts legacy pending.
 */
public record RecurringCheckoutResult(
        String gatewaySubscriptionId,
        String initPoint,
        String status
) {
}
