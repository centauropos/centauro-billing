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
     * {@code requireSignature=false} solo tiene efecto sin {@code webhook-secret} (con secreto la firma es siempre
     * obligatoria) y no se admite con un access token de producción. {@code maxSkewSeconds}: tolerancia del
     * {@code ts} de la firma contra el reloj; 0 la desactiva. {@code maxBodyBytes}: más grande responde 413.
     */
    public record Webhook(@DefaultValue("true") boolean requireSignature,
                          @DefaultValue("300") long maxSkewSeconds,
                          @DefaultValue("65536") int maxBodyBytes) {
    }

    /** Prefijo de los access tokens de producción de Mercado Pago. */
    static final String PRODUCTION_TOKEN_PREFIX = "APP_USR-";

    public boolean hasAccessToken() {
        return accessToken != null && !accessToken.isBlank();
    }

    public boolean isProductionToken() {
        return hasAccessToken() && accessToken.trim().startsWith(PRODUCTION_TOKEN_PREFIX);
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
