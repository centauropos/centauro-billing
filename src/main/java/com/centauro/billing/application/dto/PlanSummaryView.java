package com.centauro.billing.application.dto;

import java.util.Map;
import java.util.Set;

public record PlanSummaryView(String code, String name, boolean customPricing, Set<String> features,
                              Map<String, Integer> limits) {
}
