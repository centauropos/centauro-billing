package com.centauro.billing.application.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PaymentView(
        UUID id,
        String source,
        String status,
        String amount,
        String currency,
        String gatewayPaymentId,
        OffsetDateTime periodStart,
        OffsetDateTime periodEnd,
        OffsetDateTime paidAt,
        boolean applied,
        String reference,
        String notes,
        String registeredBy
) {
}