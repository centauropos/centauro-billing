package com.centauro.billing.domain.exception;

public class PlanNotFoundException extends BillingException {

    public PlanNotFoundException(String planCode) {
        super("PLAN_NOT_FOUND", "No existe el plan " + planCode);
    }
}
