package com.centauro.billing.domain.vo;

import com.centauro.billing.domain.enums.SubscriptionStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SubscriptionSearchCriteria(
        SubscriptionStatus status,
        UUID planId,
        String tenantId,
        String query,
        OffsetDateTime trialEndsBefore
) {
}