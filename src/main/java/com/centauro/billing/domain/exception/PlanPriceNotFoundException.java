package com.centauro.billing.domain.exception;

public class PlanPriceNotFoundException extends BillingException {

    public PlanPriceNotFoundException(String priceId) {
        super("PLAN_PRICE_NOT_FOUND", "No existe el precio " + priceId);
    }
}
