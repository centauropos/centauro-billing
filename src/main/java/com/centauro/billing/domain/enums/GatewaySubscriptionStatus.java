package com.centauro.billing.domain.enums;

import java.util.Locale;

/** Estado normalizado de la suscripción en la pasarela (preapproval en Mercado Pago). */
public enum GatewaySubscriptionStatus {
    PENDING, AUTHORIZED, PAUSED, CANCELLED, UNKNOWN;

    /** Valor que se guarda en {@code subscriptions.gateway_status}. */
    public String storedValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
