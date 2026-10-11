package com.centauro.billing.domain.exception;

public class DuplicatePlanException extends BillingException {

    public DuplicatePlanException(String planCode) {
        super("DUPLICATE_PLAN", "Ya existe el plan " + planCode);
    }
}
