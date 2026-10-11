package com.centauro.billing.web.security;

import org.springframework.stereotype.Component;

/**
 * Chequeos estrictos usados desde {@code @PreAuthorize}: sin modo sombra ni pase para tokens legacy.
 */
@Component("billingAuth")
public class BillingAuthorization {

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String BILLING_MANAGE = "BILLING_MANAGE";
    public static final String PLATFORM_ADMIN = "PLATFORM_ADMIN";

    public boolean hasTenant() {
        return TenantContextHolder.currentClaims().map(JwtClaims::hasTenant).orElse(false);
    }

    public boolean canManage() {
        return TenantContextHolder.currentClaims().map(BillingAuthorization::canManage).orElse(false);
    }

    public boolean isPlatformAdmin() {
        return TenantContextHolder.currentClaims().map(BillingAuthorization::isPlatformAdmin).orElse(false);
    }

    public static boolean canManage(JwtClaims claims) {
        return claims.hasTenant()
                && (ROLE_ADMIN.equalsIgnoreCase(claims.role()) || claims.hasPermission(BILLING_MANAGE));
    }

    public static boolean isPlatformAdmin(JwtClaims claims) {
        return claims.hasPermission(PLATFORM_ADMIN);
    }
}