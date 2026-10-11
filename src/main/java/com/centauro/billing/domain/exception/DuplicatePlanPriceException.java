package com.centauro.billing.domain.exception;

public class DuplicatePlanPriceException extends BillingException {

    public DuplicatePlanPriceException(String planCode) {
        super("DUPLICATE_PLAN_PRICE", "El plan " + planCode + " ya tiene un precio con esa vigencia");
    }
}
