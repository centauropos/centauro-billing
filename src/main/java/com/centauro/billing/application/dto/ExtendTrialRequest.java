package com.centauro.billing.application.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

public record ExtendTrialRequest(
        @Min(1) @Max(365) Integer days,
        OffsetDateTime trialEndsAt,
        @NotBlank @Size(max = 1000) String reason
) {
}