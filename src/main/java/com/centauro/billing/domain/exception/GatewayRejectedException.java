package com.centauro.billing.domain.exception;

public class GatewayRejectedException extends BillingException {

    public GatewayRejectedException(String message) {
        super("GATEWAY_REJECTED", message);
    }
}
