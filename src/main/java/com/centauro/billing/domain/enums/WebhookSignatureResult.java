package com.centauro.billing.domain.enums;

public enum WebhookSignatureResult {
    VALID, MISSING_SECRET, MALFORMED, EXPIRED, INVALID;

    public boolean isValid() {
        return this == VALID;
    }
}
