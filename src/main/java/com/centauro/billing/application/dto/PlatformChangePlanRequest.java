package com.centauro.billing.application.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** {@code customAmount} es obligatorio para planes con precio a medida y prohibido para el resto. */
public record PlatformChangePlanRequest(
        @NotBlank @Size(max = 40) String planCode,
        @NotBlank @Size(max = 1000) String reason,
        Boolean syncGateway,
        @Digits(integer = 10, fraction = 2) BigDecimal customAmount
) {
}
