package com.centauro.billing.domain.exception;

import java.util.UUID;

public class CheckoutNotFoundException extends BillingException {

    public CheckoutNotFoundException(UUID checkoutId) {
        super("CHECKOUT_NOT_FOUND", "No existe el checkout " + checkoutId);
    }
}
