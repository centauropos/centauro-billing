package com.centauro.billing.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PlatformChangePlanRequest(
        @NotBlank @Size(max = 40) String planCode,
        @NotBlank @Size(max = 1000) String reason,
        Boolean syncGateway
) {
}