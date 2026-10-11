package com.centauro.billing.infrastructure.mercadopago;

import com.centauro.billing.config.BillingProperties;
import com.centauro.billing.domain.port.PaymentGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/** Solo con {@code MP_ACCESS_TOKEN}: sin él no hay bean de {@link PaymentGateway} y el checkout da 502. */
@Configuration(proxyBeanMethods = false)
@Conditional(MercadoPagoConfiguredCondition.class)
public class MercadoPagoConfiguration {

    public static final String WEBHOOK_PATH = "/webhooks/mercadopago";

    private static final Logger log = LoggerFactory.getLogger(MercadoPagoConfiguration.class);

    @Bean
    public RestClient mercadoPagoRestClient(RestClient.Builder builder, MercadoPagoProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.connectTimeoutMs()))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(properties.readTimeoutMs()));
        return builder.clone()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.accessToken())
                .build();
    }

    @Bean
    public PaymentGateway mercadoPagoGateway(@Qualifier("mercadoPagoRestClient") RestClient restClient,
                                             BillingProperties billingProperties, ObjectMapper objectMapper) {
        String notificationUrl = notificationUrl(billingProperties.publicBaseUrl());
        log.info("Mercado Pago configurado; notification_url={}", notificationUrl == null ? "(sin configurar)"
                : notificationUrl);
        return new MercadoPagoGateway(restClient, notificationUrl, objectMapper);
    }

    static String notificationUrl(String publicBaseUrl) {
        if (publicBaseUrl == null || publicBaseUrl.isBlank()) {
            return null;
        }
        String base = publicBaseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + WEBHOOK_PATH;
    }
}
