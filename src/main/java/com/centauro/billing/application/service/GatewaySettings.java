package com.centauro.billing.application.service;

import java.time.Duration;

/**
 * Parámetros del checkout y de los reintentos de webhooks. {@code checkoutBackUrl} vacío: el checkout responde
 * 502 porque Mercado Pago exige {@code back_url}.
 */
public record GatewaySettings(Duration checkoutExpiration, String checkoutBackUrl, int webhookMaxAttempts) {

    public boolean hasBackUrl() {
        return checkoutBackUrl != null && !checkoutBackUrl.isBlank();
    }
}
