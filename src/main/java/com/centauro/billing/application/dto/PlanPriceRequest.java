package com.centauro.billing.application.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record PlanPriceRequest(@NotNull BigDecimal amount, @NotNull OffsetDateTime validFrom) {
}