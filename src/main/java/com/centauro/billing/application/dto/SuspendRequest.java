package com.centauro.billing.application.dto;

import com.centauro.billing.domain.enums.SuspendMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SuspendRequest(@NotNull SuspendMode mode, @NotBlank @Size(max = 1000) String reason) {
}