package com.centauro.billing.application.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PlatformTrialRequest(
        @Size(max = 255) String companyName,
        @Email @Size(max = 255) String billingEmail,
        @Size(max = 40) String planCode,
        @NotBlank @Size(max = 1000) String reason
) {
}