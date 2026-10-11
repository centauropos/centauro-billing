package com.centauro.billing.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PlatformCancelRequest(Boolean immediate, @NotBlank @Size(max = 1000) String reason) {
}