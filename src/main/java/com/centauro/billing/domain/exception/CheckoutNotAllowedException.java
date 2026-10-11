package com.centauro.billing.domain.exception;

public class CheckoutNotAllowedException extends BillingException {

    public CheckoutNotAllowedException(String message) {
        super("CHECKOUT_NOT_ALLOWED", message);
    }
}
