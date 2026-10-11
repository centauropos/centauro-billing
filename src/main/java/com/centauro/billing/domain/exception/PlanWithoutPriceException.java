package com.centauro.billing.domain.exception;

public class PlanWithoutPriceException extends BillingException {

    public PlanWithoutPriceException(String planCode) {
        super("PLAN_WITHOUT_PRICE", "El plan " + planCode + " no tiene precio vigente");
    }
}
