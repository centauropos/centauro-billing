package com.centauro.billing.domain.vo;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Pedido de preapproval without-plan-authorized. {@code cardTokenId} es el token del Brick; no loguearlo.
 */
public record RecurringCheckoutRequest(
        UUID checkoutId,
        String payerEmail,
        String reason,
        BigDecimal amount,
        String currency,
        OffsetDateTime startDate,
        String backUrl,
        String cardTokenId
) {
}
