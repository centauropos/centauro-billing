package com.centauro.billing.web.security;

import io.jsonwebtoken.Claims;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Claims que emite auth-service. {@code role} con fallback a {@code roleId.authority}, como pos-manager. */
public record JwtClaims(String subject, String tenantId, String role, List<String> permissions) {

    public JwtClaims {
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
    }

    public static JwtClaims from(Claims claims) {
        return new JwtClaims(claims.getSubject(), stringClaim(claims.get("tenantId")), resolveRole(claims),
                resolvePermissions(claims.get("permissions")));
    }

    public boolean hasTenant() {
        return tenantId != null && !tenantId.isBlank();
    }

    public boolean hasPermission(String permission) {
        return permissions.contains(permission);
    }

    private static String resolveRole(Claims claims) {
        String role = stringClaim(claims.get("role"));
        if (role != null) {
            return role;
        }
        if (claims.get("roleId") instanceof Map<?, ?> roleId) {
            return stringClaim(roleId.get("authority"));
        }
        return null;
    }

    private static List<String> resolvePermissions(Object value) {
        if (value instanceof Collection<?> collection) {
            return collection.stream().filter(v -> v != null).map(Object::toString).toList();
        }
        return List.of();
    }

    private static String stringClaim(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }
}