package com.centauro.billing.web;

import com.centauro.billing.application.usecase.AccessQueryUseCase;
import com.centauro.billing.application.usecase.CheckoutUseCase;
import com.centauro.billing.application.usecase.WebhookUseCase;
import com.centauro.billing.infrastructure.mercadopago.MercadoPagoProperties;
import com.centauro.billing.infrastructure.mercadopago.MercadoPagoSignatureVerifier;
import com.centauro.billing.web.controller.MercadoPagoWebhookController;
import com.centauro.billing.application.usecase.ManualPaymentUseCase;
import com.centauro.billing.application.usecase.PlanAdminUseCase;
import com.centauro.billing.application.usecase.PlatformSubscriptionUseCase;
import com.centauro.billing.application.usecase.TenantSubscriptionUseCase;
import com.centauro.billing.application.usecase.TrialUseCase;
import com.centauro.billing.config.BillingProperties;
import com.centauro.billing.config.CorsConfig;
import com.centauro.billing.config.SecurityConfig;
import com.centauro.billing.domain.policy.BillingTerms;
import com.centauro.billing.web.controller.InternalBillingController;
import com.centauro.billing.web.controller.PlatformPlanController;
import com.centauro.billing.web.controller.PlatformSubscriptionController;
import com.centauro.billing.web.controller.TenantBillingController;
import com.centauro.billing.web.security.BillingAuthorization;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static com.centauro.billing.support.TestFixtures.TERMS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Cableado real de las cadenas de seguridad, sin base de datos: los use cases son mocks. */
@WebMvcTest(controllers = {TenantBillingController.class, InternalBillingController.class,
        PlatformSubscriptionController.class, PlatformPlanController.class, MercadoPagoWebhookController.class})
@Import({SecurityConfig.class, CorsConfig.class, BillingAuthorization.class, MercadoPagoSignatureVerifier.class,
        SecurityWiringTest.TestBeans.class})
@TestPropertySource(properties = {
        "jwt.secret=" + SecurityWiringTest.SECRET,
        "billing.internal.api-key=" + SecurityWiringTest.API_KEY,
        "billing.mercadopago.webhook-secret=" + SecurityWiringTest.WEBHOOK_SECRET
})
class SecurityWiringTest {

    static final String SECRET = "test-secret-key-which-is-long-enough-for-hs256";
    static final String API_KEY = "internal-test-key";
    static final String WEBHOOK_SECRET = "webhook-test-secret";

    @TestConfiguration
    @EnableConfigurationProperties({BillingProperties.class, MercadoPagoProperties.class})
    static class TestBeans {
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        BillingTerms billingTerms() {
            return TERMS;
        }
    }

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private TenantSubscriptionUseCase tenantSubscriptionUseCase;
    @MockitoBean
    private PlatformSubscriptionUseCase platformSubscriptionUseCase;
    @MockitoBean
    private PlanAdminUseCase planAdminUseCase;
    @MockitoBean
    private TrialUseCase trialUseCase;
    @MockitoBean
    private ManualPaymentUseCase manualPaymentUseCase;
    @MockitoBean
    private AccessQueryUseCase accessQueryUseCase;
    @MockitoBean
    private CheckoutUseCase checkoutUseCase;
    @MockitoBean
    private WebhookUseCase webhookUseCase;

