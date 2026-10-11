package com.centauro.billing.domain.exception;

public class PlanCustomPricingException extends BillingException {

    public PlanCustomPricingException(String planCode) {
        super("PLAN_CUSTOM_PRICING", "El plan " + planCode + " tiene precio a medida y no admite precios versionados");
    }
}
