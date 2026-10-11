package com.centauro.billing.domain.exception;

public class InvalidWebhookSignatureException extends BillingException {

    public InvalidWebhookSignatureException() {
        super("INVALID_SIGNATURE", "Firma del webhook inválida");
    }
}
