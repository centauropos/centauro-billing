package com.centauro.billing.web;

import com.centauro.billing.application.usecase.AccessQueryUseCase;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static com.centauro.billing.support.TestFixtures.TERMS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Cableado real de las cadenas de seguridad, sin base de datos: los use cases son mocks. */
@WebMvcTest(controllers = {TenantBillingController.class, InternalBillingController.class,
        PlatformSubscriptionController.class, PlatformPlanController.class})
@Import({SecurityConfig.class, CorsConfig.class, BillingAuthorization.class, SecurityWiringTest.TestBeans.class})
@TestPropertySource(properties = {
        "jwt.secret=" + SecurityWiringTest.SECRET,
        "billing.internal.api-key=" + SecurityWiringTest.API_KEY
})
class SecurityWiringTest {

    static final String SECRET = "test-secret-key-which-is-long-enough-for-hs256";
    static final String API_KEY = "internal-test-key";

    @TestConfiguration
    @EnableConfigurationProperties(BillingProperties.class)
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
    void webhooksAreClosedUntilPr1b() throws Exception {
        mvc.perform(post("/webhooks/mercadopago")).andExpect(status().isUnauthorized());
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
