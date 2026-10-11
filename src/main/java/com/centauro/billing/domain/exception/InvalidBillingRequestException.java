package com.centauro.billing.domain.exception;

public class InvalidBillingRequestException extends BillingException {

    public InvalidBillingRequestException(String message) {
        super("INVALID_REQUEST", message);
    }
}
