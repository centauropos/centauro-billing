package com.centauro.billing.application.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

public record TrialRequest(
        @Size(max = 255) String companyName,
        @Email @Size(max = 255) String billingEmail,
        @Size(max = 40) String planCode,
        @Size(max = 60) String source
) {
}