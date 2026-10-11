package com.centauro.billing.application.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/** {@code reused = true}: se devolvió el checkout pendiente existente (HTTP 200 en vez de 201). */
public record CheckoutView(
        UUID checkoutId,
        String initPoint,
        OffsetDateTime expiresAt,
        OffsetDateTime startDate,
        String amount,
        String currency,
        String planCode,
        boolean reused
) {
}
