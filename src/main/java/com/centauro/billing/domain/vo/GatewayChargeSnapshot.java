package com.centauro.billing.domain.vo;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record GatewayChargeSnapshot(
        String gatewayChargeId,
        String gatewaySubscriptionId,
        String gatewayPaymentId,
        String status,
        BigDecimal amount,
        OffsetDateTime chargedAt,
        OffsetDateTime lastModified
) {
}
