package com.centauro.billing.application.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Checkout en el detalle de plataforma. Sin {@code payerEmail} ni {@code initPoint}. */
public record CheckoutSummaryView(
        UUID checkoutId,
        UUID planId,
        String status,
        String amount,
        String currency,
        String gatewayPreapprovalId,
        OffsetDateTime startDate,
        OffsetDateTime expiresAt,
        OffsetDateTime createdAt
) {
}
