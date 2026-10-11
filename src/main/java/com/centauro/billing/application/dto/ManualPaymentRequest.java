package com.centauro.billing.application.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** {@code reference} es la clave de idempotencia del pago manual dentro del tenant. */
public record ManualPaymentRequest(
        @NotNull @Digits(integer = 10, fraction = 2) BigDecimal amount,
        String currency,
        OffsetDateTime paidAt,
        @NotBlank @Size(max = 255) String reference,
        OffsetDateTime periodEnd,
        @Size(max = 2000) String notes,
        @NotBlank @Size(max = 1000) String reason
) {
}
