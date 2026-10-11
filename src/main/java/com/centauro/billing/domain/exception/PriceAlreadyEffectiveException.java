package com.centauro.billing.domain.exception;

public class PriceAlreadyEffectiveException extends BillingException {

    public PriceAlreadyEffectiveException(String priceId) {
        super("PRICE_ALREADY_EFFECTIVE", "El precio " + priceId + " ya está vigente y no se puede borrar");
    }
}
