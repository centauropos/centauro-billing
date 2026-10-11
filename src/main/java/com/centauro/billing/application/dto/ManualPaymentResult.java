package com.centauro.billing.application.dto;

public record ManualPaymentResult(PaymentView payment, TenantSubscriptionView subscription) {
}
