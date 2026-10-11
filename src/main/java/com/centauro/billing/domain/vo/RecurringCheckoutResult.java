package com.centauro.billing.domain.vo;

public record RecurringCheckoutResult(
        String gatewaySubscriptionId,
        String initPoint,
        String status
) {
}
