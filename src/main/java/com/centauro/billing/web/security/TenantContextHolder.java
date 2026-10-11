package com.centauro.billing.web.security;

import com.centauro.billing.domain.vo.Actor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

public final class TenantContextHolder {

    private TenantContextHolder() {
    }

    public static Optional<JwtClaims> currentClaims() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof JwtClaims claims) {
            return Optional.of(claims);
        }
        return Optional.empty();
    }

    public static JwtClaims requireClaims() {
        return currentClaims().orElseThrow(() -> new AuthenticationCredentialsNotFoundException("Sin JWT"));
    }

    public static String requireTenantId() {
        JwtClaims claims = requireClaims();
        if (!claims.hasTenant()) {
            throw new AccessDeniedException("El token no tiene tenantId");
        }
        return claims.tenantId();
    }

    public static String currentSubject() {
        return requireClaims().subject();
    }

    public static Actor platformAdminActor() {
        JwtClaims claims = requireClaims();
        return Actor.platformAdmin(claims.subject(), claims.userId());
    }

    public static Actor tenantUserActor() {
        JwtClaims claims = requireClaims();
        return Actor.tenantUser(claims.subject(), claims.userId());
    }
}