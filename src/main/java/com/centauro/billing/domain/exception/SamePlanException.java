package com.centauro.billing.domain.exception;

public class SamePlanException extends BillingException {

    public SamePlanException(String planCode) {
        super("SAME_PLAN", "La suscripción ya está en el plan " + planCode);
    }
}
