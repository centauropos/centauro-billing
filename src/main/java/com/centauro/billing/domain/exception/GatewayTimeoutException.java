package com.centauro.billing.domain.exception;

public class GatewayTimeoutException extends BillingException {

    public GatewayTimeoutException(String message) {
        super("GATEWAY_TIMEOUT", message);
    }
}
