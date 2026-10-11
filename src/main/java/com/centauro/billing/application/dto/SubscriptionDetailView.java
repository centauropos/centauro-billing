package com.centauro.billing.application.dto;

import java.util.List;

public record SubscriptionDetailView(
        TenantSubscriptionView subscription,
        String companyName,
        String billingEmail,
        String gateway,
        String gatewaySubscriptionId,
        String gatewayStatus,
        Long version,
        List<SubscriptionEventView> events,
        List<PaymentView> payments,
        List<CheckoutSummaryView> checkouts
) {
}