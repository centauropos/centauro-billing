package com.centauro.billing.application.service;

import java.time.Duration;

/**
 * Parámetros del checkout, de los reintentos de webhooks y de la conciliación. {@code checkoutBackUrl} vacío: el
 * checkout responde 502 porque Mercado Pago exige {@code back_url}. La conciliación corta al llegar a
 * {@code reconciliationMaxItems} elementos o a {@code reconciliationMaxDuration}, lo primero que pase.
 */
public record GatewaySettings(Duration checkoutExpiration, String checkoutBackUrl, int webhookMaxAttempts,
                              int reconciliationMaxItems, Duration reconciliationMaxDuration) {

    public static final int DEFAULT_RECONCILIATION_MAX_ITEMS = 2000;
    public static final Duration DEFAULT_RECONCILIATION_MAX_DURATION = Duration.ofMinutes(20);

    public GatewaySettings(Duration checkoutExpiration, String checkoutBackUrl, int webhookMaxAttempts) {
        this(checkoutExpiration, checkoutBackUrl, webhookMaxAttempts, DEFAULT_RECONCILIATION_MAX_ITEMS,
                DEFAULT_RECONCILIATION_MAX_DURATION);
    }

    public boolean hasBackUrl() {
        return checkoutBackUrl != null && !checkoutBackUrl.isBlank();
    }
}
