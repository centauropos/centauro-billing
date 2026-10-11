package com.centauro.billing.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;
import java.util.Set;

public record PlanUpdateRequest(
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