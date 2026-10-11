package com.centauro.billing.domain.vo;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record GatewaySubscriptionSnapshot(
        String gatewaySubscriptionId,
        String status,
        String externalReference,
        BigDecimal amount,
        OffsetDateTime nextChargeAt,
        OffsetDateTime lastModified
) {
}
