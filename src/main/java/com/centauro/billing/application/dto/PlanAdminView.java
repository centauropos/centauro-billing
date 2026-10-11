package com.centauro.billing.application.dto;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record PlanAdminView(
        UUID id,
        String code,
        String name,
        String description,
        boolean active,
        boolean publicVisible,
        int sortOrder,
        Set<String> features,
        Map<String, Integer> limits,
        PlanPriceView currentPrice,
        List<PlanPriceView> upcomingPrices
) {
}