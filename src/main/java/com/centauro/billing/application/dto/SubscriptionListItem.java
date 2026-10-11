package com.centauro.billing.application.dto;

import java.time.OffsetDateTime;

public record SubscriptionListItem(
        String tenantId,
        String companyName,
        String billingEmail,
        String status,
        String accessLevel,
        String planCode,
        OffsetDateTime trialEndsAt,
        OffsetDateTime currentPeriodEnd,
        OffsetDateTime graceEndsAt,
        boolean cancelAtPeriodEnd,
        String gatewayStatus,
        OffsetDateTime createdAt
) {
}