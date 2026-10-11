package com.centauro.billing.domain.vo;

import com.centauro.billing.domain.enums.GatewaySubscriptionStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Estado real de la suscripción en la pasarela, reconsultado (nunca el payload del webhook). */
public record GatewaySubscriptionSnapshot(
        String gatewaySubscriptionId,
        GatewaySubscriptionStatus status,
        String rawStatus,
        String externalReference,
        BigDecimal amount,
        String currency,
        OffsetDateTime nextChargeAt,
        OffsetDateTime lastModified
) {
}
