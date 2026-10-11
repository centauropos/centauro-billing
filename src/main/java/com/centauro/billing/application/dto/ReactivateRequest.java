package com.centauro.billing.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

public record ReactivateRequest(@NotNull OffsetDateTime currentPeriodEnd, @NotBlank @Size(max = 1000) String reason) {
}