    @Test
    void platformWithoutPermissionIsForbidden() throws Exception {
        mvc.perform(get("/api/v1/platform/plans").header("Authorization", bearer(List.of("BILLING_MANAGE"), "ADMIN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void roleNamedPlatformAdminDoesNotOpenThePlatform() throws Exception {
        mvc.perform(get("/api/v1/platform/plans").header("Authorization", bearer(List.of(), "PLATFORM_ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void platformWithPermissionIsNotForbidden() throws Exception {
        int status = mvc.perform(get("/api/v1/platform/plans")
                        .header("Authorization", bearer(List.of("PLATFORM_ADMIN"), "USER")))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(200);
    }

    @Test
    void platformWithoutTokenIsUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/platform/subscriptions")).andExpect(status().isUnauthorized());
    }

    @Test
    void internalWithoutKeyIsUnauthorized() throws Exception {
        mvc.perform(get("/internal/v1/tenants/t-1/access"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_API_KEY"));
    }

    @Test
    void internalWithKeyPasses() throws Exception {
        mvc.perform(get("/internal/v1/tenants/t-1/access").header("X-Internal-Api-Key", API_KEY))
                .andExpect(status().isOk());
    }

    @Test
    void tenantApiWithoutTokenIsUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/billing/subscription"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.timestamp").value(org.hamcrest.Matchers.endsWith("-03:00")));
    }

    @Test
    void unknownRoutesAreDenied() throws Exception {
        mvc.perform(get("/no-existe")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/otra-cosa").header("Authorization", bearer(List.of("PLATFORM_ADMIN"), "ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void webhookWithValidSignatureIsPublic() throws Exception {
        String ts = String.valueOf(Instant.now().getEpochSecond());
        String manifest = "id:123456;request-id:req-1;ts:" + ts + ";";

        mvc.perform(post("/webhooks/mercadopago").param("data.id", "123456").param("type", "subscription_preapproval")
                        .header("x-request-id", "req-1")
                        .header("x-signature", "ts=" + ts + ",v1=" + hmac(manifest))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"n-1\",\"type\":\"subscription_preapproval\",\"data\":{\"id\":\"123456\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(true));

        verify(webhookUseCase).receive(any());
    }

    @Test
    void webhookWithoutOrWithBadSignatureIsUnauthorizedAndNotStored() throws Exception {
        mvc.perform(post("/webhooks/mercadopago").param("data.id", "123456")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"data\":{\"id\":\"123456\"}}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_SIGNATURE"));
        mvc.perform(post("/webhooks/mercadopago").param("data.id", "123456")
                        .header("x-request-id", "req-1")
                        .header("x-signature", "ts=1700000000,v1=" + "0".repeat(64))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"data\":{\"id\":\"123456\"}}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(webhookUseCase);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder signedWebhook(String dataId)
            throws Exception {
        String ts = String.valueOf(Instant.now().getEpochSecond());
        String manifest = "id:" + dataId + ";request-id:req-1;ts:" + ts + ";";
        return post("/webhooks/mercadopago").param("data.id", dataId).param("type", "payment")
                .header("x-request-id", "req-1")
                .header("x-signature", "ts=" + ts + ",v1=" + hmac(manifest))
                .contentType(MediaType.APPLICATION_JSON);
    }

    @Test
    void webhookWithoutDataIdInTheQueryIsABadRequestAndNotStored() throws Exception {
        mvc.perform(post("/webhooks/mercadopago").header("x-request-id", "req-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"data\":{\"id\":\"123456\"}}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(webhookUseCase);
    }

    @Test
    void theOnlyResourceUsedIsTheSignedDataIdOfTheQuery() throws Exception {
        mvc.perform(signedWebhook("123456").content("{\"id\":\"n-1\",\"data\":{\"id\":\"999999\"}}"))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<com.centauro.billing.domain.vo.WebhookNotification> captor =
                org.mockito.ArgumentCaptor.forClass(com.centauro.billing.domain.vo.WebhookNotification.class);
        verify(webhookUseCase).receive(captor.capture());
        assertThat(captor.getValue().resourceId()).isEqualTo("123456");
        assertThat(captor.getValue().requestId()).isEqualTo("req-1");
    }

    @Test
    void invalidBodyWithAValidSignatureIsABadRequestAndNotStored() throws Exception {
        mvc.perform(signedWebhook("123456").content("{no es json"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(webhookUseCase);
    }

    @Test
    void bodyAboveTheLimitIsRejectedWith413() throws Exception {
        String big = "{\"pad\":\"" + "x".repeat(70_000) + "\"}";

        mvc.perform(signedWebhook("123456").content(big))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));

        verifyNoInteractions(webhookUseCase);
    }

    @Test
    void otherWebhookRoutesAndMethodsStayClosed() throws Exception {
        mvc.perform(get("/webhooks/mercadopago")).andExpect(status().isUnauthorized());
        mvc.perform(post("/webhooks/otro")).andExpect(status().isUnauthorized());
    }

    @Test
    void checkoutRequiresToken() throws Exception {
        mvc.perform(post("/api/v1/billing/checkout").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planCode\":\"PROFESIONAL\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void checkoutRequiresBillingManage() throws Exception {
        mvc.perform(post("/api/v1/billing/checkout").header("Authorization", bearer(List.of(), "USER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"planCode\":\"PROFESIONAL\"}"))
                .andExpect(status().isForbidden());
    }

    private static String hmac(String manifest) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(manifest.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void healthIsNotBlockedBySecurity() throws Exception {
        int status = mvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus();

        assertThat(status).isNotIn(401, 403);
    }

    private static String bearer(List<String> permissions, String role) {
        Instant now = Instant.now();
        return "Bearer " + Jwts.builder()
                .subject("someone")
                .claims(Map.of("tenantId", "t-1", "id", 99, "role", role, "permissions", permissions))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(3600)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }
}
