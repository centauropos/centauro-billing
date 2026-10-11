package com.centauro.billing.domain.exception;

/** Doble click: otro checkout para el mismo plan se está creando en la pasarela. */
public class CheckoutInProgressException extends BillingException {

    public CheckoutInProgressException() {
        super("CHECKOUT_IN_PROGRESS", "Ya hay un pago con Mercado Pago iniciándose; reintentar en unos segundos");
    }
}
