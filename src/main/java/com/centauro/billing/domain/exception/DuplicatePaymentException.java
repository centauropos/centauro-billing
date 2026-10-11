package com.centauro.billing.domain.exception;

public class DuplicatePaymentException extends BillingException {

    public DuplicatePaymentException(String reference) {
        super("DUPLICATE_PAYMENT", "Ya hay un pago registrado con la referencia " + reference);
    }
}
