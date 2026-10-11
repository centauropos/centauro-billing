package com.centauro.billing.web.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static com.centauro.billing.support.TestFixtures.AR;
import static com.centauro.billing.support.TestFixtures.NOW;
import static com.centauro.billing.support.TestFixtures.clockAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenFilterTest {

    private static final String SECRET = "my-secret-key-which-should-be-very-long-and-secure";

    private final SecurityErrorWriter writer = new SecurityErrorWriter(
            new ObjectMapper().registerModule(new JavaTimeModule()), clockAt(NOW), AR);
    private final JwtTokenFilter filter = new JwtTokenFilter(SECRET, writer);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validTokenAuthenticatesWithClaims() throws Exception {
        MockHttpServletRequest request = withToken(token(SECRET, Map.of("tenantId", "t-1", "role", "ADMIN",
                "permissions", List.of("BILLING_MANAGE"))));
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        JwtClaims claims = TenantContextHolder.requireClaims();
        assertThat(claims.subject()).isEqualTo("owner");
        assertThat(claims.tenantId()).isEqualTo("t-1");
        assertThat(claims.role()).isEqualTo("ADMIN");
        assertThat(claims.permissions()).containsExactly("BILLING_MANAGE");
    }

    @Test
    void roleFallsBackToRoleIdAuthority() throws Exception {
        MockHttpServletRequest request = withToken(token(SECRET, Map.of("tenantId", "t-1",
                "roleId", Map.of("authority", "ADMIN"))));

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(TenantContextHolder.requireClaims().role()).isEqualTo("ADMIN");
    }

    @Test
    void tokenSignedWithAnotherSecretIs401() throws Exception {
        MockHttpServletRequest request = withToken(token("another-secret-key-that-is-long-enough-123",
                Map.of("tenantId", "t-1")));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":\"INVALID_TOKEN\"");
        assertThat(chain.getRequest()).isNull();
        assertThat(TenantContextHolder.currentClaims()).isEmpty();
    }

    @Test
    void garbageTokenIs401() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(withToken("not-a-jwt"), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void missingHeaderContinuesUnauthenticated() throws Exception {
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(TenantContextHolder.currentClaims()).isEmpty();
    }

    @Test
    void shortSecretFailsAtStartup() {
        assertThatThrownBy(() -> new JwtTokenFilter("too-short", writer)).isInstanceOf(IllegalStateException.class);
    }

    private static MockHttpServletRequest withToken(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/billing/subscription");
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    static String token(String secret, Map<String, Object> claims) {
        return Jwts.builder()
                .subject("owner")
                .claims(claims)
                .issuedAt(Date.from(NOW.toInstant()))
                .expiration(Date.from(NOW.plusDays(5).toInstant().plusSeconds(3_650L * 24 * 3600)))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }
}
