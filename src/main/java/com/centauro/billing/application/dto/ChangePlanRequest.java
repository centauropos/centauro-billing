package com.centauro.billing.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePlanRequest(@NotBlank @Size(max = 40) String planCode) {
}