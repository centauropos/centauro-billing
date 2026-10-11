package com.centauro.billing.domain.vo;

import com.centauro.billing.domain.exception.InvalidBillingRequestException;

import java.util.regex.Pattern;

public final class TenantIds {

    private static final Pattern FORMAT = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private TenantIds() {
    }

    public static String requireValid(String tenantId) {
        if (tenantId == null || !FORMAT.matcher(tenantId).matches()) {
            throw new InvalidBillingRequestException("Formato de tenantId inválido");
        }
        return tenantId;
    }
}