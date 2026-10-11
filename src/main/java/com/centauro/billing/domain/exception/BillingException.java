package com.centauro.billing.domain.exception;

public abstract class BillingException extends RuntimeException {

    private final String code;

    protected BillingException(String code, String message) {
        super(message);
        this.code = code;
    }

    protected BillingException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}