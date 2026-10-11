package com.centauro.billing.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record ManualPaymentRequest(
        @NotNull BigDecimal amount,
        String currency,
        OffsetDateTime paidAt,
        @Size(max = 255) String reference,
        OffsetDateTime periodEnd,
        @Size(max = 2000) String notes,
        @NotBlank @Size(max = 1000) String reason
) {
}