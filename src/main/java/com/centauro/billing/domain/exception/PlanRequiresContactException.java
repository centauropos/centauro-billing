package com.centauro.billing.domain.exception;

public class PlanRequiresContactException extends BillingException {

    public PlanRequiresContactException(String planCode) {
        super("PLAN_REQUIRES_CONTACT", "El plan " + planCode + " tiene precio a medida: contactar a ventas");
    }
}
