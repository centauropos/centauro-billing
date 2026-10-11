package com.centauro.billing.application.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record PlanPriceRequest(@NotNull @Digits(integer = 10, fraction = 2) BigDecimal amount,
                               @NotNull OffsetDateTime validFrom) {
}
