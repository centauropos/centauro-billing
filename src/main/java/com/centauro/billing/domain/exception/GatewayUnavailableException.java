package com.centauro.billing.domain.exception;

public class GatewayUnavailableException extends BillingException {

    public GatewayUnavailableException(String message) {
        super("GATEWAY_UNAVAILABLE", message);
    }
}
