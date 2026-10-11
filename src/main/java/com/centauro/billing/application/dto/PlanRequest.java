package com.centauro.billing.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Map;
import java.util.Set;

public record PlanRequest(
        @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,39}$") String code,
        @NotBlank @Size(max = 100) String name,
        String description,
        Boolean active,
        Boolean publicVisible,
        Boolean customPricing,
        Integer sortOrder,
        Set<String> features,
        Map<String, Integer> limits
) {
}