package com.centauro.billing.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

/** {@code currentPeriodEnd} es opcional solo si hay pagos aprobados sin aplicar que cubran el período. */
public record ReactivateRequest(OffsetDateTime currentPeriodEnd, @NotBlank @Size(max = 1000) String reason) {
}
