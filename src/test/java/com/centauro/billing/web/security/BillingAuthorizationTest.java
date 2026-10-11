package com.centauro.billing.web.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BillingAuthorizationTest {

    private final BillingAuthorization auth = new BillingAuthorization();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void adminRoleCanManage() {
        authenticate(new JwtClaims("u", "t-1", "ADMIN", List.of()));

        assertThat(auth.canManage()).isTrue();
        assertThat(auth.hasTenant()).isTrue();
    }

    @Test
    void billingManagePermissionCanManage() {
        authenticate(new JwtClaims("u", "t-1", "CASHIER", List.of("BILLING_MANAGE")));

        assertThat(auth.canManage()).isTrue();
    }

    @Test
    void otherRolesCannotManage() {
        authenticate(new JwtClaims("u", "t-1", "CASHIER", List.of("SALES_CREATE")));

        assertThat(auth.canManage()).isFalse();
        assertThat(auth.hasTenant()).isTrue();
    }

    @Test
    void legacyTokenWithoutRoleOrPermissionsCannotManage() {
        authenticate(new JwtClaims("u", "t-1", null, null));

        assertThat(auth.canManage()).isFalse();
    }

    @Test
    void adminWithoutTenantCannotManage() {
        authenticate(new JwtClaims("u", null, "ADMIN", List.of()));

        assertThat(auth.canManage()).isFalse();
        assertThat(auth.hasTenant()).isFalse();
    }

    @Test
    void platformAdminRequiresThePermission() {
        authenticate(new JwtClaims("u", "t-1", "ADMIN", List.of("BILLING_MANAGE")));
        assertThat(auth.isPlatformAdmin()).isFalse();

        authenticate(new JwtClaims("u", "t-1", "USER", List.of("PLATFORM_ADMIN")));
        assertThat(auth.isPlatformAdmin()).isTrue();
    }

    @Test
    void legacyTokenIsNotPlatformAdmin() {
        authenticate(new JwtClaims("u", "t-1", null, null));

        assertThat(auth.isPlatformAdmin()).isFalse();
    }

    @Test
    void unauthenticatedHasNothing() {
        assertThat(auth.canManage()).isFalse();
        assertThat(auth.isPlatformAdmin()).isFalse();
        assertThat(auth.hasTenant()).isFalse();
    }

    private static void authenticate(JwtClaims claims) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(claims, null, List.of()));
    }
}
