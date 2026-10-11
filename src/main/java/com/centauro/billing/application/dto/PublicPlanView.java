package com.centauro.billing.application.dto;

import java.util.Map;
import java.util.Set;

/** Con {@code customPricing = true} el precio es {@code null}: el front muestra "Consultar". */
public record PublicPlanView(
        String code,
        String name,
        String description,
        Set<String> features,
        Map<String, Integer> limits,
        PriceView price,
        boolean customPricing,
        boolean current
) {
}
