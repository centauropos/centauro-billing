package com.centauro.billing.application.dto;

/** {@code created=false} cuando la referencia ya estaba registrada y se devuelve el pago original. */
public record ManualPaymentResult(PaymentView payment, TenantSubscriptionView subscription, boolean created) {
}
