package com.centauro.billing.application.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Vista del checkout del tenant. {@code initPoint} es nullable (flujo Brick no lo usa; checkouts legacy
 * pending pueden traerlo). {@code reused = true}: se devolvió el pendiente existente o una autorización
 * idempotente (HTTP 200 en vez de 201).
 */
public record CheckoutView(
        UUID checkoutId,
        String initPoint,
        OffsetDateTime expiresAt,
        OffsetDateTime startDate,
        String amount,
        String currency,
        String planCode,
        String payerEmail,
        boolean reused
) {
}
