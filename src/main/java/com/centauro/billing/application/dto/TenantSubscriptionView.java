package com.centauro.billing.application.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record TenantSubscriptionView(
        String tenantId,
        String status,
        String accessLevel,
        PlanSummaryView plan,
        PriceView price,
        OffsetDateTime trialEndsAt,
        Integer trialDaysLeft,
        OffsetDateTime currentPeriodStart,
        OffsetDateTime currentPeriodEnd,
        OffsetDateTime graceEndsAt,
        boolean cancelAtPeriodEnd,
        boolean hasPaymentMethod,
        OffsetDateTime nextChargeAt,
        PendingCheckoutView pendingCheckout,
        NoticeView notice,
        List<String> allowedActions
) {
}