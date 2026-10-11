package com.centauro.billing.application.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PendingCheckoutView(UUID checkoutId, String initPoint, OffsetDateTime expiresAt) {
}