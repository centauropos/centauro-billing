package com.centauro.billing.infrastructure.mercadopago;

import com.centauro.billing.domain.enums.GatewayChargeStatus;
import com.centauro.billing.domain.enums.GatewaySubscriptionStatus;
import com.centauro.billing.domain.exception.GatewayRejectedException;
import com.centauro.billing.domain.exception.GatewayTimeoutException;
import com.centauro.billing.domain.exception.GatewayUnavailableException;
import com.centauro.billing.domain.vo.GatewayChargeSnapshot;
import com.centauro.billing.domain.vo.GatewaySubscriptionSnapshot;
import com.centauro.billing.domain.vo.RecurringCheckoutRequest;
import com.centauro.billing.domain.vo.RecurringCheckoutResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MercadoPagoGatewayTest {

    private static final String BASE = "https://api.mp.test";
    private static final String NOTIFICATION_URL = "https://billing.test/webhooks/mercadopago";

    private MockRestServiceServer server;
    private MercadoPagoGateway gateway;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        gateway = new MercadoPagoGateway(builder.build(), NOTIFICATION_URL, new ObjectMapper());
    }

    private static RecurringCheckoutRequest request(UUID checkoutId, OffsetDateTime startDate) {
        return new RecurringCheckoutRequest(checkoutId, "owner@test.com", "Centauro - plan Profesional",
                new BigDecimal("59900"), "ARS", startDate, "https://app.test/billing?checkout=" + checkoutId,
                "card-tok-xyz");
    }

    @Test
    void createsAnAuthorizedPreapprovalWithCardTokenAndIdempotencyKey() {
        UUID checkoutId = UUID.randomUUID();
        OffsetDateTime start = OffsetDateTime.of(2026, 10, 25, 10, 0, 0, 0, ZoneOffset.ofHours(-3));
        server.expect(requestTo(BASE + "/preapproval"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(MercadoPagoGateway.IDEMPOTENCY_HEADER, checkoutId.toString()))
                .andExpect(jsonPath("$.external_reference").value(checkoutId.toString()))
                .andExpect(jsonPath("$.payer_email").value("owner@test.com"))
                .andExpect(jsonPath("$.status").value("authorized"))
                .andExpect(jsonPath("$.card_token_id").value("card-tok-xyz"))
                .andExpect(jsonPath("$.back_url").value("https://app.test/billing?checkout=" + checkoutId))
                .andExpect(jsonPath("$.notification_url").value(NOTIFICATION_URL))
                .andExpect(jsonPath("$.preapproval_plan_id").doesNotExist())
                .andExpect(jsonPath("$.auto_recurring.frequency").value(1))
                .andExpect(jsonPath("$.auto_recurring.frequency_type").value("months"))
                .andExpect(jsonPath("$.auto_recurring.transaction_amount").value(59900.0))
                .andExpect(jsonPath("$.auto_recurring.currency_id").value("ARS"))
                .andExpect(jsonPath("$.auto_recurring.start_date").value("2026-10-25T13:00:00.000Z"))
                .andRespond(withSuccess("{\"id\":\"pre-1\",\"status\":\"authorized\"}", MediaType.APPLICATION_JSON));

        RecurringCheckoutResult result = gateway.createRecurringCheckout(request(checkoutId, start));

        assertThat(result.gatewaySubscriptionId()).isEqualTo("pre-1");
        assertThat(result.initPoint()).isNull();
        assertThat(result.status()).isEqualTo("authorized");
        server.verify();
    }

    @Test
    void withoutStartDateTheFieldIsOmittedAndInitPointMayBeAbsent() {
        server.expect(requestTo(BASE + "/preapproval"))
                .andExpect(jsonPath("$.auto_recurring.start_date").doesNotExist())
                .andExpect(jsonPath("$.status").value("authorized"))
                .andExpect(jsonPath("$.card_token_id").value("card-tok-xyz"))
                .andRespond(withSuccess("{\"id\":\"pre-1\",\"status\":\"authorized\"}", MediaType.APPLICATION_JSON));

        RecurringCheckoutResult result = gateway.createRecurringCheckout(request(UUID.randomUUID(), null));

        assertThat(result.initPoint()).isNull();
        server.verify();
    }

    @Test
    void preapprovalWithoutIdIsAnError() {
        server.expect(requestTo(BASE + "/preapproval"))
                .andRespond(withSuccess("{\"status\":\"authorized\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.createRecurringCheckout(request(UUID.randomUUID(), null)))
                .isInstanceOf(GatewayUnavailableException.class);
    }

    @Test
    void missingCardTokenIsRejectedWithoutCallingMercadoPago() {
        RecurringCheckoutRequest noToken = new RecurringCheckoutRequest(UUID.randomUUID(), "owner@test.com",
                "Centauro", new BigDecimal("59900"), "ARS", null, "https://app.test/billing", null);

        assertThatThrownBy(() -> gateway.createRecurringCheckout(noToken))
                .isInstanceOf(GatewayRejectedException.class);
        server.verify();
    }

    @Test
    void creationIsNotRetriedOnServerError() {
        server.expect(requestTo(BASE + "/preapproval")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> gateway.createRecurringCheckout(request(UUID.randomUUID(), null)))
                .isInstanceOf(GatewayUnavailableException.class);
        server.verify();
    }

    @Test
    void creationTimeoutIsReportedAsTimeout() {
        server.expect(requestTo(BASE + "/preapproval")).andRespond(withException(new SocketTimeoutException("read")));

        assertThatThrownBy(() -> gateway.createRecurringCheckout(request(UUID.randomUUID(), null)))
                .isInstanceOf(GatewayTimeoutException.class);
    }

    @Test
    void clientErrorIsRejectedWithMessageButWithoutEmails() {
        server.expect(requestTo(BASE + "/preapproval"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"Invalid payer_email owner@test.com\",\"status\":400}"));

        assertThatThrownBy(() -> gateway.createRecurringCheckout(request(UUID.randomUUID(), null)))
                .isInstanceOf(GatewayRejectedException.class)
                .hasMessageContaining("400")
                .hasMessageContaining("***")
                .hasMessageNotContaining("owner@test.com");
    }

    @Test
    void clientErrorRedactsCardTokenIdsFromTheMessage() {
        String fakeToken = "card_tok_fake_9f3a2b1c0d8e7f6a";
        server.expect(requestTo(BASE + "/preapproval"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"Invalid card_token_id " + fakeToken + "\",\"status\":400}"));

        assertThatThrownBy(() -> gateway.createRecurringCheckout(request(UUID.randomUUID(), null)))
                .isInstanceOf(GatewayRejectedException.class)
                .hasMessageContaining("card_token_id")
                .hasMessageContaining("***")
                .hasMessageNotContaining(fakeToken);
    }

    @Test
    void redactSensitiveMasksEmailsAndCardTokens() {
        assertThat(MercadoPagoGateway.redactSensitive(
                "bad card_token_id=tok_abc123 for payer_email owner@test.com"))
                .isEqualTo("bad card_token_id=*** for payer_email ***")
                .doesNotContain("tok_abc123")
                .doesNotContain("owner@test.com");
    }

    @Test
    void readsAndMapsAPreapproval() {
        server.expect(requestTo(BASE + "/preapproval/pre-1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"id\":\"pre-1\",\"status\":\"authorized\",\"external_reference\":\"ref-1\","
                        + "\"auto_recurring\":{\"transaction_amount\":59900.00,\"currency_id\":\"ARS\"},"
                        + "\"next_payment_date\":\"2026-11-11T10:00:00.000-0400\","
                        + "\"last_modified\":\"2026-10-11T10:00:00.123-04:00\",\"payer_email\":\"x@y.com\"}",
                        MediaType.APPLICATION_JSON));

        GatewaySubscriptionSnapshot snapshot = gateway.getSubscription("pre-1");

        assertThat(snapshot.status()).isEqualTo(GatewaySubscriptionStatus.AUTHORIZED);
        assertThat(snapshot.externalReference()).isEqualTo("ref-1");
        assertThat(snapshot.amount()).isEqualByComparingTo("59900");
        assertThat(snapshot.currency()).isEqualTo("ARS");
        assertThat(snapshot.nextChargeAt()).isEqualTo(OffsetDateTime.of(2026, 11, 11, 10, 0, 0, 0,
                ZoneOffset.ofHours(-4)));
        assertThat(snapshot.lastModified()).isNotNull();
    }

    @Test
    void readsAreRetriedOnceOnServerError() {
        server.expect(requestTo(BASE + "/preapproval/pre-1")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(BASE + "/preapproval/pre-1"))
                .andRespond(withSuccess("{\"id\":\"pre-1\",\"status\":\"paused\"}", MediaType.APPLICATION_JSON));

        assertThat(gateway.getSubscription("pre-1").status()).isEqualTo(GatewaySubscriptionStatus.PAUSED);
        server.verify();
    }

    @Test
    void readsFailAfterTheRetry() {
        server.expect(requestTo(BASE + "/preapproval/pre-1")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(BASE + "/preapproval/pre-1")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> gateway.getSubscription("pre-1")).isInstanceOf(GatewayUnavailableException.class);
        server.verify();
    }

    @Test
    void readsAndMapsAnAuthorizedPayment() {
        server.expect(requestTo(BASE + "/authorized_payments/ap-1"))
                .andRespond(withSuccess("{\"id\":\"ap-1\",\"preapproval_id\":\"pre-1\",\"status\":\"processed\","
                        + "\"transaction_amount\":59900,\"currency_id\":\"ARS\","
                        + "\"debit_date\":\"2026-10-11T10:00:00.000-03:00\","
                        + "\"payment\":{\"id\":\"99\",\"status\":\"approved\",\"status_detail\":\"accredited\"}}",
                        MediaType.APPLICATION_JSON));

        GatewayChargeSnapshot charge = gateway.getCharge("ap-1");

        assertThat(charge.chargeId()).isEqualTo("ap-1");
        assertThat(charge.gatewaySubscriptionId()).isEqualTo("pre-1");
        assertThat(charge.transactionId()).isEqualTo("99");
        assertThat(charge.status()).isEqualTo(GatewayChargeStatus.APPROVED);
        assertThat(charge.rawStatus()).isEqualTo("processed/approved");
        assertThat(charge.amount()).isEqualByComparingTo("59900");
        assertThat(charge.chargedAt()).isNotNull();
    }

    @Test
    void listsChargesAcrossPages() {
        String firstPage = IntStream.range(0, MercadoPagoGateway.SEARCH_PAGE_SIZE)
                .mapToObj(i -> "{\"id\":\"ap-" + i + "\",\"preapproval_id\":\"pre-1\",\"status\":\"scheduled\"}")
                .collect(Collectors.joining(",", "{\"paging\":{\"total\":51},\"results\":[", "]}"));
        server.expect(requestTo(startsWith(BASE + "/authorized_payments/search")))
                .andExpect(queryParam("preapproval_id", "pre-1"))
                .andExpect(queryParam("offset", "0"))
                .andRespond(withSuccess(firstPage, MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(BASE + "/authorized_payments/search")))
                .andExpect(queryParam("offset", "50"))
                .andRespond(withSuccess("{\"paging\":{\"total\":51},\"results\":[{\"id\":\"ap-50\","
                        + "\"preapproval_id\":\"pre-1\",\"status\":\"recycling\"}]}", MediaType.APPLICATION_JSON));

        List<GatewayChargeSnapshot> charges = gateway.listCharges("pre-1");

        assertThat(charges).hasSize(51);
        assertThat(charges.get(0).status()).isEqualTo(GatewayChargeStatus.PENDING);
        assertThat(charges.get(50).status()).isEqualTo(GatewayChargeStatus.REJECTED);
        server.verify();
    }

    @Test
    void cancelSendsOnlyTheStatus() {
        server.expect(requestTo(BASE + "/preapproval/pre-1"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.status").value(MercadoPagoGateway.CANCELLED_STATUS))
                .andExpect(jsonPath("$.auto_recurring").doesNotExist())
                .andRespond(withSuccess());

        gateway.cancel("pre-1");

        server.verify();
    }

    @Test
    void updateAmountSendsOnlyTheAmountInArs() {
        server.expect(requestTo(BASE + "/preapproval/pre-1"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.status").doesNotExist())
                .andExpect(jsonPath("$.auto_recurring.transaction_amount").value(14900.0))
                .andExpect(jsonPath("$.auto_recurring.currency_id").value("ARS"))
                .andExpect(jsonPath("$.auto_recurring.frequency").doesNotExist())
                .andRespond(withSuccess());

        gateway.updateAmount("pre-1", new BigDecimal("14900"));

        server.verify();
    }

    @Test
    void cancelIsNotRetried() {
        server.expect(requestTo(BASE + "/preapproval/pre-1")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> gateway.cancel("pre-1")).isInstanceOf(GatewayUnavailableException.class);
        server.verify();
    }
}
