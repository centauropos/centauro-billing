package com.centauro.billing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "billing")
public record BillingProperties(
        @DefaultValue("America/Argentina/Buenos_Aires") String zone,
        @DefaultValue Internal internal,
        String publicBaseUrl,
        @DefaultValue Trial trial,
        @DefaultValue Grace grace,
        @DefaultValue Checkout checkout,
        @DefaultValue Jobs jobs
) {

    public record Internal(String apiKey) {
    }

    public record Trial(@DefaultValue("14") int days,
                        @DefaultValue("PROFESIONAL") String planCode,
                        @DefaultValue("3") int graceDays) {
    }

    public record Grace(@DefaultValue("7") int days) {
    }

    public record Checkout(@DefaultValue("48") int expirationHours, String backUrl) {
    }

    public record Jobs(@DefaultValue("true") boolean enabled,
                       @DefaultValue("0 */15 * * * *") String lifecycleCron,
                       @DefaultValue("0 0 * * * *") String checkoutExpirationCron,
                       @DefaultValue("0 */5 * * * *") String webhookRetryCron,
                       @DefaultValue("0 30 4 * * *") String reconciliationCron,
                       @DefaultValue("10") int webhookMaxAttempts,
                       @DefaultValue("2000") int reconciliationMaxItems,
                       @DefaultValue("20") int reconciliationMaxMinutes) {
    }
}
