package com.centauro.billing.domain.exception;

public class SubscriptionAdminSuspendedException extends BillingException {

    public SubscriptionAdminSuspendedException(String tenantId) {
        super("SUBSCRIPTION_ADMIN_SUSPENDED", "La suscripción de " + tenantId
                + " está suspendida por la plataforma; reactivarla antes de registrar un pago");
    }
}
