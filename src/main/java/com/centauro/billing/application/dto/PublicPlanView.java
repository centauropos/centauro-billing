package com.centauro.billing.application.dto;

import java.util.Map;
import java.util.Set;

public record PublicPlanView(
        String code,
        String name,
        String description,
        Set<String> features,
        Map<String, Integer> limits,
        PriceView price,
        boolean current
) {
}