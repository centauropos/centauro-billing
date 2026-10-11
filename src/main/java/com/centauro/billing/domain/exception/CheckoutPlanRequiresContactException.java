package com.centauro.billing.domain.exception;

/** Mismo código que {@link PlanRequiresContactException}, pero en el checkout responde 409 (el plan existe). */
public class CheckoutPlanRequiresContactException extends PlanRequiresContactException {

    public CheckoutPlanRequiresContactException(String planCode) {
        super(planCode);
    }
}
