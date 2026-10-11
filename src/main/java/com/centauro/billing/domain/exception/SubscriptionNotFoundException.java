package com.centauro.billing.domain.exception;

public class SubscriptionNotFoundException extends BillingException {

    public SubscriptionNotFoundException(String tenantId) {
        super("SUBSCRIPTION_NOT_FOUND", "No existe suscripción para el tenant " + tenantId);
    }
}
