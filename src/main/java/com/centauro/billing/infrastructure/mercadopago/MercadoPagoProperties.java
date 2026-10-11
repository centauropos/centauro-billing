package com.centauro.billing.infrastructure.mercadopago;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Ninguna es obligatoria para arrancar: sin {@code access-token} no se crea la pasarela, y sin
 * {@code webhook-secret} el webhook responde 401 a todo.
 */
@ConfigurationProperties(prefix = "billing.mercadopago")
public record MercadoPagoProperties(
        @DefaultValue("https://api.mercadopago.com") String baseUrl,
        String accessToken,
        String webhookSecret,
        @DefaultValue Webhook webhook,
        @DefaultValue("5000") int connectTimeoutMs,
        @DefaultValue("15000") int readTimeoutMs
) {

    /**
     * {@code maxSkewSeconds}: tolerancia del {@code ts} de la firma contra el reloj; 0 la desactiva.
     */
    public record Webhook(@DefaultValue("true") boolean requireSignature,
                          @DefaultValue("0") long maxSkewSeconds) {
    }

    public boolean hasAccessToken() {
        return accessToken != null && !accessToken.isBlank();
    }

    public boolean hasWebhookSecret() {
        return webhookSecret != null && !webhookSecret.isBlank();
    }

    /** Nunca exponer los secretos (logs de arranque, actuator, excepciones de binding). */
    @Override
    public String toString() {
        return "MercadoPagoProperties[baseUrl=" + baseUrl + ", accessToken=" + (hasAccessToken() ? "***" : "")
                + ", webhookSecret=" + (hasWebhookSecret() ? "***" : "") + ", webhook=" + webhook
                + ", connectTimeoutMs=" + connectTimeoutMs + ", readTimeoutMs=" + readTimeoutMs + "]";
    }
}
