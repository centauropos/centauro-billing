package com.centauro.billing.domain.exception;

public class WebhookPayloadTooLargeException extends BillingException {

    public WebhookPayloadTooLargeException(int maxBytes) {
        super("PAYLOAD_TOO_LARGE", "El cuerpo del webhook supera los " + maxBytes + " bytes");
    }
}
