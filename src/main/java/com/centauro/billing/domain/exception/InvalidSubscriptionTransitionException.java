package com.centauro.billing.domain.exception;

public class InvalidSubscriptionTransitionException extends BillingException {

    public InvalidSubscriptionTransitionException(String message) {
        super("INVALID_TRANSITION", message);
    }
}
